package io.blockdesigner.aibuilder.agent;

import io.blockdesigner.core.model.BlockPos;
import io.blockdesigner.core.model.BlockState;
import io.blockdesigner.core.model.Box;
import io.blockdesigner.core.model.Layer;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The scene described for the model: the scope, the visible layers with their bounds, block counts and main blocks,
 * the palette inside the scope, and a coarse top-down height map, so "add a tower on the east side" has something
 * to go on.
 */
public final class SceneSummary {
    static final int MAP_CELLS = 32;
    private static final String HEIGHTS = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";
    /** Layers bigger than this are summarised from their counts only, without walking every block. */
    static final long WALK_LIMIT = 3_000_000;

    private SceneSummary() {
    }

    /** The visible bounds of the scene, if it has any blocks. */
    public static Optional<Box> bounds(List<Layer> layers) {
        Box b = null;
        for (Layer l : layers) {
            if (!l.visible() || l.ghost()) continue;
            Optional<Box> lb = l.worldBounds();
            if (lb.isPresent()) b = b == null ? lb.get() : b.union(lb.get());
        }
        return Optional.ofNullable(b);
    }

    public static String describe(List<Layer> layers, Scope scope) {
        StringBuilder sb = new StringBuilder();
        sb.append("SCOPE: ");
        switch (scope.mode()) {
            case SELECTION -> sb.append("the user's selection, box ").append(box(scope.box()))
                    .append(". Change only blocks inside it unless the request says otherwise.");
            case EVERYTHING -> sb.append("everything visible, bounds ").append(box(scope.box())).append('.');
            case NEW_BUILD -> sb.append("a new build in empty space. Put its lowest north-west corner at ")
                    .append(pos(scope.anchor())).append(" (ground level is y=").append(scope.anchor().y()).append(").");
        }
        sb.append('\n');

        sb.append("LAYERS (bottom to top):\n");
        int shown = 0;
        for (Layer l : layers) {
            if (!l.visible() || l.ghost()) continue;
            Optional<Box> b = l.worldBounds();
            sb.append("- \"").append(l.name()).append('"');
            if (l.locked()) sb.append(" (locked)");
            if (b.isEmpty()) {
                sb.append(": empty\n");
            } else {
                long n = l.structure().blockCount();
                sb.append(": ").append(box(b.get())).append(", ").append(String.format(Locale.ROOT, "%,d", n)).append(" blocks; ")
                        .append(top(names(l.structure().stateCounts()), n, 5)).append('\n');
            }
            shown++;
        }
        if (shown == 0) sb.append("- (none: the scene is empty)\n");

        Box area = scope.box();
        if (area != null) {
            Map<String, Long> palette = new HashMap<>();
            int[][] height = heightMap(layers, area, palette);
            long total = palette.values().stream().mapToLong(Long::longValue).sum();
            if (total > 0) {
                sb.append("BLOCKS IN SCOPE: ").append(top(palette, total, 10)).append('\n');
                sb.append(mapText(height, area));
            } else if (height == null) {
                sb.append("(too many blocks to map)\n");
            }
        }
        return sb.toString();
    }

    private static Map<String, Long> names(Map<BlockState, Long> counts) {
        Map<String, Long> out = new HashMap<>();
        counts.forEach((s, n) -> {
            if (!s.isAir()) out.merge(s.path(), n, Long::sum);
        });
        return out;
    }

    static String top(Map<String, Long> counts, long total, int k) {
        StringBuilder sb = new StringBuilder();
        List<Map.Entry<String, Long>> sorted = counts.entrySet().stream().sorted((a, b) -> Long.compare(b.getValue(), a.getValue())).toList();
        for (int i = 0; i < Math.min(k, sorted.size()); i++) {
            if (i > 0) sb.append(", ");
            sb.append(sorted.get(i).getKey()).append(' ').append(Math.max(1, Math.round(100.0 * sorted.get(i).getValue() / Math.max(1, total)))).append('%');
        }
        if (sorted.size() > k) sb.append(", …");
        return sb.toString();
    }

    /**
     * The highest block of each column in {@code area}, relative to its bottom (-1 for none), sampled down to at most
     * {@link #MAP_CELLS} cells a side; fills {@code palette} with the block counts inside the area. Null when too big.
     */
    static int[][] heightMap(List<Layer> layers, Box area, Map<String, Long> palette) {
        int step = Math.max(1, (int) Math.ceil(Math.max(area.sizeX(), area.sizeZ()) / (double) MAP_CELLS));
        int w = (area.sizeX() + step - 1) / step, d = (area.sizeZ() + step - 1) / step;
        int[][] h = new int[d][w];
        for (int[] row : h) java.util.Arrays.fill(row, -1);
        for (Layer l : layers) {
            if (!l.visible() || l.ghost()) continue;
            if (l.worldBounds().isEmpty()) continue;
            if (l.structure().blockCount() > WALK_LIMIT) return null;
            l.structure().forEachBlock((x, y, z, s) -> {
                if (s.isAir()) return;
                BlockPos p = l.toWorld(x, y, z);
                if (!area.contains(p.x(), p.y(), p.z())) return;
                palette.merge(s.path(), 1L, Long::sum);
                int cx = (p.x() - area.minX()) / step, cz = (p.z() - area.minZ()) / step;
                h[cz][cx] = Math.max(h[cz][cx], p.y() - area.minY());
            });
        }
        return h;
    }

    static String mapText(int[][] h, Box area) {
        int step = Math.max(1, (int) Math.ceil(Math.max(area.sizeX(), area.sizeZ()) / (double) MAP_CELLS));
        StringBuilder sb = new StringBuilder("HEIGHT MAP (top view; north at the top, east to the right; ");
        sb.append(step == 1 ? "one character per block" : "each character is " + step + "×" + step + " blocks");
        sb.append("; '.' is empty, 0-9 then a-z give the top block's height above y=").append(area.minY())
                .append("; the first row is z=").append(area.minZ()).append(", the first column x=").append(area.minX()).append("):\n");
        for (int[] row : h) {
            for (int v : row) sb.append(v < 0 ? '.' : HEIGHTS.charAt(Math.min(v, HEIGHTS.length() - 1)));
            sb.append('\n');
        }
        return sb.toString();
    }

    static String box(Box b) {
        return "from " + b.minX() + " " + b.minY() + " " + b.minZ() + " to " + b.maxX() + " " + b.maxY() + " " + b.maxZ()
                + " (" + Scope.size(b) + ", x×y×z)";
    }

    static String pos(BlockPos p) {
        return p.x() + " " + p.y() + " " + p.z();
    }

    /** Block counts per id (no properties) for the whole visible scene, most first; for tests and the panel. */
    public static Map<String, Long> palette(List<Layer> layers) {
        Map<String, Long> out = new LinkedHashMap<>();
        for (Layer l : layers) if (l.visible() && !l.ghost()) names(l.structure().stateCounts()).forEach((k, v) -> out.merge(k, v, Long::sum));
        return out;
    }
}
