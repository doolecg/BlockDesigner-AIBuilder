package io.blockdesigner.aibuilder.models;

import io.blockdesigner.aibuilder.FakeHttp;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EngineTest {
    @TempDir
    Path dir;

    /** Shaped like GitHub's answer: a "v0.5.0" release with no Windows builds first, then pre-release builds. */
    private static String releases(String base) {
        return """
                [
                  {"tag_name": "v0.5.0", "draft": false, "prerelease": false, "assets": [{"name": "source.tar.gz", "browser_download_url": "%1$s/src", "size": 10}]},
                  {"tag_name": "b11206", "draft": true, "prerelease": true, "assets": [
                    {"name": "llama-b11206-bin-win-vulkan-x64.zip", "browser_download_url": "%1$s/draft", "size": 1}]},
                  {"tag_name": "b11205", "draft": false, "prerelease": true, "assets": [
                    {"name": "cudart-llama-bin-win-cuda-12.4-x64.zip", "browser_download_url": "%1$s/cudart124", "size": 391443627},
                    {"name": "cudart-llama-bin-win-cuda-13.4-x64.zip", "browser_download_url": "%1$s/cudart134", "size": 423535356},
                    {"name": "llama-b11205-bin-win-cpu-arm64.zip", "browser_download_url": "%1$s/cpuarm", "size": 12040648},
                    {"name": "llama-b11205-bin-win-cpu-x64.zip", "browser_download_url": "%1$s/cpu", "size": 19155045},
                    {"name": "llama-b11205-bin-win-cuda-12.4-x64.zip", "browser_download_url": "%1$s/cuda124", "size": 263077910},
                    {"name": "llama-b11205-bin-win-cuda-13.4-x64.zip", "browser_download_url": "%1$s/cuda134", "size": 152343527},
                    {"name": "llama-b11205-bin-win-vulkan-x64.zip", "browser_download_url": "%1$s/vulkan", "size": 33061695}]}
                ]""".formatted(base);
    }

    @Test
    void picksTheNewestReleaseWithTheBuildAndSkipsDrafts() throws IOException {
        List<Engine.Release> rs = Engine.parseReleases(releases("https://x"));
        Engine.Pick gpu = Engine.pick(rs, Engine.Variant.GPU).orElseThrow();
        assertThat(gpu.tag()).isEqualTo("b11205");
        assertThat(gpu.build().name()).isEqualTo("llama-b11205-bin-win-vulkan-x64.zip");
        assertThat(gpu.runtime()).isEmpty();
        assertThat(Engine.pick(rs, Engine.Variant.CPU).orElseThrow().build().name()).isEqualTo("llama-b11205-bin-win-cpu-x64.zip");
        Engine.Pick cuda = Engine.pick(rs, Engine.Variant.NVIDIA).orElseThrow();
        assertThat(cuda.build().name()).isEqualTo("llama-b11205-bin-win-cuda-12.4-x64.zip");
        assertThat(cuda.runtime().orElseThrow().name()).isEqualTo("cudart-llama-bin-win-cuda-12.4-x64.zip");
        assertThat(cuda.size()).isEqualTo(263077910L + 391443627L);
        assertThat(Engine.pick(rs.subList(0, 1), Engine.Variant.GPU)).isEmpty();
        assertThatThrownBy(() -> Engine.parseReleases("{\"message\":\"API rate limit exceeded\"}")).hasMessageContaining("rate limit");
    }

    private static byte[] zip(String... entries) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(bytes)) {
            for (String e : entries) {
                z.putNextEntry(new ZipEntry(e));
                z.write(("contents of " + e).getBytes());
                z.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    @Test
    void unzipRefusesPathsOutsideTheFolder() throws IOException {
        Path z = dir.resolve("evil.zip");
        Files.write(z, zip("ok.txt", "../escape.txt"));
        assertThatThrownBy(() -> Engine.unzip(z, dir.resolve("out"))).hasMessageContaining("Unsafe");
        assertThat(dir.resolve("escape.txt")).doesNotExist();
    }

    @Test
    void installsFromTheReleaseListAndFindsTheServer() throws Exception {
        byte[] build = zip("llama-server.exe", "ggml-vulkan.dll");
        try (FakeHttp http = new FakeHttp()) {
            String base = http.uri("").toString();
            String json = releases(base).replace("\"size\": 33061695", "\"size\": " + build.length);
            http.text("/releases", 200, "application/json", json);
            http.file("/vulkan", build, true, 0);
            Engine engine = new Engine(dir.resolve("engine"), Downloader.defaultClient(), new Downloader(Downloader.defaultClient()), http.uri("/releases"));
            assertThat(engine.installed(Engine.Variant.GPU)).isEmpty();
            Engine.Pick pick = engine.findLatest(Engine.Variant.GPU);
            AtomicReference<Engine.Installed> done = new AtomicReference<>();
            DownloadJob job = engine.installJob(pick, p -> { }, done::set);
            job.run();
            assertThat(job.progress().state()).as(job.progress().message()).isEqualTo(DownloadJob.State.DONE);
            assertThat(done.get().server()).exists();
            assertThat(done.get().dir().getFileName().toString()).isEqualTo("b11205-vulkan");
            Engine.Installed found = engine.installed(Engine.Variant.GPU).orElseThrow();
            assertThat(found.tag()).isEqualTo("b11205");
            assertThat(engine.installed(Engine.Variant.CPU)).isEmpty();
            assertThat(dir.resolve("engine/downloads/llama-b11205-bin-win-vulkan-x64.zip")).doesNotExist();
            assertThat(http.headers.getFirst().getFirst("User-Agent")).isEqualTo(Downloader.USER_AGENT);
        }
    }

    @Test
    void tagNumbersOrderBuilds() {
        assertThat(Engine.tagNumber("b11205")).isEqualTo(11205);
        assertThat(Engine.tagNumber("v0.5.0")).isEqualTo(-1);
        assertThat(Engine.compareVersions("13.4", "12.4")).isPositive();
        assertThat(Engine.Variant.fromId("cuda")).isEqualTo(Engine.Variant.NVIDIA);
        assertThat(Engine.Variant.fromId("nonsense")).isEqualTo(Engine.Variant.GPU);
    }
}
