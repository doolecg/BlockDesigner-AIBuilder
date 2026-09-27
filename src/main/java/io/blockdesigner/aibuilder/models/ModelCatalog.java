package io.blockdesigner.aibuilder.models;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The models the Models page offers: the built-in ones (from {@code models.json} in the jar) and the user's own
 * custom entries (a Hugging Face file or a URL), kept in {@code custom-models.json} in the plugin's folder.
 */
public final class ModelCatalog {
    public enum Role { MODEL, MMPROJ }

    public record ModelFile(String name, String url, long size, Role role) {
    }

    public record ModelEntry(String id, String name, String tag, String blurb, boolean vision, String ram, String licence,
                             boolean recommended, List<ModelFile> files, boolean custom) {
        public long totalSize() {
            long t = 0;
            for (ModelFile f : files) t += Math.max(0, f.size());
            return t;
        }

        public Optional<ModelFile> model() {
            return files.stream().filter(f -> f.role() == Role.MODEL).findFirst();
        }

        public Optional<ModelFile> mmproj() {
            return files.stream().filter(f -> f.role() == Role.MMPROJ).findFirst();
        }
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    private final List<ModelEntry> builtIn;
    private final List<ModelEntry> custom = new ArrayList<>();
    private final Path customFile;

    private ModelCatalog(List<ModelEntry> builtIn, Path customFile) {
        this.builtIn = List.copyOf(builtIn);
        this.customFile = customFile;
    }

    /** The built-in catalog from the jar, plus custom entries from {@code customFile} (may be null). */
    public static ModelCatalog load(Path customFile) throws IOException {
        try (InputStream in = ModelCatalog.class.getResourceAsStream("/io/blockdesigner/aibuilder/models.json")) {
            if (in == null) throw new IOException("models.json is missing from the plugin");
            return load(in, customFile);
        }
    }

    public static ModelCatalog load(InputStream builtInJson, Path customFile) throws IOException {
        ModelCatalog c = new ModelCatalog(parse(JSON.readTree(builtInJson), false), customFile);
        if (customFile != null && Files.isRegularFile(customFile)) {
            try {
                c.custom.addAll(parse(JSON.readTree(customFile.toFile()), true));
            } catch (IOException e) {
                // A broken custom file shouldn't hide the built-in models.
            }
        }
        return c;
    }

    static List<ModelEntry> parse(JsonNode root, boolean custom) throws IOException {
        List<ModelEntry> out = new ArrayList<>();
        JsonNode models = root.path("models");
        if (!models.isArray()) throw new IOException("A model catalog needs a \"models\" list");
        for (JsonNode m : models) {
            String id = m.path("id").asText("");
            if (!id.matches("[a-z0-9][a-z0-9._-]*")) throw new IOException("Bad model id '" + id + "'");
            List<ModelFile> files = new ArrayList<>();
            for (JsonNode f : m.path("files")) {
                String name = f.path("name").asText("");
                String url = f.path("url").asText("");
                if (!safeFileName(name) || !(url.startsWith("https://") || url.startsWith("http://"))) {
                    throw new IOException("Bad file in model '" + id + "'");
                }
                Role role = "mmproj".equalsIgnoreCase(f.path("role").asText("model")) ? Role.MMPROJ : Role.MODEL;
                files.add(new ModelFile(name, url, f.path("size").asLong(0), role));
            }
            if (files.stream().noneMatch(f -> f.role() == Role.MODEL)) throw new IOException("Model '" + id + "' has no model file");
            out.add(new ModelEntry(id, m.path("name").asText(id), m.path("tag").asText(custom ? "Custom" : ""), m.path("blurb").asText(""),
                    m.path("vision").asBoolean(false), m.path("ram").asText(""), m.path("licence").asText(""),
                    m.path("recommended").asBoolean(false), List.copyOf(files), custom));
        }
        return out;
    }

    static boolean safeFileName(String name) {
        return name.matches("[A-Za-z0-9._-]+") && !name.startsWith(".") && name.length() <= 200;
    }

    public List<ModelEntry> all() {
        List<ModelEntry> out = new ArrayList<>(builtIn);
        out.addAll(custom);
        return out;
    }

    public List<ModelEntry> builtIn() {
        return builtIn;
    }

    public Optional<ModelEntry> find(String id) {
        return all().stream().filter(e -> e.id().equals(id)).findFirst();
    }

    public ModelEntry recommended() {
        return builtIn.stream().filter(ModelEntry::recommended).findFirst().orElse(builtIn.getFirst());
    }

    /**
     * A custom entry from a Hugging Face reference ({@code owner/repo/file.gguf}, or a {@code huggingface.co} link)
     * or a plain https link, with an optional vision projector (mmproj) the same way. Sizes are learnt at download.
     */
    public static ModelEntry customEntry(String name, String modelRef, String mmprojRef) {
        ModelFile model = fileFrom(modelRef, Role.MODEL);
        List<ModelFile> files = new ArrayList<>(List.of(model));
        if (mmprojRef != null && !mmprojRef.isBlank()) files.add(fileFrom(mmprojRef, Role.MMPROJ));
        String base = (name == null || name.isBlank() ? model.name().replaceFirst("\\.gguf$", "") : name).strip();
        String id = "custom-" + base.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]+", "-").replaceAll("^-+|-+$", "");
        if (id.equals("custom-")) id = "custom-model";
        return new ModelEntry(id, base, "Custom", "Your own model", files.size() > 1, "", "See the model's page", false, List.copyOf(files), true);
    }

    static ModelFile fileFrom(String ref, Role role) {
        String r = ref == null ? "" : ref.strip();
        String url;
        if (r.startsWith("https://") || r.startsWith("http://")) {
            // A Hugging Face page link (…/blob/main/…) downloads from …/resolve/main/…
            url = r.replace("/blob/", "/resolve/");
            int q = url.indexOf('?');
            if (q >= 0) url = url.substring(0, q);
        } else {
            String[] parts = r.split("/");
            if (parts.length < 3 || !r.endsWith(".gguf")) {
                throw new IllegalArgumentException("Give a link, or a Hugging Face file as owner/repo/file.gguf");
            }
            String repo = parts[0] + "/" + parts[1];
            String path = String.join("/", java.util.Arrays.copyOfRange(parts, 2, parts.length));
            url = "https://huggingface.co/" + repo + "/resolve/main/" + path;
        }
        String file = url.substring(url.lastIndexOf('/') + 1);
        if (!file.toLowerCase(Locale.ROOT).endsWith(".gguf") || !safeFileName(file)) {
            throw new IllegalArgumentException("The link must end in a .gguf file name");
        }
        return new ModelFile(file, url, 0, role);
    }

    public void addCustom(ModelEntry entry) throws IOException {
        custom.removeIf(e -> e.id().equals(entry.id()));
        custom.add(entry);
        saveCustom();
    }

    public void removeCustom(String id) throws IOException {
        if (custom.removeIf(e -> e.id().equals(id))) saveCustom();
    }

    /** Records the size learnt while downloading a custom file, so later checks can compare it. */
    public void learnSize(String id, String fileName, long size) throws IOException {
        for (int i = 0; i < custom.size(); i++) {
            ModelEntry e = custom.get(i);
            if (!e.id().equals(id)) continue;
            List<ModelFile> files = new ArrayList<>();
            for (ModelFile f : e.files()) files.add(f.name().equals(fileName) && f.size() <= 0 ? new ModelFile(f.name(), f.url(), size, f.role()) : f);
            custom.set(i, new ModelEntry(e.id(), e.name(), e.tag(), e.blurb(), e.vision(), e.ram(), e.licence(), e.recommended(), List.copyOf(files), true));
            saveCustom();
        }
    }

    private void saveCustom() throws IOException {
        if (customFile == null) return;
        ObjectNode root = JSON.createObjectNode();
        ArrayNode arr = root.putArray("models");
        for (ModelEntry e : custom) {
            ObjectNode m = arr.addObject();
            m.put("id", e.id()).put("name", e.name()).put("tag", e.tag()).put("blurb", e.blurb()).put("vision", e.vision())
                    .put("ram", e.ram()).put("licence", e.licence());
            ArrayNode fs = m.putArray("files");
            for (ModelFile f : e.files()) {
                fs.addObject().put("name", f.name()).put("url", f.url()).put("size", f.size()).put("role", f.role() == Role.MMPROJ ? "mmproj" : "model");
            }
        }
        Files.createDirectories(customFile.getParent());
        JSON.writerWithDefaultPrettyPrinter().writeValue(customFile.toFile(), root);
    }
}
