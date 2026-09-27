package io.blockdesigner.aibuilder.models;

import java.util.Locale;

/** Human-readable sizes and durations. */
public final class Sizes {
    private Sizes() {
    }

    public static String bytes(long n) {
        if (n < 1024) return n + " B";
        double v = n;
        String[] units = {"KB", "MB", "GB", "TB"};
        int u = -1;
        do {
            v /= 1024;
            u++;
        } while (v >= 1024 && u < units.length - 1);
        return String.format(Locale.ROOT, v >= 10 || u == 0 ? "%.0f %s" : "%.1f %s", v, units[u]);
    }

    /** Decimal gigabytes, as download pages quote them ("5.2 GB"). */
    public static String gb(long n) {
        return String.format(Locale.ROOT, "%.1f GB", n / 1e9);
    }

    public static String duration(long seconds) {
        if (seconds < 60) return seconds + " s";
        if (seconds < 3600) return (seconds + 30) / 60 + " min";
        return String.format(Locale.ROOT, "%d h %d min", seconds / 3600, (seconds % 3600) / 60);
    }
}
