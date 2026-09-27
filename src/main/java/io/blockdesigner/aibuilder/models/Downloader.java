package io.blockdesigner.aibuilder.models;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resumable HTTP downloads. A file downloads into {@code <name>.part}; an interrupted or cancelled download picks
 * up where it stopped with a Range request, and the finished file is checked against the expected size before it
 * takes its real name.
 */
public final class Downloader {
    /** Progress of one file: bytes so far (including a resumed part), the total (or -1), and a smoothed speed. */
    @FunctionalInterface
    public interface Listener {
        void progress(long done, long total, double bytesPerSecond);
    }

    public static final class CancelledException extends IOException {
        public CancelledException() {
            super("Cancelled");
        }
    }

    private static final Pattern CONTENT_RANGE = Pattern.compile("bytes\\s+(\\d+)-(\\d+)/(\\d+|\\*)");
    public static final String USER_AGENT = "BlockDesigner-AIBuilder";

    private final HttpClient http;

    public Downloader(HttpClient http) {
        this.http = http;
    }

    public static HttpClient defaultClient() {
        return HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(20)).build();
    }

    public static Path partFile(Path target) {
        return target.resolveSibling(target.getFileName() + ".part");
    }

    /**
     * Downloads {@code url} to {@code target}. Returns the final size.
     *
     * @param expectedSize the size the file must have, or 0 / negative when unknown
     */
    public long download(URI url, Path target, long expectedSize, Listener listener, AtomicBoolean cancel) throws IOException {
        if (Files.isRegularFile(target) && (expectedSize <= 0 || Files.size(target) == expectedSize)) {
            long n = Files.size(target);
            listener.progress(n, n, 0);
            return n;
        }
        Files.createDirectories(target.toAbsolutePath().getParent());
        Path part = partFile(target);
        long have = Files.isRegularFile(part) ? Files.size(part) : 0;
        if (expectedSize > 0 && have > expectedSize) {
            Files.delete(part);
            have = 0;
        }
        if (expectedSize > 0 && have == expectedSize) return finish(part, target, expectedSize);

        HttpRequest.Builder req = HttpRequest.newBuilder(url).header("User-Agent", USER_AGENT).timeout(Duration.ofMinutes(2)).GET();
        if (have > 0) req.header("Range", "bytes=" + have + "-");
        HttpResponse<InputStream> resp;
        try {
            resp = http.send(req.build(), HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CancelledException();
        }
        int status = resp.statusCode();
        long total;
        boolean append;
        try (InputStream body = resp.body()) {
            if (status == 416 && have > 0) {
                // Asked for a range past the end: the part file is already complete.
                long known = totalFromRange(resp.headers());
                if (expectedSize > 0 ? have == expectedSize : known < 0 || known == have) return finish(part, target, expectedSize > 0 ? expectedSize : have);
                Files.deleteIfExists(part);
                throw new IOException("The server refused to resume; try again");
            }
            if (status == 206) {
                long start = startFromRange(resp.headers());
                if (start != have) throw new IOException("The server resumed at the wrong place (" + start + " instead of " + have + ")");
                append = true;
                long t = totalFromRange(resp.headers());
                total = t > 0 ? t : expectedSize;
            } else if (status == 200) {
                append = false;   // no resume support: start again
                have = 0;
                long len = resp.headers().firstValueAsLong("Content-Length").orElse(-1);
                total = len > 0 ? len : expectedSize;
            } else {
                throw new IOException("Download failed: HTTP " + status + " from " + url.getHost());
            }
            if (expectedSize > 0 && total > 0 && total != expectedSize) {
                throw new IOException(String.format("The file on the server is %,d bytes, not the expected %,d; not downloading it", total, expectedSize));
            }
            copy(body, part, append, have, total, listener, cancel);
        }
        long size = Files.size(part);
        if (total > 0 && size != total) throw new IOException(String.format("The download stopped early (%,d of %,d bytes); try again to resume", size, total));
        return finish(part, target, expectedSize > 0 ? expectedSize : size);
    }

    private static void copy(InputStream in, Path part, boolean append, long have, long total, Listener listener, AtomicBoolean cancel) throws IOException {
        byte[] buf = new byte[1 << 20];
        long done = have, lastBytes = have;
        long lastTime = System.nanoTime();
        double speed = 0;
        try (OutputStream out = Files.newOutputStream(part, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                append ? StandardOpenOption.APPEND : StandardOpenOption.TRUNCATE_EXISTING)) {
            int n;
            while ((n = in.read(buf)) >= 0) {
                if (cancel.get()) throw new CancelledException();
                out.write(buf, 0, n);
                done += n;
                long now = System.nanoTime();
                double dt = (now - lastTime) / 1e9;
                if (dt >= 0.5) {
                    double instant = (done - lastBytes) / dt;
                    speed = speed == 0 ? instant : speed * 0.7 + instant * 0.3;
                    lastTime = now;
                    lastBytes = done;
                    listener.progress(done, total, speed);
                }
            }
            if (cancel.get()) throw new CancelledException();
        }
        listener.progress(done, total, speed);
    }

    private static long finish(Path part, Path target, long expectedSize) throws IOException {
        long size = Files.size(part);
        if (expectedSize > 0 && size != expectedSize) {
            Files.deleteIfExists(part);
            throw new IOException(String.format("The downloaded file is %,d bytes, not %,d; it was removed", size, expectedSize));
        }
        Files.move(part, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        return size;
    }

    static long totalFromRange(HttpHeaders h) {
        return h.firstValue("Content-Range").map(v -> {
            Matcher m = CONTENT_RANGE.matcher(v);
            if (m.find() && !m.group(3).equals("*")) return Long.parseLong(m.group(3));
            Matcher star = Pattern.compile("bytes\\s+\\*/(\\d+)").matcher(v);
            return star.find() ? Long.parseLong(star.group(1)) : -1L;
        }).orElse(-1L);
    }

    static long startFromRange(HttpHeaders h) {
        return h.firstValue("Content-Range").map(v -> {
            Matcher m = CONTENT_RANGE.matcher(v);
            return m.find() ? Long.parseLong(m.group(1)) : -1L;
        }).orElse(-1L);
    }
}
