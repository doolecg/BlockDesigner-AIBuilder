package io.blockdesigner.aibuilder;

import io.blockdesigner.core.blocks.BlockFamily;
import io.blockdesigner.core.model.BlockPos;
import io.blockdesigner.core.model.BlockState;
import io.blockdesigner.core.worldedit.WorldEdit;
import io.blockdesigner.plugin.BlockCatalog;
import io.blockdesigner.plugin.PluginCommand;
import io.blockdesigner.plugin.PluginContext;
import io.blockdesigner.plugin.PluginPanel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Enables the plugin against a stand-in context, the way BlockDesigner does: it registers its commands into BlockEdit's
 * registry (as the app's PluginManager does) and its two pages, and a registered command runs from a BlockEdit line.
 */
class PluginEnableTest {
    @TempDir
    Path dir;

    static final class Catalog implements BlockCatalog {
        public BlockState resolve(String text) {
            BlockState s = TestBlocks.resolve(text);
            if (s == null) throw new IllegalArgumentException("Unknown block: " + text);
            return s;
        }

        public boolean exists(String id) {
            return TestBlocks.exists(id);
        }

        public Optional<BlockFamily> family(BlockState b) {
            return Optional.empty();
        }

        public Optional<BlockState> sameShape(BlockState b, BlockFamily f) {
            return Optional.empty();
        }

        public Optional<BlockState> variant(BlockState b, String m) {
            return Optional.empty();
        }

        public Optional<BlockState> withoutVariant(BlockState b, String m) {
            return Optional.empty();
        }

        public BlockState withId(BlockState b, String id) {
            return b.withName(BlockState.normalizeId(id));
        }

        public int averageColor(BlockState b) {
            return 0xFF808080;
        }

        public String displayName(BlockState b) {
            return b.path();
        }
    }

    @Test
    void enableRegistersCommandsAndPagesAndDisableCleansUp() throws Exception {
        List<PluginCommand> commands = new ArrayList<>();
        List<PluginPanel> panels = new ArrayList<>();
        List<String> log = new ArrayList<>();
        List<io.blockdesigner.plugin.Options> settings = new ArrayList<>();
        Catalog catalog = new Catalog();
        PluginContext ctx = (PluginContext) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{PluginContext.class}, (p, m, args) ->
                switch (m.getName()) {
                    case "dataFolder" -> dir;
                    case "blocks" -> catalog;
                    case "log" -> log.add((String) args[0]);
                    case "registerCommand" -> {
                        PluginCommand c = (PluginCommand) args[0];
                        commands.add(c);
                        // What PluginManager does: into BlockEdit's registry.
                        WorldEdit.register(new WorldEdit.Command(c.name(), c.usage(), c.description()), (a, f, wc, region) -> {
                            try {
                                return WorldEdit.Result.ok(c.handler().run(new PluginCommand.Context(a, f, wc.world(), Optional.ofNullable(region),
                                        Optional.ofNullable(wc.aim()), Optional.ofNullable(wc.hand()), catalog)), 0);
                            } catch (IllegalArgumentException e) {
                                return WorldEdit.Result.error(e.getMessage());
                            } catch (Exception e) {
                                throw new IllegalStateException(e);
                            }
                        });
                        yield null;
                    }
                    case "registerPanel" -> panels.add((PluginPanel) args[0]);
                    // API 6: settings on the Settings page, page status dots; no toolkit here, so no ui().
                    case "registerSettings" -> settings.add((io.blockdesigner.plugin.Options) args[0]);
                    case "setPanelStatus", "updateSettings", "showPanel", "openSettings" -> null;
                    case "ui" -> null;
                    case "toString" -> "fake context";
                    case "hashCode" -> 0;
                    case "equals" -> false;
                    default -> null;
                });
        AiBuilderPlugin plugin = new AiBuilderPlugin();
        try {
            plugin.enable(ctx);
            assertThat(commands).extracting(PluginCommand::name)
                    .containsExactly("setblock", "fill", "tower", "roof", "battlements", "windows", "door", "gatehouse", "floors");
            assertThat(panels).extracting(PluginPanel::id).containsExactly("assistant", "models");
            assertThat(panels).extracting(PluginPanel::title).containsExactly("Assistant", "Models");
            assertThat(settings).containsExactly(AiOptions.OPTIONS);   // its page in the Settings window

            // In the command bar: a BlockEdit session running the plugin's /tower.
            var world = io.blockdesigner.aibuilder.build.OverlayWorld.empty();
            WorldEdit we = new WorldEdit();
            WorldEdit.Context wc = new WorldEdit.Context(world, null, new BlockPos(0, 0, 0), null, TestBlocks::resolve);
            WorldEdit.Result r = we.run("/tower 2 3 flat stone_bricks at 0 0 0", wc);
            assertThat(r.ok()).as(r.message()).isTrue();
            assertThat(world.get(new BlockPos(2, 0, 0)).name()).isEqualTo("minecraft:stone_bricks");
            assertThat(WorldEdit.commands()).extracting(WorldEdit.Command::name).contains("tower", "fill");
            assertThat(dir.resolve("settings.json")).doesNotExist();   // nothing written until something changes
        } finally {
            plugin.disable();
            commands.forEach(c -> WorldEdit.unregister(c.name()));
        }
    }
}
