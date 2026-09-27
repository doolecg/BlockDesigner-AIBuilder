package io.blockdesigner.aibuilder.models;

import io.blockdesigner.aibuilder.models.ModelCatalog.ModelEntry;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Gets the built-in model answering: checks it is downloaded, installs the engine if needed (a small download),
 * starts the background server and, if the graphics card build fails to start, falls back to the CPU build.
 */
public final class LocalModels {
    private final ModelCatalog catalog;
    private final ModelStore store;
    private final Engine engine;
    private final LocalServer server;
    private final Path log;

    public LocalModels(ModelCatalog catalog, ModelStore store, Engine engine, LocalServer server, Path log) {
        this.catalog = catalog;
        this.store = store;
        this.engine = engine;
        this.server = server;
        this.log = log;
    }

    public LocalServer server() {
        return server;
    }

    /**
     * Makes sure the model is running and returns its OpenAI-compatible base URL. Blocks (downloads, model loading):
     * call it off the UI thread.
     */
    public URI ensureReady(String modelId, Engine.Variant variant, int contextSize, Consumer<String> status) throws IOException {
        ModelEntry entry = catalog.find(modelId).orElseThrow(() -> new IOException("Pick a model on the Models page first"));
        if (!store.installed(entry)) {
            throw new IOException("Download " + entry.name() + " on the Models page first (" + Sizes.gb(entry.totalSize()) + ")");
        }
        Engine.Installed eng = engine.installed(variant).orElse(null);
        if (eng == null) eng = install(variant, status);
        Path model = store.path(entry, entry.model().orElseThrow());
        Path mmproj = entry.mmproj().map(f -> store.path(entry, f)).orElse(null);
        LocalServer.Launch launch = new LocalServer.Launch(eng.server(), model, mmproj, contextSize, variant != Engine.Variant.CPU, log);
        if (server.running(launch)) {
            server.touch();
            return server.baseUrl();
        }
        status.accept("Starting " + entry.name() + "…");
        try {
            server.start(launch, Duration.ofMinutes(5));
        } catch (IOException e) {
            if (variant == Engine.Variant.CPU) throw e;
            status.accept("The graphics card build didn't start; trying the CPU build…");
            Engine.Installed cpu = engine.installed(Engine.Variant.CPU).orElse(null);
            if (cpu == null) cpu = install(Engine.Variant.CPU, status);
            server.start(new LocalServer.Launch(cpu.server(), model, mmproj, contextSize, false, log), Duration.ofMinutes(5));
        }
        return server.baseUrl();
    }

    /** Downloads and unpacks the engine on the calling thread. */
    public Engine.Installed install(Engine.Variant variant, Consumer<String> status) throws IOException {
        status.accept("Looking for the model engine…");
        Engine.Pick pick = engine.findLatest(variant);
        AtomicReference<Engine.Installed> done = new AtomicReference<>();
        DownloadJob job = engine.installJob(pick, p -> status.accept("Downloading the model engine (" + Sizes.gb(pick.size()) + "): " + p.describe()), done::set);
        job.run();
        DownloadJob.Progress p = job.progress();
        if (p.state() != DownloadJob.State.DONE || done.get() == null) {
            throw new IOException("The model engine couldn't be installed: " + Optional.ofNullable(p.message()).orElse(p.state().name()));
        }
        return done.get();
    }
}
