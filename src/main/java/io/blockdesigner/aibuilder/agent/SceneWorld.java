package io.blockdesigner.aibuilder.agent;

import io.blockdesigner.core.model.BlockPos;
import io.blockdesigner.core.model.BlockState;
import io.blockdesigner.core.model.Layer;
import io.blockdesigner.core.worldedit.WorldEdit;

import java.util.List;

/** The visible layers merged in world coordinates, top layer first, read only. Tries run against an overlay on it. */
public final class SceneWorld implements WorldEdit.World {
    private final List<Layer> layers;

    /** @param layers bottom first, as the scene lists them */
    public SceneWorld(List<Layer> layers) {
        this.layers = List.copyOf(layers);
    }

    @Override
    public BlockState get(BlockPos p) {
        for (int i = layers.size() - 1; i >= 0; i--) {
            Layer l = layers.get(i);
            if (!l.visible() || l.ghost()) continue;
            BlockState s = l.structure().get(l.toLocal(p));
            if (s != null && !s.isAir()) return s;
        }
        return BlockState.AIR;
    }

    @Override
    public void set(BlockPos p, BlockState s) {
        throw new UnsupportedOperationException("The scene is changed through the editor, not here");
    }
}
