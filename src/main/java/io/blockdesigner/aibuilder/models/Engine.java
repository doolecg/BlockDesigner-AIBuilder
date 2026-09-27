package io.blockdesigner.aibuilder.models;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * The llama.cpp engine that runs the built-in models: finds the newest release with a Windows build of the wanted
 * kind through the GitHub API, downloads it and unpacks it into {@code <plugin folder>/engine/<tag>-<variant>/}.
 *
 * <p>llama.cpp's numbered builds ({@code b11205}) are published as pre-releases, and the repository's "latest"
 * release can be something else without Windows builds, so the release list is scanned rather than "latest".
 */
public final class Engine {
    public enum Variant {
        GPU("vulkan", "Graphics card (Vulkan): any recent GPU"),
        CPU("cpu", "Processor only: slower, works everywhere"),
        NVIDIA("cuda", "NVIDIA (CUDA): fastest on NVIDIA cards, bigger download");

        public final String id;
        public final String label;

        Variant(String id, String label) {
            this.id = id;
            this.label = label;
        }

        public static Variant fromId(String id) {
            for (Variant v : values()) if (v.id.equals(id) || v.name().equalsIgnoreCase(id)) return v;
            return GPU;
        }
    }

    public record Asset(String name, String url, long size) {
    }

    public record Release(String tag, boolean draft, List<Asset> assets) {
    }

    /** What to download for a variant: the build, and for CUDA the matching runtime. */
    public record Pick(String tag, Variant variant, Asset build, Optional<Asset> runtime) {
        public long size() {
            return build.size() + runtime.map(Asset::size).orElse(0L);
        }
    }

    public record Installed(String tag, Variant variant, Path dir, Path server) {
    }

    public static final URI RELEASES = URI.create("https://api.github.com/repos/ggml-org/llama.cpp/releases?per_page=20");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern TAG_NUMBER = Pattern.compile("b(\\d+)");

    private final Path root;
    private final HttpClient http;
    private final Downloader downloader;
    private final URI releasesUrl;

    public Engine(Path root, HttpClient http, Downloader downloader, URI releasesUrl) {
        this.root = root;
        this.http = http;
        this.downloader = downloader;
        this.releasesUrl = releasesUrl;
    }

    public Path root() {
        return root;
    }

    static List<Release> parseReleases(String json) throws IOException {
        JsonNode arr = JSON.readTree(json);
        if (!arr.isArray()) throw new IOException("Unexpected answer from GitHub: " + arr.path("message").asText("not a release list"));
        List<Release> out = new ArrayList<>();
        for (JsonNode r : arr) {
            List<Asset> assets = new ArrayList<>();
            for (JsonNode a : r.path("assets")) {
                assets.add(new Asset(a.path("name").asText(), a.path("browser_download_url").asText(), a.path("size").asLong()));
            }
            out.add(new Release(r.path("tag_name").asText(), r.path("draft").asBoolean(false), List.copyOf(assets)));
        }
        return out;
    }

    /** The newest release (list order: newest first) that has a Windows x64 build of the variant. */
    static Optional<Pick> pick(List<Release> releases, Variant v) {
        for (Release r : releases) {
            if (r.draft()) continue;
            if (v == Variant.NVIDIA) {
                // Prefer CUDA 12 (works with older drivers), else the newest CUDA; it needs the matching runtime too.
                Pattern p = Pattern.compile("llama-.*-bin-win-cuda-([\\d.]+)-x64\\.zip");
                Asset best = null;
                String bestVer = null;
                for (Asset a : r.assets()) {
                    Matcher m = p.matcher(a.name());
                    if (!m.matches()) continue;
                    String ver = m.group(1);
                    if (best == null || preferCuda(ver, bestVer)) {
                        best = a;
                        bestVer = ver;
                    }
                }
                if (best == null) continue;
                String runtimeName = "cudart-llama-bin-win-cuda-" + bestVer + "-x64.zip";
                Optional<Asset> runtime = r.assets().stream().filter(a -> a.name().equals(runtimeName)).findFirst();
                if (runtime.isEmpty()) continue;
                return Optional.of(new Pick(r.tag(), v, best, runtime));
            }
            Pattern p = Pattern.compile("llama-.*-bin-win-" + v.id + "-x64\\.zip");
            for (Asset a : r.assets()) {
                if (p.matcher(a.name()).matches()) return Optional.of(new Pick(r.tag(), v, a, Optional.empty()));
            }
        }
        return Optional.empty();
    }

    private static boolean preferCuda(String candidate, String current) {
        boolean c12 = candidate.startsWith("12."), cur12 = current.startsWith("12.");
        if (c12 != cur12) return c12;
        return compareVersions(candidate, current) > 0;
    }

    static int compareVersions(String a, String b) {
        String[] x = a.split("\\."), y = b.split("\\.");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int xi = i < x.length ? Integer.parseInt(x[i]) : 0, yi = i < y.length ? Integer.parseInt(y[i]) : 0;
            if (xi != yi) return Integer.compare(xi, yi);
        }
        return 0;
    }

    /** Asks GitHub for the newest build of the variant. */
    public Pick findLatest(Variant v) throws IOException {
        HttpRequest req = HttpRequest.newBuilder(releasesUrl).header("User-Agent", Downloader.USER_AGENT)
                .header("Accept", "application/vnd.github+json").timeout(Duration.ofSeconds(30)).GET().build();
        HttpResponse<String> resp;
        try {
            resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted");
        }
        if (resp.statusCode() != 200) throw new IOException("GitHub answered HTTP " + resp.statusCode() + " when looking for the engine");
        return pick(parseReleases(resp.body()), v).orElseThrow(() ->
                new IOException("No recent llama.cpp release has a Windows " + v.id + " build"));
    }

    /** The newest installed build of the variant, if any. */
    public Optional<Installed> installed(Variant v) {
        if (!Files.isDirectory(root)) return Optional.empty();
        try (Stream<Path> dirs = Files.list(root)) {
            return dirs.filter(Files::isDirectory)
                    .filter(d -> d.getFileName().toString().endsWith("-" + v.id))
                    .map(d -> findServer(d).map(exe -> new Installed(d.getFileName().toString().replaceFirst("-" + v.id + "$", ""), v, d, exe)))
                    .flatMap(Optional::stream)
                    .max(Comparator.comparingLong(i -> tagNumber(i.tag())));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    static long tagNumber(String tag) {
        Matcher m = TAG_NUMBER.matcher(tag);
        return m.find() ? Long.parseLong(m.group(1)) : -1;
    }

    /** Downloads and unpacks a build. Older builds of the same variant are removed afterwards. */
    public DownloadJob installJob(Pick pick, Consumer<DownloadJob.Progress> listener, Consumer<Installed> onInstalled) {
        Path dir = root.resolve(pick.tag() + "-" + pick.variant().id);
        List<DownloadJob.Item> items = new ArrayList<>();
        items.add(new DownloadJob.Item(URI.create(pick.build().url()), root.resolve("downloads").resolve(pick.build().name()), pick.build().size()));
        pick.runtime().ifPresent(r -> items.add(new DownloadJob.Item(URI.create(r.url()), root.resolve("downloads").resolve(r.name()), r.size())));
        int[] left = {items.size()};
        return new DownloadJob(items, downloader, listener, (item, size) -> {
            unzip(item.target(), dir);
            Files.deleteIfExists(item.target());
            if (--left[0] == 0) {
                Path exe = findServer(dir).orElseThrow(() -> new IOException("llama-server.exe is missing from the engine download"));
                removeOlder(pick.variant(), dir);
                if (onInstalled != null) onInstalled.accept(new Installed(pick.tag(), pick.variant(), dir, exe));
            }
        });
    }

    private void removeOlder(Variant v, Path keep) {
        try (Stream<Path> dirs = Files.list(root)) {
            for (Path d : dirs.filter(Files::isDirectory).filter(d -> d.getFileName().toString().endsWith("-" + v.id)).toList()) {
                if (!d.equals(keep)) ModelStore.deleteTree(d);
            }
        } catch (IOException ignored) {
            // an old build left behind is harmless
        }
    }

    static Optional<Path> findServer(Path dir) {
        if (!Files.isDirectory(dir)) return Optional.empty();
        try (Stream<Path> s = Files.walk(dir, 3)) {
            return s.filter(p -> p.getFileName().toString().equalsIgnoreCase("llama-server.exe")).findFirst();
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    /** Unpacks a zip into {@code dir}, refusing entries that would land outside it. */
    static void unzip(Path zip, Path dir) throws IOException {
        Files.createDirectories(dir);
        Path base = dir.toAbsolutePath().normalize();
        try (InputStream in = Files.newInputStream(zip); ZipInputStream z = new ZipInputStream(in)) {
            ZipEntry e;
            while ((e = z.getNextEntry()) != null) {
                Path out = base.resolve(e.getName()).normalize();
                if (!out.startsWith(base)) throw new IOException("Unsafe path in the engine zip: " + e.getName());
                if (e.isDirectory()) {
                    Files.createDirectories(out);
                } else {
                    Files.createDirectories(out.getParent());
                    Files.copy(z, out, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }
}
