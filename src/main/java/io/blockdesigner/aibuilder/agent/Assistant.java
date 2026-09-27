package io.blockdesigner.aibuilder.agent;

import io.blockdesigner.aibuilder.build.BuildRunner;
import io.blockdesigner.aibuilder.build.CommandScript;
import io.blockdesigner.aibuilder.build.OverlayWorld;
import io.blockdesigner.aibuilder.build.Styles;
import io.blockdesigner.aibuilder.image.Attachment;
import io.blockdesigner.aibuilder.llm.Cancel;
import io.blockdesigner.aibuilder.llm.ChatClient;
import io.blockdesigner.aibuilder.llm.ChatMessage;
import io.blockdesigner.core.model.BlockPos;
import io.blockdesigner.core.model.BlockState;
import io.blockdesigner.core.model.Box;
import io.blockdesigner.core.model.Layer;
import io.blockdesigner.core.place.BlockPlacement.Dir;
import io.blockdesigner.plugin.PluginCommand;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.function.Function;

/**
 * One conversation with the model. A turn: describe the scene, send the request, take the commands from the reply's
 * fenced block, try them against a copy of the scene, send any errors back for up to {@value #REPAIR_ROUNDS} repair
 * rounds, then apply the result as one undo step (a new build goes into a layer of its own).
 */
public final class Assistant {
    public static final int REPAIR_ROUNDS = 3;
    static final int HISTORY_MESSAGES = 8;

    /** BlockDesigner's side: the scene, and applying changes. Everything but {@link #onUi} is called on the UI thread. */
    public interface Host {
        /** Runs {@code task} on the UI thread and waits for its result. */
        <T> T onUi(Callable<T> task) throws Exception;

        List<Layer> layers();

        Optional<Box> selection();

        /** Applies the overlay's changes as one undo step; a new build becomes a new layer named {@code layerName}. */
        Applied apply(String label, Scope scope, OverlayWorld overlay, String layerName);

        /** Takes a turn's changes back (blocks changed since are left alone). */
        void undo(Applied applied);
    }

    /** What a turn changed, enough to take it back. */
    public record Applied(String label, Scope scope, Map<BlockPos, BlockState> before, Map<BlockPos, BlockState> after, Layer layer) {
        public int changed() {
            return after.size();
        }
    }

    /** Progress for the chat page; called on the worker thread. */
    public interface Listener {
        default void status(String message) {
        }

        default void text(String piece) {
        }

        /** A new reply starts (a repair round): the text so far can be cleared. */
        default void newReply(int round) {
        }

        default void tried(int round, BuildRunner.Report report) {
        }
    }

    /**
     * @param reply    the model's last reply
     * @param prose    the reply without its code block
     * @param commands the commands that were run (empty when the reply had none)
     * @param report   the last try's per-line results, or null when nothing ran
     * @param applied  what changed, or null when nothing did
     */
    public record Turn(String prompt, String reply, String prose, List<String> commands, BuildRunner.Report report, int rounds,
                       Applied applied, Scope scope) {
    }

    private final BuildRunner runner;
    private final List<PluginCommand> commands;
    private final Host host;
    private final List<ChatMessage> history = new ArrayList<>();

    public Assistant(BuildRunner runner, List<PluginCommand> commands, Host host) {
        this.runner = runner;
        this.commands = commands;
        this.host = host;
    }

    public synchronized void clear() {
        history.clear();
    }

    /**
     * Runs one turn on the calling (worker) thread.
     *
     * @param style     a style name, or "auto"
     * @param imageNote the picture's colour hints (null without a picture)
     */
    public Turn run(String prompt, Attachment image, String imageNote, Scope.Choice choice, String style, int maxBlocks,
                    ChatClient client, Listener listener, Cancel cancel) throws IOException {
        listener.status("Looking at the scene…");
        record Setup(Scope scope, String summary) {
        }
        Setup setup = ui(() -> {
            List<Layer> layers = host.layers();
            Scope scope = Scope.choose(choice, host.selection(), SceneSummary.bounds(layers));
            return new Setup(scope, SceneSummary.describe(layers, scope));
        });
        Scope scope = setup.scope();
        String notes = imageNote;
        if (image != null && !client.vision()) {
            notes = (notes == null ? "" : notes) + "(The user attached a picture, but this model can't see pictures: go by the colours above and the request.)\n";
        }
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system(SystemPrompt.build(commands, style, setup.summary(), notes)));
        synchronized (this) {
            messages.addAll(history);
        }
        String userText = prompt.isBlank() && image != null ? "Build what the picture shows." : prompt;
        messages.add(image != null && client.vision() ? ChatMessage.user(userText, List.of(image.image())) : ChatMessage.user(userText));

        long seed = new Random().nextLong();
        Styles.Style st = Styles.get(style == null || style.equals("auto") ? Styles.DEFAULT : style);
        BuildRunner.Settings settings = new BuildRunner.Settings(maxBlocks, st, scope.anchor(), Dir.NORTH, scope.box(), seed);

        String reply = "";
        List<String> script = List.of();
        BuildRunner.Report report = null;
        OverlayWorld overlay = null;
        int round = 0;
        while (true) {
            cancel.check();
            listener.newReply(round);
            listener.status(round == 0 ? "Thinking… (" + client.describe() + ")" : "Fixing the commands (try " + round + " of " + REPAIR_ROUNDS + ")…");
            reply = client.chat(messages, listener::text, cancel);
            script = CommandScript.extract(reply);
            if (script.isEmpty()) break;
            listener.status("Trying " + script.size() + " command" + (script.size() == 1 ? "" : "s") + "…");
            List<String> lines = script;
            record Tried(OverlayWorld overlay, BuildRunner.Report report) {
            }
            Tried tried = ui(() -> {
                OverlayWorld o = new OverlayWorld(new SceneWorld(host.layers()));
                return new Tried(o, runner.run(lines, o, settings));
            });
            overlay = tried.overlay();
            report = tried.report();
            listener.tried(round, report);
            if (report.ok() || round >= REPAIR_ROUNDS || cancel.cancelled()) break;
            round++;
            messages.add(ChatMessage.assistant(reply));
            messages.add(ChatMessage.user(SystemPrompt.repair(report.errorText(), round, REPAIR_ROUNDS)));
        }
        cancel.check();

        Applied applied = null;
        if (overlay != null && !overlay.changes().isEmpty()) {
            listener.status("Applying…");
            String label = "AI: " + shorten(prompt.isBlank() ? "picture" : prompt, 48);
            String layerName = "AI: " + shorten(prompt.isBlank() ? "picture" : prompt, 28);
            OverlayWorld o = overlay;
            applied = ui(() -> host.apply(label, scope, o, layerName));
        }
        synchronized (this) {
            history.add(ChatMessage.user(image != null ? userText + "\n(with a reference picture)" : userText));
            history.add(ChatMessage.assistant(reply));
            while (history.size() > HISTORY_MESSAGES) history.removeFirst();
        }
        return new Turn(prompt, reply, CommandScript.prose(reply), script, report, round + (script.isEmpty() ? 0 : 1), applied, scope);
    }

    /** Takes a turn back, on the UI thread. */
    public void undo(Applied applied) throws IOException {
        ui(() -> {
            host.undo(applied);
            return null;
        });
    }

    static String shorten(String s, int max) {
        String t = s.strip().replaceAll("\\s+", " ");
        return t.length() <= max ? t : t.substring(0, max - 1) + "…";
    }

    private <T> T ui(Callable<T> task) throws IOException {
        try {
            return host.onUi(task);
        } catch (IOException | RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    /** The before/after maps of an edit: what the base had where the overlay changes things. */
    public static Applied edit(String label, Scope scope, OverlayWorld overlay, Function<BlockPos, BlockState> before) {
        Map<BlockPos, BlockState> after = overlay.changes();
        Map<BlockPos, BlockState> was = new java.util.LinkedHashMap<>();
        after.keySet().forEach(p -> was.put(p, before.apply(p)));
        return new Applied(label, scope, was, after, null);
    }
}
