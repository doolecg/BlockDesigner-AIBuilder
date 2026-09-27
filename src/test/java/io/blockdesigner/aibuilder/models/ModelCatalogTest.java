package io.blockdesigner.aibuilder.models;

import io.blockdesigner.aibuilder.models.ModelCatalog.ModelEntry;
import io.blockdesigner.aibuilder.models.ModelCatalog.Role;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModelCatalogTest {
    @TempDir
    Path dir;

    @Test
    void theBuiltInCatalogHasGemmaAsTheDefault() throws IOException {
        ModelCatalog c = ModelCatalog.load(dir.resolve("custom-models.json"));
        assertThat(c.builtIn()).extracting(ModelEntry::id).containsExactly("gemma-4-e4b", "qwen2.5-vl-3b", "qwen3-vl-8b", "gemma-3-12b");
        ModelEntry gemma = c.recommended();
        assertThat(gemma.id()).isEqualTo("gemma-4-e4b");
        assertThat(gemma.vision()).isTrue();
        assertThat(gemma.model().orElseThrow().name()).isEqualTo("gemma-4-E4B-it-Q4_0.gguf");
        assertThat(gemma.mmproj().orElseThrow().role()).isEqualTo(Role.MMPROJ);
        assertThat(Sizes.gb(gemma.totalSize())).isEqualTo("5.2 GB");
        for (ModelEntry e : c.builtIn()) {
            assertThat(e.files()).allMatch(f -> f.size() > 0 && f.url().startsWith("https://huggingface.co/") && f.url().endsWith(f.name()));
        }
    }

    @Test
    void customEntriesFromHuggingFaceReferencesAndLinks() {
        ModelEntry e = ModelCatalog.customEntry("", "bartowski/Some-Model-GGUF/Some-Model-Q4_K_M.gguf",
                "https://huggingface.co/bartowski/Some-Model-GGUF/blob/main/mmproj-f16.gguf?download=true");
        assertThat(e.id()).isEqualTo("custom-some-model-q4_k_m");
        assertThat(e.model().orElseThrow().url()).isEqualTo("https://huggingface.co/bartowski/Some-Model-GGUF/resolve/main/Some-Model-Q4_K_M.gguf");
        assertThat(e.mmproj().orElseThrow().url()).isEqualTo("https://huggingface.co/bartowski/Some-Model-GGUF/resolve/main/mmproj-f16.gguf");
        assertThat(e.vision()).isTrue();
        assertThatThrownBy(() -> ModelCatalog.customEntry("x", "not a model", null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ModelCatalog.customEntry("x", "https://example.com/file.bin", null)).hasMessageContaining(".gguf");
    }

    @Test
    void customEntriesAreSavedAndLoaded() throws IOException {
        Path file = dir.resolve("custom-models.json");
        ModelCatalog c = ModelCatalog.load(file);
        c.addCustom(ModelCatalog.customEntry("My model", "owner/repo/my.gguf", null));
        c.learnSize("custom-my-model", "my.gguf", 1234);
        ModelCatalog again = ModelCatalog.load(file);
        ModelEntry e = again.find("custom-my-model").orElseThrow();
        assertThat(e.custom()).isTrue();
        assertThat(e.model().orElseThrow().size()).isEqualTo(1234);
        assertThat(again.all()).hasSize(5);
        again.removeCustom("custom-my-model");
        assertThat(ModelCatalog.load(file).all()).hasSize(4);
    }

    @Test
    void badCatalogsAreRefused() {
        String badId = "{\"models\":[{\"id\":\"../x\",\"files\":[{\"name\":\"a.gguf\",\"url\":\"https://x/a.gguf\"}]}]}";
        assertThatThrownBy(() -> ModelCatalog.load(new ByteArrayInputStream(badId.getBytes(StandardCharsets.UTF_8)), null)).hasMessageContaining("id");
        String badFile = "{\"models\":[{\"id\":\"x\",\"files\":[{\"name\":\"..\\\\evil.gguf\",\"url\":\"https://x/a.gguf\"}]}]}";
        assertThatThrownBy(() -> ModelCatalog.load(new ByteArrayInputStream(badFile.getBytes(StandardCharsets.UTF_8)), null)).hasMessageContaining("file");
    }

    @Test
    void aBrokenCustomFileLeavesTheBuiltInModels() throws IOException {
        Path file = dir.resolve("custom-models.json");
        Files.writeString(file, "{ not json");
        assertThat(ModelCatalog.load(file).all()).hasSize(4);
    }

    @Test
    void theStoreKnowsWhatIsInstalled() throws IOException {
        ModelCatalog c = ModelCatalog.load(null);
        ModelStore store = new ModelStore(dir.resolve("models"));
        ModelEntry custom = ModelCatalog.customEntry("t", "o/r/t.gguf", null);
        assertThat(store.installed(custom)).isFalse();
        Files.createDirectories(store.dir(custom));
        Files.write(Downloader.partFile(store.path(custom, custom.model().orElseThrow())), new byte[10]);
        assertThat(store.downloadedBytes(custom)).isEqualTo(10);
        Files.write(store.path(custom, custom.model().orElseThrow()), new byte[20]);
        assertThat(store.installed(custom)).isTrue();   // size unknown: any finished file counts
        assertThat(store.installed(c.recommended())).isFalse();
        assertThat(store.diskUse()).isEqualTo(30);
        store.delete(custom);
        assertThat(store.dir(custom)).doesNotExist();
    }
}
