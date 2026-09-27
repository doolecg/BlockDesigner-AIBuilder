package io.blockdesigner.aibuilder.agent;

import io.blockdesigner.aibuilder.TestBlocks;
import io.blockdesigner.aibuilder.build.BuildRunner;
import io.blockdesigner.aibuilder.build.BuildState;
import io.blockdesigner.aibuilder.build.BuilderCommands;
import io.blockdesigner.aibuilder.build.OverlayWorld;
import io.blockdesigner.aibuilder.image.Attachment;
import io.blockdesigner.aibuilder.llm.Cancel;
import io.blockdesigner.aibuilder.llm.ChatClient;
import io.blockdesigner.aibuilder.llm.ChatMessage;
import io.blockdesigner.core.model.BlockPos;
import io.blockdesigner.core.model.BlockState;
import io.blockdesigner.core.model.Box;
import io.blockdesigner.core.model.Layer;
import io.blockdesigner.core.model.Structure;
import io.blockdesigner.plugin.PluginCommand;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AssistantTest {
    /** Replies from a list, recording what it was sent. */
    static final class ScriptedModel implements ChatClient {
        final Deque<String> replies;
        final List<List<ChatMessage>> sent = new ArrayList<>();
        boolean vision = true;

        ScriptedModel(String... replies) {
            this.replies = new ArrayDeque<>(List.of(replies));
        }

        public String chat(List<ChatMessage> messages, Consumer<String> onText, Cancel cancel) throws IOException {
            sent.add(List.copyOf(messages));
            cancel.check();
            String r = replies.removeFirst();
            onText.accept(r);
            return r;
        }

        public boolean vision() {
            return vision;
        }

        public String describe() {
            return "scripted";
        }
    }

    /** The scene as plain layers; edits go straight into the first layer, new builds into a new one. */
    static final class FakeHost implements Assistant.Host {
        final List<Layer> layers = new ArrayList<>();
        Optional<Box> selection = Optional.empty();
        int undoSteps;

        public <T> T onUi(Callable<T> task) throws Exception {
            return task.call();
        }

        public List<Layer> layers() {
            return layers;
        }

        public Optional<Box> selection() {
            return selection;
        }

        public Assistant.Applied apply(String label, Scope scope, OverlayWorld overlay, String layerName) {
            undoSteps++;
            if (scope.newLayer()) {
                Layer l = new Layer(layerName, overlay.toStructure());
                layers.add(l);
                return new Assistant.Applied(label, scope, java.util.Map.of(), overlay.changes(), l);
            }
            SceneWorld world = new SceneWorld(layers);
            Assistant.Applied a = Assistant.edit(label, scope, overlay, world::get);
            overlay.changes().forEach((p, s) -> layers.getFirst().structure().set(p, s));
            return a;
        }

        public void undo(Assistant.Applied a) {
            if (a.layer() != null) {
                layers.remove(a.layer());
                return;
            }
            a.before().forEach((p, s) -> {
                if (layers.getFirst().structure().get(p) == a.after().get(p)) layers.getFirst().structure().set(p, s);
            });
        }
    }

    private final BuildState state = new BuildState();
    private final List<PluginCommand> commands = BuilderCommands.all(TestBlocks.materials(), state);
    private final BuildRunner runner = new BuildRunner(commands, TestBlocks::resolve, state);
    private final FakeHost host = new FakeHost();
    private final Assistant assistant = new Assistant(runner, commands, host);

    private Assistant.Turn turn(ScriptedModel model, String prompt) throws IOException {
        return assistant.run(prompt, null, null, Scope.Choice.AUTO, "auto", 100_000, model, new Assistant.Listener() { }, new Cancel());
    }

    @Test
    void aNewBuildOnAnEmptySceneGoesIntoItsOwnLayer() throws IOException {
        ScriptedModel model = new ScriptedModel("A small tower.\n```commands\n/style medieval\n/tower 2 4 cone @wall @roof at 0 0 0\n```");
        Assistant.Turn t = turn(model, "build a small tower");
        assertThat(t.scope().mode()).isEqualTo(Scope.Mode.NEW_BUILD);
        assertThat(t.report().ok()).isTrue();
        assertThat(t.rounds()).isEqualTo(1);
        assertThat(host.layers).hasSize(1);
        assertThat(host.layers.getFirst().name()).isEqualTo("AI: build a small tower");
        assertThat(host.layers.getFirst().structure().get(2, 0, 0).name()).isEqualTo("minecraft:stone_bricks");
        assertThat(host.undoSteps).isEqualTo(1);
        assertThat(t.prose()).isEqualTo("A small tower.");
        // The system prompt carries the scene, the commands and the styles.
        String system = model.sent.getFirst().getFirst().text();
        assertThat(system).contains("SCOPE: a new build").contains("/tower <radius>").contains("/walls <pattern>").contains("medieval (")
                .contains("70%stone_bricks").doesNotContain("%%");
    }

    @Test
    void errorsGoBackToTheModelUntilFixed() throws IOException {
        ScriptedModel model = new ScriptedModel(
                "```\n/fill 0 0 0 4 0 4 stone\n/roof gabel oak_planks\n```",
                "Fixed the roof type.\n```\n/fill 0 0 0 4 0 4 stone\n/pos1 0 0 0\n/pos2 4 0 4\n/roof gable oak_planks\n```");
        List<Integer> tries = new ArrayList<>();
        Assistant.Turn t = assistant.run("a floor with a roof", null, null, Scope.Choice.AUTO, "auto", 100_000, model, new Assistant.Listener() {
            public void tried(int round, BuildRunner.Report report) {
                tries.add(report.errors().size());
            }
        }, new Cancel());
        assertThat(tries).containsExactly(1, 0);
        assertThat(t.rounds()).isEqualTo(2);
        assertThat(t.report().ok()).isTrue();
        List<ChatMessage> second = model.sent.get(1);
        assertThat(second.get(second.size() - 2).role()).isEqualTo(ChatMessage.Role.ASSISTANT);
        assertThat(second.getLast().text()).contains("line 2").contains("gabel").contains("try 1 of 3");
        assertThat(host.undoSteps).isEqualTo(1);
    }

    @Test
    void afterThreeRepairRoundsWhatWorkedIsApplied() throws IOException {
        String bad = "```\n/setblock 0 0 0 stone\n/setblock 1 0 0 unobtainium\n```";
        ScriptedModel model = new ScriptedModel(bad, bad, bad, bad, "never asked");
        Assistant.Turn t = turn(model, "two blocks");
        assertThat(model.sent).hasSize(4);
        assertThat(t.rounds()).isEqualTo(4);
        assertThat(t.report().errors()).hasSize(1);
        assertThat(host.layers.getFirst().structure().get(0, 0, 0).name()).isEqualTo("minecraft:stone");
    }

    @Test
    void editsWorkOnTheSelectionAndCanBeUndone() throws IOException {
        Structure s = new Structure();
        for (int x = 0; x < 10; x++) for (int z = 0; z < 10; z++) s.set(x, 0, z, BlockState.parse("minecraft:stone_bricks"));
        host.layers.add(new Layer("Castle", s));
        host.selection = Optional.of(Box.of(new BlockPos(0, 0, 0), new BlockPos(4, 0, 9)));
        ScriptedModel model = new ScriptedModel("Mossy.\n```\n/replace stone_bricks mossy_stone_bricks\n```");
        Assistant.Turn t = turn(model, "make it mossy");
        assertThat(t.scope().mode()).isEqualTo(Scope.Mode.SELECTION);
        assertThat(t.applied().changed()).isEqualTo(50);
        assertThat(s.get(2, 0, 2).name()).isEqualTo("minecraft:mossy_stone_bricks");
        assertThat(s.get(7, 0, 2).name()).isEqualTo("minecraft:stone_bricks");   // outside the selection
        assertThat(model.sent.getFirst().getFirst().text()).contains("SCOPE: the user's selection").contains("\"Castle\"");
        assistant.undo(t.applied());
        assertThat(s.get(2, 0, 2).name()).isEqualTo("minecraft:stone_bricks");
    }

    @Test
    void aQuestionChangesNothingAndHistoryIsKept() throws IOException {
        ScriptedModel model = new ScriptedModel("Castles have walls, towers and a gatehouse.", "```\n/setblock 0 0 0 stone\n```");
        Assistant.Turn t = turn(model, "what does a castle have?");
        assertThat(t.applied()).isNull();
        assertThat(t.commands()).isEmpty();
        assertThat(t.report()).isNull();
        turn(model, "ok, one block");
        List<ChatMessage> second = model.sent.get(1);
        assertThat(second).extracting(ChatMessage::text).contains("what does a castle have?", "Castles have walls, towers and a gatehouse.");
    }

    @Test
    void picturesGoToVisionModelsAndHintsToAll() throws IOException {
        BufferedImage img = new BufferedImage(20, 20, BufferedImage.TYPE_INT_RGB);
        Attachment a = Attachment.of("house.png", img);
        ScriptedModel vision = new ScriptedModel("Nothing to build.");
        assistant.run("", a, "Main colours: black", Scope.Choice.AUTO, "auto", 1000, vision, new Assistant.Listener() { }, new Cancel());
        ChatMessage user = vision.sent.getFirst().getLast();
        assertThat(user.images()).hasSize(1);
        assertThat(user.text()).isEqualTo("Build what the picture shows.");
        assertThat(vision.sent.getFirst().getFirst().text()).contains("REFERENCE PICTURE").contains("Main colours: black");

        ScriptedModel blind = new ScriptedModel("ok");
        blind.vision = false;
        assistant.run("a house like this", a, "Main colours: black", Scope.Choice.AUTO, "auto", 1000, blind, new Assistant.Listener() { }, new Cancel());
        assertThat(blind.sent.getFirst().getLast().images()).isEmpty();
        assertThat(blind.sent.getFirst().getFirst().text()).contains("can't see pictures");
    }

    @Test
    void cancelStopsBeforeAnythingChanges() {
        ScriptedModel model = new ScriptedModel("```\n/setblock 0 0 0 stone\n```");
        Cancel cancel = new Cancel();
        cancel.cancel();
        assertThatThrownBy(() -> assistant.run("x", null, null, Scope.Choice.AUTO, "auto", 1000, model, new Assistant.Listener() { }, cancel))
                .isInstanceOf(Cancel.CancelledException.class);
        assertThat(host.layers).isEmpty();
    }
}
