package io.blockdesigner.aibuilder.models;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * A few files downloaded one after another on a background thread, reported as one progress: bytes, speed, time
 * left, and the state. Listeners are called on the download thread.
 */
public final class DownloadJob {
    public enum State { QUEUED, RUNNING, DONE, FAILED, CANCELLED }

    public record Item(URI url, Path target, long size) {
    }

    public record Progress(State state, long done, long total, double bytesPerSecond, String message) {
        /** 0..1, or -1 when the total isn't known. */
        public double fraction() {
            return total > 0 ? Math.min(1, done / (double) total) : -1;
        }

        /** Seconds left at the current speed, or -1. */
        public long secondsLeft() {
            return total > 0 && bytesPerSecond > 1 ? (long) Math.ceil((total - done) / bytesPerSecond) : -1;
        }

        /** "43% · 12 MB/s · 2 min left" (or the state's own words). */
        public String describe() {
            return switch (state) {
                case QUEUED -> "Waiting…";
                case DONE -> "Done";
                case CANCELLED -> "Cancelled";
                case FAILED -> message == null ? "Failed" : message;
                case RUNNING -> {
                    StringBuilder sb = new StringBuilder();
                    if (fraction() >= 0) sb.append((int) Math.floor(fraction() * 100)).append('%');
                    else sb.append(Sizes.bytes(done));
                    if (bytesPerSecond > 0) sb.append(" · ").append(Sizes.bytes((long) bytesPerSecond)).append("/s");
                    long left = secondsLeft();
                    if (left >= 0) sb.append(" · ").append(Sizes.duration(left)).append(" left");
                    yield sb.toString();
                }
            };
        }
    }

    /** Told when each file finishes, with its size (for custom models whose size wasn't known). */
    @FunctionalInterface
    public interface FileDone {
        void done(Item item, long size) throws IOException;
    }

    private final List<Item> items;
    private final Downloader downloader;
    private final Consumer<Progress> listener;
    private final FileDone fileDone;
    private final AtomicBoolean cancel = new AtomicBoolean();
    private volatile Progress progress;

    public DownloadJob(List<Item> items, Downloader downloader, Consumer<Progress> listener, FileDone fileDone) {
        this.items = List.copyOf(items);
        this.downloader = downloader;
        this.listener = listener;
        this.fileDone = fileDone;
        this.progress = new Progress(State.QUEUED, 0, total(), 0, null);
    }

    private long total() {
        long t = 0;
        for (Item i : items) {
            if (i.size() <= 0) return -1;
            t += i.size();
        }
        return t;
    }

    public Progress progress() {
        return progress;
    }

    public boolean finished() {
        State s = progress.state();
        return s == State.DONE || s == State.FAILED || s == State.CANCELLED;
    }

    public void cancel() {
        cancel.set(true);
    }

    public void start(ExecutorService executor) {
        executor.execute(this::run);
    }

    /** Runs the downloads on the calling thread. */
    public void run() {
        long total = total();
        long before = 0;
        try {
            for (Item item : items) {
                final long base = before;
                report(new Progress(State.RUNNING, base, total, progress.bytesPerSecond(), null));
                long size = downloader.download(item.url(), item.target(), item.size(), (done, fileTotal, speed) ->
                        report(new Progress(State.RUNNING, base + done, total > 0 ? total : -1, speed, null)), cancel);
                if (fileDone != null) fileDone.done(item, size);
                before += size;
            }
            report(new Progress(State.DONE, before, total > 0 ? total : before, 0, null));
        } catch (Downloader.CancelledException e) {
            report(new Progress(State.CANCELLED, before, total, 0, "Cancelled"));
        } catch (IOException | RuntimeException e) {
            report(new Progress(State.FAILED, before, total, 0, e.getMessage() == null ? e.toString() : e.getMessage()));
        }
    }

    private void report(Progress p) {
        progress = p;
        if (listener != null) listener.accept(p);
    }
}
