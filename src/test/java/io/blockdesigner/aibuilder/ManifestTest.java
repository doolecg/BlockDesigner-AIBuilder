package io.blockdesigner.aibuilder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.blockdesigner.plugin.BlockDesignerPlugin;
import io.blockdesigner.plugin.PluginApi;
import org.junit.jupiter.api.Test;

import java.io.InputStream;

import static org.assertj.core.api.Assertions.assertThat;

/** The manifest BlockDesigner reads: its main class exists, and it asks for an API the vendored jars have. */
class ManifestTest {
    @Test
    void manifestPointsAtThePluginAndFitsTheApi() throws Exception {
        JsonNode m;
        try (InputStream in = getClass().getResourceAsStream("/" + PluginApi.DESCRIPTOR)) {
            assertThat(in).as(PluginApi.DESCRIPTOR + " on the classpath").isNotNull();
            m = new ObjectMapper().readTree(in);
        }
        assertThat(m.path("id").asText()).isEqualTo("ai-builder");
        assertThat(m.path("version").asText()).doesNotContain("@VERSION@");
        assertThat(m.path("api").asInt(1)).isBetween(1, PluginApi.VERSION);
        assertThat(m.path("updates").asText()).startsWith("https://github.com/");
        Class<?> main = Class.forName(m.path("main").asText());
        assertThat(BlockDesignerPlugin.class.isAssignableFrom(main)).isTrue();
        assertThat(main.getDeclaredConstructor().newInstance()).isNotNull();
    }
}
