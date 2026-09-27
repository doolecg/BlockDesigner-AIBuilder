package io.blockdesigner.aibuilder;

import io.blockdesigner.aibuilder.build.BuildRunner;
import io.blockdesigner.aibuilder.build.BuildState;
import io.blockdesigner.aibuilder.build.BuilderCommands;
import io.blockdesigner.aibuilder.build.Materials;
import io.blockdesigner.core.model.BlockState;

import java.util.HashSet;
import java.util.Set;

/** A small block registry for tests: the ids the styles and generators use, with their shapes. */
public final class TestBlocks {
    public static final Set<String> IDS = new HashSet<>();

    static {
        String[] full = {"stone", "andesite", "polished_andesite", "stone_bricks", "mossy_stone_bricks", "cobblestone", "mossy_cobblestone",
                "oak_planks", "spruce_planks", "birch_planks", "dark_oak_planks", "jungle_planks", "sandstone", "smooth_sandstone",
                "cut_sandstone", "prismarine_bricks", "smooth_quartz", "quartz_bricks", "bricks", "deepslate_bricks"};
        for (String f : full) IDS.add("minecraft:" + f);
        String[] withShapes = {"stone_brick", "mossy_stone_brick", "cobblestone", "mossy_cobblestone", "oak", "spruce", "birch", "dark_oak", "jungle",
                "sandstone", "smooth_sandstone", "prismarine_brick", "smooth_quartz", "polished_andesite", "andesite", "stone", "brick", "deepslate_brick"};
        for (String s : withShapes) {
            IDS.add("minecraft:" + s + "_stairs");
            IDS.add("minecraft:" + s + "_slab");
        }
        for (String w : new String[]{"stone_brick", "cobblestone", "brick", "sandstone"}) IDS.add("minecraft:" + w + "_wall");
        for (String w : new String[]{"oak", "spruce", "birch", "dark_oak", "jungle", "iron"}) IDS.add("minecraft:" + w + "_door");
        for (String w : new String[]{"oak", "spruce", "birch", "dark_oak", "jungle"}) {
            IDS.add("minecraft:" + w + "_log");
            IDS.add("minecraft:stripped_" + w + "_log");
            IDS.add("minecraft:" + w + "_fence");
        }
        String[] misc = {"air", "glass", "glass_pane", "iron_bars", "orange_terracotta", "purpur_pillar", "light_blue_stained_glass_pane",
                "amethyst_block", "white_concrete", "gray_concrete", "black_concrete", "red_wool", "white_wool", "iron_block", "gold_block",
                "grass_block", "dirt"};
        for (String m : misc) IDS.add("minecraft:" + m);
    }

    private TestBlocks() {
    }

    public static boolean exists(String id) {
        return IDS.contains(BlockState.normalizeId(id));
    }

    /** Parses block text when its id is known, else null (like the app's resolver). */
    public static BlockState resolve(String text) {
        String t = text.strip();
        String id = t.replaceAll("\\[.*$", "");
        if (!exists(id)) return null;
        BlockState s = BlockState.parse(t.contains(":") ? t : "minecraft:" + t);
        // Complete the default properties, as the app's catalog does.
        java.util.Map<String, String> defaults = new java.util.TreeMap<>();
        String path = s.path();
        if (path.endsWith("_stairs")) defaults.putAll(java.util.Map.of("facing", "north", "half", "bottom", "shape", "straight", "waterlogged", "false"));
        if (path.endsWith("_slab")) defaults.putAll(java.util.Map.of("type", "bottom", "waterlogged", "false"));
        if (path.endsWith("_pane") || path.endsWith("_fence") || path.equals("iron_bars")) {
            defaults.putAll(java.util.Map.of("north", "false", "east", "false", "south", "false", "west", "false", "waterlogged", "false"));
        }
        if (path.endsWith("_door")) defaults.putAll(java.util.Map.of("facing", "north", "half", "lower", "hinge", "left", "open", "false", "powered", "false"));
        defaults.putAll(s.properties());
        return BlockState.of(s.name(), defaults);
    }

    public static Materials materials() {
        return new Materials(TestBlocks::exists);
    }

    public static BuildRunner runner() {
        BuildState state = new BuildState();
        return new BuildRunner(BuilderCommands.all(materials(), state), TestBlocks::resolve, state);
    }
}
