package io.blockdesigner.aibuilder.agent;

import io.blockdesigner.core.model.BlockPos;
import io.blockdesigner.core.model.Box;

import java.util.Optional;

/**
 * What a turn works on: the selection, everything visible, or a new build in empty space (its own layer).
 *
 * @param box    the selection or the visible bounds; null for a new build
 * @param anchor where a new build starts (its lowest north-west corner), or the box's corner
 */
public record Scope(Mode mode, Box box, BlockPos anchor) {
    public enum Mode { SELECTION, EVERYTHING, NEW_BUILD }

    /** What the user picked on the Assistant page. */
    public enum Choice {
        AUTO("Auto"), SELECTION("Selection"), EVERYTHING("Everything visible"), NEW_BUILD("New build");

        public final String label;

        Choice(String label) {
            this.label = label;
        }
    }

    public boolean newLayer() {
        return mode == Mode.NEW_BUILD;
    }

    /**
     * Auto: the selection when there is one; a new build when the scene is empty; otherwise everything visible. A new
     * build next to existing blocks starts five blocks east of them.
     */
    public static Scope choose(Choice choice, Optional<Box> selection, Optional<Box> sceneBounds) {
        Choice c = choice;
        if (c == Choice.AUTO) c = selection.isPresent() ? Choice.SELECTION : sceneBounds.isEmpty() ? Choice.NEW_BUILD : Choice.EVERYTHING;
        if (c == Choice.SELECTION && selection.isEmpty()) c = sceneBounds.isEmpty() ? Choice.NEW_BUILD : Choice.EVERYTHING;
        if (c == Choice.EVERYTHING && sceneBounds.isEmpty()) c = Choice.NEW_BUILD;
        return switch (c) {
            case SELECTION -> new Scope(Mode.SELECTION, selection.get(), selection.get().min());
            case EVERYTHING -> new Scope(Mode.EVERYTHING, sceneBounds.get(), sceneBounds.get().min());
            default -> new Scope(Mode.NEW_BUILD, null, sceneBounds.map(b -> new BlockPos(b.maxX() + 6, b.minY(), b.minZ()))
                    .orElse(BlockPos.ORIGIN));
        };
    }

    /** The scope line on the Assistant page. */
    public String describe() {
        return switch (mode) {
            case SELECTION -> "Editing: selection " + size(box);
            case EVERYTHING -> "Editing: everything visible " + size(box);
            case NEW_BUILD -> "New build at " + anchor.x() + " " + anchor.y() + " " + anchor.z() + " (its own layer)";
        };
    }

    static String size(Box b) {
        return b.sizeX() + "×" + b.sizeY() + "×" + b.sizeZ();
    }
}
