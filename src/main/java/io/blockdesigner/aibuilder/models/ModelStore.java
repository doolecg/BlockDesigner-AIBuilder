package io.blockdesigner.aibuilder.models;

import io.blockdesigner.aibuilder.models.ModelCatalog.ModelEntry;
import io.blockdesigner.aibuilder.models.ModelCatalog.ModelFile;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;

/** Downloaded models: {@code <plugin folder>/models/<id>/<file>}. */
public final class ModelStore {
    private final Path root;

    public ModelStore(Path root) {
        this.root = root;
    }

    public Path root() {
        return root;
    }

    public Path dir(ModelEntry e) {
        return root.resolve(e.id());
    }

    public Path path(ModelEntry e, ModelFile f) {
        return dir(e).resolve(f.name());
    }

    public boolean installed(ModelEntry e) {
        for (ModelFile f : e.files()) {
            Path p = path(e, f);
            try {
                if (!Files.isRegularFile(p) || (f.size() > 0 && Files.size(p) != f.size())) return false;
            } catch (IOException ex) {
                return false;
            }
        }
        return true;
    }

    /** Bytes already downloaded towards the model (finished files and parts). */
    public long downloadedBytes(ModelEntry e) {
        long n = 0;
        for (ModelFile f : e.files()) {
            Path p = path(e, f), part = Downloader.partFile(p);
            try {
                if (Files.isRegularFile(p)) n += Files.size(p);
                else if (Files.isRegularFile(part)) n += Files.size(part);
            } catch (IOException ignored) {
                // counted as nothing
            }
        }
        return n;
    }

    public DownloadJob download(ModelEntry e, Downloader downloader, Consumer<DownloadJob.Progress> listener, DownloadJob.FileDone fileDone) {
        List<DownloadJob.Item> items = new ArrayList<>();
        for (ModelFile f : e.files()) items.add(new DownloadJob.Item(URI.create(f.url()), path(e, f), f.size()));
        return new DownloadJob(items, downloader, listener, fileDone);
    }

    public void delete(ModelEntry e) throws IOException {
        deleteTree(dir(e));
    }

    public long diskUse() {
        return treeSize(root);
    }

    static long treeSize(Path dir) {
        if (!Files.isDirectory(dir)) return 0;
        try (Stream<Path> s = Files.walk(dir)) {
            return s.filter(Files::isRegularFile).mapToLong(p -> {
                try {
                    return Files.size(p);
                } catch (IOException e) {
                    return 0;
                }
            }).sum();
        } catch (IOException e) {
            return 0;
        }
    }

    static void deleteTree(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (Stream<Path> s = Files.walk(dir)) {
            for (Path p : s.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
        }
    }
}
