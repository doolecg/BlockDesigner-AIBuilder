package io.blockdesigner.aibuilder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * The plugin's settings as the rest of the plugin reads them, also kept in {@code settings.json} in its folder. Since
 * 0.2.0 BlockDesigner's Settings window owns all but {@link #activeModel} (see {@link AiOptions}); the file's values are
 * moved there once ({@link #migratedToApp}). API keys are not here; they are kept encrypted by
 * {@link io.blockdesigner.aibuilder.llm.Secrets}.
 */
public final class AiSettings {
    /** Where the answers come from. */
    public enum Provider {
        BUILT_IN("Built-in model (runs on this PC)"),
        LOCAL_SERVER("My local server (Ollama, LM Studio…)"),
        CLAUDE("Claude (my Anthropic API key)"),
        OPENAI("OpenAI or compatible (my API key)");

        public final String label;

        Provider(String label) {
            this.label = label;
        }
    }

    public Provider provider = Provider.BUILT_IN;
    public String activeModel = "gemma-4-e4b";
    public String engineVariant = "vulkan";
    public boolean startAtLaunch = false;
    /** Stop the built-in model after this many idle minutes; 0 keeps it running. */
    public int idleMinutes = 30;
    public int contextSize = 16384;

    public String localServerUrl = "http://localhost:11434/v1";
    public String localServerModel = "";
    public boolean localServerVision = true;

    public String claudeModel = "claude-opus-5-5";
    public String openaiUrl = "https://api.openai.com/v1";
    public String openaiModel = "gpt-5";

    public String style = "auto";
    public int maxBlocks = 250_000;
    /** Set once these settings have been moved to BlockDesigner's Settings window. */
    public boolean migratedToApp;

    private static final ObjectMapper JSON = new ObjectMapper();

    public static AiSettings load(Path file) {
        AiSettings s = new AiSettings();
        if (file == null || !Files.isRegularFile(file)) return s;
        try {
            JsonNode n = JSON.readTree(file.toFile());
            try {
                s.provider = Provider.valueOf(n.path("provider").asText(s.provider.name()));
            } catch (IllegalArgumentException ignored) {
                // unknown provider: keep the default
            }
            s.activeModel = n.path("activeModel").asText(s.activeModel);
            s.engineVariant = n.path("engineVariant").asText(s.engineVariant);
            s.startAtLaunch = n.path("startAtLaunch").asBoolean(s.startAtLaunch);
            s.idleMinutes = Math.max(0, n.path("idleMinutes").asInt(s.idleMinutes));
            s.contextSize = Math.clamp(n.path("contextSize").asInt(s.contextSize), 2048, 131072);
            s.localServerUrl = n.path("localServerUrl").asText(s.localServerUrl);
            s.localServerModel = n.path("localServerModel").asText(s.localServerModel);
            s.localServerVision = n.path("localServerVision").asBoolean(s.localServerVision);
            s.claudeModel = n.path("claudeModel").asText(s.claudeModel);
            s.openaiUrl = n.path("openaiUrl").asText(s.openaiUrl);
            s.openaiModel = n.path("openaiModel").asText(s.openaiModel);
            s.style = n.path("style").asText(s.style);
            s.maxBlocks = Math.clamp(n.path("maxBlocks").asInt(s.maxBlocks), 1000, 2_000_000);
            s.migratedToApp = n.path("migratedToApp").asBoolean(false);
        } catch (IOException e) {
            // A broken file falls back to the defaults.
        }
        return s;
    }

    public void save(Path file) throws IOException {
        ObjectNode n = JSON.createObjectNode();
        n.put("provider", provider.name()).put("activeModel", activeModel).put("engineVariant", engineVariant)
                .put("startAtLaunch", startAtLaunch).put("idleMinutes", idleMinutes).put("contextSize", contextSize)
                .put("localServerUrl", localServerUrl).put("localServerModel", localServerModel).put("localServerVision", localServerVision)
                .put("claudeModel", claudeModel).put("openaiUrl", openaiUrl).put("openaiModel", openaiModel)
                .put("style", style).put("maxBlocks", maxBlocks).put("migratedToApp", migratedToApp);
        Files.createDirectories(file.toAbsolutePath().getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        JSON.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), n);
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
    }
}
