package io.blockdesigner.aibuilder;

import io.blockdesigner.aibuilder.agent.Assistant;
import io.blockdesigner.aibuilder.build.BuildRunner;
import io.blockdesigner.aibuilder.build.BuildState;
import io.blockdesigner.aibuilder.build.Materials;
import io.blockdesigner.aibuilder.build.Styles;
import io.blockdesigner.aibuilder.image.ImageHints;
import io.blockdesigner.aibuilder.llm.AnthropicClient;
import io.blockdesigner.aibuilder.llm.ChatClient;
import io.blockdesigner.aibuilder.llm.OpenAiCompatibleClient;
import io.blockdesigner.aibuilder.llm.Secrets;
import io.blockdesigner.aibuilder.models.DownloadJob;
import io.blockdesigner.aibuilder.models.Downloader;
import io.blockdesigner.aibuilder.models.Engine;
import io.blockdesigner.aibuilder.models.LocalModels;
import io.blockdesigner.aibuilder.models.LocalServer;
import io.blockdesigner.aibuilder.models.ModelCatalog;
import io.blockdesigner.aibuilder.models.ModelCatalog.ModelEntry;
import io.blockdesigner.aibuilder.models.ModelStore;
import io.blockdesigner.aibuilder.ui.AssistantPanel;
import io.blockdesigner.aibuilder.ui.ModelsPanel;
import io.blockdesigner.core.model.BlockState;
import io.blockdesigner.plugin.PluginCommand;
import io.blockdesigner.plugin.PluginContext;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** The plugin's running parts, made on enable and released on disable. The pages talk to it. */
public final class AiBuilder {
    public final PluginContext ctx;
    public final BuildState state;
    public final Materials materials;
    public final List<PluginCommand> commands;
    public final Path data;
    public final Path settingsFile;
    public AiSettings settings;
    public final HttpClient http = Downloader.defaultClient();
    public final Downloader downloader = new Downloader(http);
    public ModelCatalog catalog;
    public final ModelStore store;
    public final Engine engine;
    public final LocalServer server;
    public final LocalModels local;
    public final Secrets secrets;
    public final Assistant assistant;
    public final ExecutorService workers = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "ai-builder-worker");
        t.setDaemon(true);
        return t;
    });
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "ai-builder-idle");
        t.setDaemon(true);
        return t;
    });
    /** Model downloads in progress, by model id; the engine's under "engine". */
    public final Map<String, DownloadJob> jobs = new ConcurrentHashMap<>();
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    private Map<String, Integer> blockColours;

    AiBuilder(PluginContext ctx, BuildState state, Materials materials, List<PluginCommand> commands) {
        this.ctx = ctx;
        this.state = state;
        this.materials = materials;
        this.commands = commands;
        this.data = ctx.dataFolder();
        this.settingsFile = data.resolve("settings.json");
        this.settings = AiSettings.load(settingsFile);
        this.store = new ModelStore(data.resolve("models"));
        this.engine = new Engine(data.resolve("engine"), http, downloader, Engine.RELEASES);
        this.server = new LocalServer(http, LocalServer.PROCESS);
        this.secrets = new Secrets(data.resolve("keys"), Secrets.POWERSHELL);
        try {
            this.catalog = ModelCatalog.load(data.resolve("custom-models.json"));
        } catch (IOException e) {
            throw new IllegalStateException("The model list couldn't be read: " + e.getMessage(), e);
        }
        this.local = new LocalModels(catalog, store, engine, server, data.resolve("logs").resolve("llama-server.log"));
        BuildRunner runner = new BuildRunner(commands, text -> {
            try {
                return ctx.blocks().resolve(text);
            } catch (IllegalArgumentException e) {
                return null;
            }
        }, state);
        this.assistant = new Assistant(runner, commands, new AppHost(ctx));
        applyStyle();
    }

    void start() {
        ctx.registerPanel(new AssistantPanel(this));
        ctx.registerPanel(new ModelsPanel(this));
        server.onStatus(s -> changed());
        timer.scheduleAtFixedRate(this::checkIdle, 1, 1, TimeUnit.MINUTES);
        if (settings.startAtLaunch && settings.provider == AiSettings.Provider.BUILT_IN) {
            catalog.find(settings.activeModel).filter(store::installed).ifPresent(e -> workers.execute(() -> {
                try {
                    startLocal(msg -> { });
                } catch (IOException ex) {
                    ctx.log("The built-in model didn't start: " + ex.getMessage());
                }
            }));
        }
    }

    void stop() {
        jobs.values().forEach(DownloadJob::cancel);
        server.stop();
        timer.shutdownNow();
        workers.shutdownNow();
    }

    private void checkIdle() {
        int minutes = settings.idleMinutes;
        if (minutes > 0 && server.status() == LocalServer.Status.READY && server.idleMillis() > minutes * 60_000L) {
            ctx.log("Stopping the built-in model after " + minutes + " idle minutes");
            server.stop();
        }
    }

    /** Pages listen here for any change worth redrawing (downloads, server state, settings). */
    public void onChange(Runnable r) {
        listeners.add(r);
    }

    public void changed() {
        ctx.runOnUiThread(() -> listeners.forEach(Runnable::run));
    }

    public void saveSettings() {
        applyStyle();
        try {
            settings.save(settingsFile);
        } catch (IOException e) {
            ctx.log("Settings not saved: " + e.getMessage());
        }
        changed();
    }

    private void applyStyle() {
        state.setStyle(Styles.get("auto".equals(settings.style) ? Styles.DEFAULT : settings.style));
    }

    public Engine.Variant variant() {
        return Engine.Variant.fromId(settings.engineVariant);
    }

    public Optional<ModelEntry> activeModel() {
        return catalog.find(settings.activeModel);
    }

    /** Starts the built-in model (installing the engine if needed). Blocks. */
    public URI startLocal(Consumer<String> status) throws IOException {
        URI u = local.ensureReady(settings.activeModel, variant(), settings.contextSize, status);
        changed();
        return u;
    }

    /** The model to talk to, per the settings; may start the built-in one. Blocks: call off the UI thread. */
    public ChatClient client(Consumer<String> status) throws IOException {
        AiSettings s = settings;
        return switch (s.provider) {
            case BUILT_IN -> {
                ModelEntry e = activeModel().orElseThrow(() -> new IOException("Pick a model on the Models page"));
                URI base = startLocal(status);
                yield new OpenAiCompatibleClient(http, base, null, "", e.vision(), e.name() + " on this PC", 0.3);
            }
            case LOCAL_SERVER -> new OpenAiCompatibleClient(http, URI.create(s.localServerUrl.strip()), null, s.localServerModel,
                    s.localServerVision, (s.localServerModel.isBlank() ? "Local server" : s.localServerModel), 0.3);
            case CLAUDE -> new AnthropicClient(http, AnthropicClient.defaultBase(), secrets.load("anthropic").orElseThrow(() ->
                    new IOException("Add your Anthropic API key on the Models page (Advanced)")), s.claudeModel);
            case OPENAI -> new OpenAiCompatibleClient(http, URI.create(s.openaiUrl.strip()), secrets.load("openai").orElseThrow(() ->
                    new IOException("Add your OpenAI API key on the Models page (Advanced)")), s.openaiModel, true,
                    "OpenAI-compatible (" + s.openaiModel + ")", null);
        };
    }

    /** Starts downloading a model (does nothing when it is already downloading). */
    public DownloadJob download(ModelEntry e) {
        DownloadJob running = jobs.get(e.id());
        if (running != null && !running.finished()) return running;
        DownloadJob job = store.download(e, downloader, p -> {
            if (p.state() == DownloadJob.State.DONE || p.state() == DownloadJob.State.FAILED || p.state() == DownloadJob.State.CANCELLED) {
                if (p.state() == DownloadJob.State.FAILED) ctx.log("Download of " + e.name() + " failed: " + p.message());
                if (p.state() == DownloadJob.State.DONE) ctx.toast(e.name() + " is ready");
            }
            changed();
        }, (item, size) -> {
            if (e.custom()) catalog.learnSize(e.id(), item.target().getFileName().toString(), size);
        });
        jobs.put(e.id(), job);
        job.start(workers);
        changed();
        return job;
    }

    /** Installs (or updates) the engine of the current variant in the background. */
    public void installEngine() {
        DownloadJob running = jobs.get("engine");
        if (running != null && !running.finished()) return;
        Engine.Variant v = variant();
        workers.execute(() -> {
            try {
                Engine.Pick pick = engine.findLatest(v);
                DownloadJob job = engine.installJob(pick, p -> changed(), inst -> ctx.log("Engine " + inst.tag() + " (" + v.id + ") installed"));
                jobs.put("engine", job);
                changed();
                job.run();
                if (job.progress().state() == DownloadJob.State.DONE && server.status() == LocalServer.Status.READY) server.stop();
            } catch (IOException e) {
                ctx.log("Engine install failed: " + e.getMessage());
                ctx.runOnUiThread(() -> ctx.toast("The engine couldn't be installed: " + e.getMessage()));
            }
            changed();
        });
    }

    /** Colours of the building blocks for picture hints: the loaded game's when available. Call on the UI thread. */
    public Map<String, Integer> blockColours() {
        if (blockColours != null) return blockColours;
        Map<String, Integer> out = new LinkedHashMap<>();
        boolean loaded = ctx.assets().available();
        for (var e : ImageHints.BUILDING_BLOCKS.entrySet()) {
            if (!materials.exists(e.getKey())) continue;
            int colour = e.getValue();
            if (loaded) {
                try {
                    BlockState st = ctx.blocks().resolve(e.getKey());
                    colour = ctx.blocks().averageColor(st) & 0xFFFFFF;
                } catch (RuntimeException ignored) {
                    // keep the table's colour
                }
            }
            out.put(e.getKey(), colour);
        }
        if (out.isEmpty()) out.putAll(ImageHints.BUILDING_BLOCKS);
        if (loaded) blockColours = out;   // before assets load, ask again next time
        return out;
    }
}
