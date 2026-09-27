package io.blockdesigner.aibuilder.models;

import io.blockdesigner.aibuilder.FakeHttp;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DownloaderTest {
    @TempDir
    Path dir;

    private static byte[] data(int n) {
        byte[] b = new byte[n];
        new Random(42).nextBytes(b);
        return b;
    }

    private final Downloader dl = new Downloader(Downloader.defaultClient());

    @Test
    void downloadsAndChecksTheSize() throws Exception {
        byte[] d = data(300_000);
        try (FakeHttp http = new FakeHttp()) {
            http.file("/m.gguf", d, true, 0);
            List<Long> seen = new ArrayList<>();
            Path target = dir.resolve("models/x/m.gguf");
            long n = dl.download(http.uri("/m.gguf"), target, d.length, (done, total, speed) -> seen.add(done), new AtomicBoolean());
            assertThat(n).isEqualTo(d.length);
            assertThat(Files.readAllBytes(target)).isEqualTo(d);
            assertThat(Downloader.partFile(target)).doesNotExist();
            assertThat(seen).last().isEqualTo((long) d.length);
            // A second call finds it done without asking the server again.
            int before = http.requests.size();
            dl.download(http.uri("/m.gguf"), target, d.length, (a, b, c) -> { }, new AtomicBoolean());
            assertThat(http.requests).hasSize(before);
        }
    }

    @Test
    void resumesAPartWithARangeRequest() throws Exception {
        byte[] d = data(200_000);
        try (FakeHttp http = new FakeHttp()) {
            http.file("/m.gguf", d, true, 0);
            Path target = dir.resolve("m.gguf");
            Files.write(Downloader.partFile(target), java.util.Arrays.copyOf(d, 123_456));
            dl.download(http.uri("/m.gguf"), target, d.length, (a, b, c) -> { }, new AtomicBoolean());
            assertThat(http.requests).containsExactly("GET /m.gguf Range:bytes=123456-");
            assertThat(Files.readAllBytes(target)).isEqualTo(d);
        }
    }

    @Test
    void startsAgainWhenTheServerIgnoresRanges() throws Exception {
        byte[] d = data(50_000);
        try (FakeHttp http = new FakeHttp()) {
            http.file("/m.gguf", d, false, 0);
            Path target = dir.resolve("m.gguf");
            Files.write(Downloader.partFile(target), new byte[20_000]);   // junk that must not be kept
            dl.download(http.uri("/m.gguf"), target, d.length, (a, b, c) -> { }, new AtomicBoolean());
            assertThat(Files.readAllBytes(target)).isEqualTo(d);
        }
    }

    @Test
    void refusesAFileOfTheWrongSize() throws Exception {
        byte[] d = data(10_000);
        try (FakeHttp http = new FakeHttp()) {
            http.file("/m.gguf", d, true, 0);
            Path target = dir.resolve("m.gguf");
            assertThatThrownBy(() -> dl.download(http.uri("/m.gguf"), target, 9_999, (a, b, c) -> { }, new AtomicBoolean()))
                    .isInstanceOf(IOException.class).hasMessageContaining("not the expected");
            assertThat(target).doesNotExist();
        }
    }

    @Test
    void reportsHttpErrors() throws Exception {
        try (FakeHttp http = new FakeHttp()) {
            http.text("/missing", 404, "text/plain", "no");
            assertThatThrownBy(() -> dl.download(http.uri("/missing"), dir.resolve("a.gguf"), 0, (a, b, c) -> { }, new AtomicBoolean()))
                    .hasMessageContaining("404");
        }
    }

    @Test
    void aFinishedPartWithUnknownSizeIsKeptOn416() throws Exception {
        byte[] d = data(5_000);
        try (FakeHttp http = new FakeHttp()) {
            http.file("/m.gguf", d, true, 0);
            Path target = dir.resolve("m.gguf");
            Files.write(Downloader.partFile(target), d);
            dl.download(http.uri("/m.gguf"), target, 0, (a, b, c) -> { }, new AtomicBoolean());
            assertThat(Files.readAllBytes(target)).isEqualTo(d);
        }
    }

    @Test
    void cancelKeepsThePartAndTheNextTryResumes() throws Exception {
        byte[] d = data(400_000);
        try (FakeHttp http = new FakeHttp()) {
            http.file("/m.gguf", d, true, 8_000);
            Path target = dir.resolve("m.gguf");
            AtomicBoolean cancel = new AtomicBoolean();
            CompletableFuture<Throwable> run = CompletableFuture.supplyAsync(() -> {
                try {
                    dl.download(http.uri("/m.gguf"), target, d.length, (done, total, speed) -> {
                        if (done > 50_000) cancel.set(true);
                    }, cancel);
                    return null;
                } catch (Throwable t) {
                    return t;
                }
            });
            // The listener only fires every half second: cancel from here too once some bytes are in.
            Path part = Downloader.partFile(target);
            long end = System.currentTimeMillis() + 10_000;
            while (System.currentTimeMillis() < end && (!Files.exists(part) || Files.size(part) < 40_000)) Thread.sleep(20);
            cancel.set(true);
            Throwable t = run.get(20, TimeUnit.SECONDS);
            assertThat(t).isInstanceOf(Downloader.CancelledException.class);
            assertThat(target).doesNotExist();
            long partial = Files.size(part);
            assertThat(partial).isBetween(1L, (long) d.length - 1);
            dl.download(http.uri("/m.gguf"), target, d.length, (a, b, c) -> { }, new AtomicBoolean());
            assertThat(Files.readAllBytes(target)).isEqualTo(d);
            assertThat(http.requests.getLast()).contains("Range:bytes=" + partial + "-");
        }
    }

    @Test
    void aJobAddsUpItsFiles() throws Exception {
        byte[] a = data(30_000), b = data(70_000);
        try (FakeHttp http = new FakeHttp()) {
            http.file("/a", a, true, 0);
            http.file("/b", b, true, 0);
            List<DownloadJob.Progress> seen = new ArrayList<>();
            List<Long> sizes = new ArrayList<>();
            DownloadJob job = new DownloadJob(List.of(
                    new DownloadJob.Item(http.uri("/a"), dir.resolve("a.gguf"), a.length),
                    new DownloadJob.Item(http.uri("/b"), dir.resolve("b.gguf"), 0)), dl, seen::add, (item, size) -> sizes.add(size));
            assertThat(job.progress().state()).isEqualTo(DownloadJob.State.QUEUED);
            job.run();
            assertThat(job.progress().state()).isEqualTo(DownloadJob.State.DONE);
            assertThat(job.progress().done()).isEqualTo(100_000);
            assertThat(sizes).containsExactly(30_000L, 70_000L);
            assertThat(seen).anyMatch(p -> p.state() == DownloadJob.State.RUNNING);
            assertThat(job.finished()).isTrue();
        }
    }

    @Test
    void progressText() {
        var p = new DownloadJob.Progress(DownloadJob.State.RUNNING, 430, 1000, 12 * 1024 * 1024, null);
        assertThat(p.describe()).startsWith("43% · 12 MB/s");
        var slow = new DownloadJob.Progress(DownloadJob.State.RUNNING, 0, 5_200_000_000L, 12 * 1024 * 1024, null);
        assertThat(slow.describe()).contains("min left");
        assertThat(Sizes.gb(5_150_682_208L)).isEqualTo("5.2 GB");
        assertThat(Sizes.duration(125)).isEqualTo("2 min");
    }
}
