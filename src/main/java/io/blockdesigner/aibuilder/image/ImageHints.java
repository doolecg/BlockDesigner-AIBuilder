package io.blockdesigner.aibuilder.image;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.function.ToIntFunction;

/**
 * A picture's main colours, each matched to the nearest-looking building block. The hints go into the prompt for
 * every model, so even one that can't see pictures builds in the right colours.
 */
public final class ImageHints {
    /** A dominant colour, its share of the picture, and the block that looks closest. */
    public record Hint(int rgb, double share, String block) {
    }

    /**
     * Building blocks to choose from, with approximate average colours of their vanilla textures. In the app the
     * loaded game's own colours ({@code BlockCatalog.averageColor}) replace these.
     */
    public static final Map<String, Integer> BUILDING_BLOCKS = new LinkedHashMap<>();

    static {
        Object[][] table = {
                {"stone_bricks", 0x7A7A7A}, {"mossy_stone_bricks", 0x737A62}, {"cobblestone", 0x807F80}, {"stone", 0x7E7E7E},
                {"andesite", 0x888889}, {"polished_andesite", 0x848786}, {"diorite", 0xBCBCBC}, {"granite", 0x956755},
                {"deepslate_bricks", 0x464648}, {"blackstone", 0x2A2429}, {"tuff_bricks", 0x62675F}, {"calcite", 0xDFE0DC},
                {"bricks", 0x976253}, {"mud_bricks", 0x89694F}, {"sandstone", 0xD8CB9B}, {"smooth_sandstone", 0xE0D6AA},
                {"red_sandstone", 0xBA6621}, {"terracotta", 0x985E44}, {"white_terracotta", 0xD1B2A1}, {"orange_terracotta", 0xA25426},
                {"light_gray_terracotta", 0x876B62}, {"brown_terracotta", 0x4D3324}, {"oak_planks", 0xA2834F}, {"spruce_planks", 0x735531},
                {"birch_planks", 0xC0AF79}, {"dark_oak_planks", 0x432B14}, {"jungle_planks", 0xA07351}, {"acacia_planks", 0xA85A32},
                {"cherry_planks", 0xE3B3AD}, {"mangrove_planks", 0x773631}, {"oak_log", 0x6D5532}, {"spruce_log", 0x3B2610},
                {"white_concrete", 0xCFD5D6}, {"light_gray_concrete", 0x7D7D73}, {"gray_concrete", 0x373A3E}, {"black_concrete", 0x080A0F},
                {"red_concrete", 0x8E2121}, {"blue_concrete", 0x2D2F8F}, {"green_concrete", 0x495B24}, {"yellow_concrete", 0xF1AF15},
                {"quartz_block", 0xECE6DF}, {"prismarine_bricks", 0x63AB9E}, {"dark_prismarine", 0x335B4B}, {"nether_bricks", 0x2C1519},
                {"purpur_block", 0xA97DA9}, {"copper_block", 0xC06C50}, {"oxidized_copper", 0x52A385}, {"glass", 0xB0D6DB},
                {"snow_block", 0xF9FEFE}, {"grass_block", 0x7CBD6B}, {"dirt", 0x866043}, {"moss_block", 0x596E2D}};
        for (Object[] row : table) BUILDING_BLOCKS.put((String) row[0], (Integer) row[1]);
    }

    private ImageHints() {
    }

    /**
     * Up to {@code k} dominant colours of the picture (transparent pixels left out), most common first, each with
     * its nearest block; colours that land on the same block are merged.
     *
     * @param colour a block's colour as 0xRRGGBB (the app's catalog, or the built-in table)
     */
    public static List<Hint> dominant(BufferedImage img, int k, Iterable<String> blocks, ToIntFunction<String> colour) {
        float[][] px = sample(img, 96);
        if (px.length == 0) return List.of();
        float[][] centres = kmeans(px, Math.min(k, px.length), 12);
        int[] counts = new int[centres.length];
        for (float[] p : px) counts[nearest(centres, p)]++;
        // Candidate blocks in the same colour space.
        List<String> ids = new ArrayList<>();
        List<float[]> labs = new ArrayList<>();
        for (String b : blocks) {
            ids.add(b);
            labs.add(oklab(colour.applyAsInt(b)));
        }
        Map<String, double[]> merged = new LinkedHashMap<>();   // block -> {share, r, g, b weighted}
        Integer[] order = new Integer[centres.length];
        for (int i = 0; i < order.length; i++) order[i] = i;
        java.util.Arrays.sort(order, (a, b) -> Integer.compare(counts[b], counts[a]));
        for (int i : order) {
            if (counts[i] == 0) continue;
            double share = counts[i] / (double) px.length;
            int best = nearest(labs.toArray(new float[0][]), centres[i]);
            int rgb = fromOklab(centres[i]);
            double[] m = merged.computeIfAbsent(ids.get(best), x -> new double[4]);
            m[0] += share;
            m[1] += ((rgb >> 16) & 255) * share;
            m[2] += ((rgb >> 8) & 255) * share;
            m[3] += (rgb & 255) * share;
        }
        List<Hint> out = new ArrayList<>();
        merged.forEach((block, m) -> {
            int rgb = ((int) Math.round(m[1] / m[0]) << 16) | ((int) Math.round(m[2] / m[0]) << 8) | (int) Math.round(m[3] / m[0]);
            out.add(new Hint(rgb, m[0], block));
        });
        out.sort((a, b) -> Double.compare(b.share(), a.share()));
        return out;
    }

    public static String describe(List<Hint> hints) {
        StringBuilder sb = new StringBuilder("Main colours of the picture and the closest building blocks (use them for the matching parts):\n");
        for (Hint h : hints) {
            if (h.share() < 0.02) continue;
            sb.append(String.format(Locale.ROOT, "- #%06x, %d%% of the picture -> %s%n", h.rgb(), Math.round(h.share() * 100), h.block()));
        }
        return sb.toString();
    }

    /** OKLab colours of the picture's opaque pixels, scaled down to at most {@code side} a side. */
    static float[][] sample(BufferedImage img, int side) {
        BufferedImage s = img.getWidth() > side || img.getHeight() > side ? Attachment.scale(img, side) : img;
        List<float[]> out = new ArrayList<>();
        for (int y = 0; y < s.getHeight(); y++) {
            for (int x = 0; x < s.getWidth(); x++) {
                int argb = s.getRGB(x, y);
                if ((argb >>> 24) < 128) continue;
                out.add(oklab(argb & 0xFFFFFF));
            }
        }
        return out.toArray(new float[0][]);
    }

    static float[][] kmeans(float[][] px, int k, int iterations) {
        Random r = new Random(1234);
        float[][] c = new float[k][];
        c[0] = px[r.nextInt(px.length)].clone();
        double[] d = new double[px.length];
        for (int i = 1; i < k; i++) {
            double sum = 0;
            for (int p = 0; p < px.length; p++) {
                d[p] = dist(px[p], c[nearest(java.util.Arrays.copyOf(c, i), px[p])]);
                sum += d[p];
            }
            double t = r.nextDouble() * sum;
            int pick = 0;
            for (int p = 0; p < px.length; p++) {
                t -= d[p];
                if (t <= 0) {
                    pick = p;
                    break;
                }
            }
            c[i] = px[pick].clone();
        }
        for (int it = 0; it < iterations; it++) {
            double[][] acc = new double[k][3];
            int[] n = new int[k];
            for (float[] p : px) {
                int j = nearest(c, p);
                acc[j][0] += p[0];
                acc[j][1] += p[1];
                acc[j][2] += p[2];
                n[j]++;
            }
            for (int j = 0; j < k; j++) {
                if (n[j] > 0) c[j] = new float[]{(float) (acc[j][0] / n[j]), (float) (acc[j][1] / n[j]), (float) (acc[j][2] / n[j])};
            }
        }
        return c;
    }

    static int nearest(float[][] centres, float[] p) {
        int best = 0;
        double bd = Double.MAX_VALUE;
        for (int i = 0; i < centres.length; i++) {
            double dd = dist(centres[i], p);
            if (dd < bd) {
                bd = dd;
                best = i;
            }
        }
        return best;
    }

    static double dist(float[] a, float[] b) {
        double x = a[0] - b[0], y = a[1] - b[1], z = a[2] - b[2];
        return x * x + y * y + z * z;
    }

    private static double lin(int c) {
        double v = c / 255.0;
        return v <= 0.04045 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4);
    }

    private static int gamma(double v) {
        double s = v <= 0.0031308 ? 12.92 * v : 1.055 * Math.pow(v, 1 / 2.4) - 0.055;
        return (int) Math.round(Math.clamp(s, 0, 1) * 255);
    }

    static float[] oklab(int rgb) {
        double r = lin((rgb >> 16) & 255), g = lin((rgb >> 8) & 255), b = lin(rgb & 255);
        double l = Math.cbrt(0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b);
        double m = Math.cbrt(0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b);
        double s = Math.cbrt(0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b);
        return new float[]{(float) (0.2104542553 * l + 0.7936177850 * m - 0.0040720468 * s),
                (float) (1.9779984951 * l - 2.4285922050 * m + 0.4505937099 * s),
                (float) (0.0259040371 * l + 0.7827717662 * m - 0.8086757660 * s)};
    }

    static int fromOklab(float[] lab) {
        double l = lab[0] + 0.3963377774 * lab[1] + 0.2158037573 * lab[2];
        double m = lab[0] - 0.1055613458 * lab[1] - 0.0638541728 * lab[2];
        double s = lab[0] - 0.0894841775 * lab[1] - 1.2914855480 * lab[2];
        l = l * l * l;
        m = m * m * m;
        s = s * s * s;
        int r = gamma(4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s);
        int g = gamma(-1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s);
        int b = gamma(-0.0041960863 * l - 0.7034186147 * m + 1.7076147010 * s);
        return (r << 16) | (g << 8) | b;
    }
}
