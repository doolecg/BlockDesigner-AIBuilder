package io.blockdesigner.aibuilder.agent;

import io.blockdesigner.aibuilder.build.BuilderCommands;
import io.blockdesigner.aibuilder.build.Styles;
import io.blockdesigner.core.worldedit.WorldEdit;
import io.blockdesigner.plugin.PluginCommand;

import java.util.List;
import java.util.Set;

/**
 * The instructions sent with every turn: the output format (a short plan and one fenced block of commands), the
 * coordinate conventions, the command reference, the styles, and the scene summary.
 */
public final class SystemPrompt {
    /** The BlockEdit commands worth offering to a model (not help, undo or the region-info ones). */
    static final Set<String> USEFUL = Set.of("pos1", "pos2", "set", "replace", "walls", "faces", "overlay", "move", "stack", "copy",
            "paste", "rotate", "flip", "expand", "contract", "shift", "outset", "inset", "line", "sphere", "hsphere", "cyl", "hcyl",
            "pyramid", "hpyramid", "hollow", "fixshapes", "naturalize", "smooth", "center");

    private SystemPrompt() {
    }

    static String blockEditReference() {
        StringBuilder sb = new StringBuilder();
        for (WorldEdit.Command c : WorldEdit.COMMANDS) {
            if (USEFUL.contains(c.name())) sb.append(c.usage()).append("  : ").append(c.description()).append('\n');
        }
        return sb.toString();
    }

    /**
     * @param style the style picked on the Assistant page, or "auto" to let the model choose
     */
    public static String build(List<PluginCommand> own, String style, String sceneSummary, String imageNotes) {
        String styleLine = style == null || style.equals("auto")
                ? "No style is set: pick the one that fits the request with a `/style <name>` line at the top of the block (medieval if unsure)."
                : "The user picked the " + style + " style: @wall, @roof and the other placeholders use it.";
        return """
                You are AI Builder, the building assistant inside BlockDesigner, an editor for Minecraft builds. You build and \
                change structures by writing commands, which BlockDesigner runs for you. There is no game running: only blocks.

                HOW TO ANSWER
                1. One to three short sentences: what you will build or change, with rough sizes.
                2. Then exactly one fenced code block with the commands, one per line:
                ```commands
                /first command
                /second command
                ```
                - Only commands in the block: no explanations, no numbering. Lines starting with # are comments.
                - If the user only asks a question, answer it in words and leave out the block.
                - If a command fails you will be told which line and why; then reply with the whole corrected block.

                COORDINATES
                - x grows to the east, y is up, z grows to the south (north is -z). Use whole numbers, absolute coordinates.
                - A block at y sits on top of the block at y-1. Put floors at the build's lowest y and walls on top of them.
                - Minecraft facings: stairs rise towards their "facing" side. Roofs: use /roof, it gets the stairs right.

                BLOCKS
                - Minecraft ids without "minecraft:", e.g. stone_bricks, oak_planks, glass_pane, spruce_stairs[facing=east,half=bottom].
                - Mixes: 70%%stone_bricks,20%%mossy_stone_bricks,10%%cracked_stone_bricks (no spaces). air removes blocks.
                - Style placeholders @wall @trim @roof @floor @glass @accent @door stand for the style's blocks.

                COMMANDS
                Vanilla-style and builder commands (they take absolute coordinates, or work on the region pos1..pos2):
                %s
                BlockEdit commands (they work on the region between /pos1 and /pos2, or around /pos1):
                %s
                /style <name>  : switch the style for the following lines

                GOOD HABITS
                - Set the region before region commands: /pos1 x y z then /pos2 x y z. A region's bottom layer is its floor;
                  /roof and /battlements go on top of it, /windows and /door cut into its walls.
                - A house: /fill the floor, /pos1 + /pos2 the whole box, /walls @wall, /windows, /door, then /roof gable.
                - A tower: /tower <radius> <height> cone @wall @roof at x y z (x y z is the centre of its base).
                - "Replace X with Y": /pos1 and /pos2 around the scope, then /replace X Y (Y may be a mix).
                - Hollow shells, not solid lumps: /walls, /faces, /hcyl, /hsphere, or /fill … hollow.
                - Keep builds a sensible size (up to about 40 blocks a side) unless asked for more. Don't touch blocks outside the scope.

                STYLES
                %s
                %s

                THE SCENE
                %s
                %s""".formatted(BuilderCommands.reference(own), blockEditReference(), Styles.describe(), styleLine, sceneSummary,
                imageNotes == null || imageNotes.isBlank() ? "" : "\nREFERENCE PICTURE\n" + imageNotes);
    }

    /** The follow-up after a try whose commands failed. */
    public static String repair(String errors, int round, int rounds) {
        return "Some commands failed (try " + round + " of " + rounds + "):\n" + errors
                + "\nFix them and reply with the complete corrected command block (every command, including the ones that worked), "
                + "with a one-line note of what you changed.";
    }
}
