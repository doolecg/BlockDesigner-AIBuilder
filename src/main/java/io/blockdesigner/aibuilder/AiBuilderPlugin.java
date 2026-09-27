package io.blockdesigner.aibuilder;

import io.blockdesigner.aibuilder.build.BuildState;
import io.blockdesigner.aibuilder.build.BuilderCommands;
import io.blockdesigner.aibuilder.build.Materials;
import io.blockdesigner.plugin.BlockDesignerPlugin;
import io.blockdesigner.plugin.PluginCommand;
import io.blockdesigner.plugin.PluginContext;

import java.util.List;

/**
 * AI Builder: builds and edits structures from a chat, with a local model by default. Registers the builder
 * commands ({@code /setblock}, {@code /fill}, {@code /tower}…) into BlockEdit, and the Assistant and Models pages.
 */
public final class AiBuilderPlugin implements BlockDesignerPlugin {
    private AiBuilder app;

    @Override
    public void enable(PluginContext ctx) {
        BuildState state = new BuildState();
        Materials materials = new Materials(ctx.blocks()::exists);
        List<PluginCommand> commands = BuilderCommands.all(materials, state);
        for (PluginCommand c : commands) {
            try {
                ctx.registerCommand(c);
            } catch (IllegalArgumentException e) {
                // Another plugin (or a newer BlockDesigner) already has this name; the assistant still runs ours.
                ctx.log("/" + c.name() + " not added to the command bar: " + e.getMessage());
            }
        }
        app = new AiBuilder(ctx, state, materials, commands);
        app.start();
    }

    @Override
    public void disable() {
        if (app != null) app.stop();
        app = null;
    }
}
