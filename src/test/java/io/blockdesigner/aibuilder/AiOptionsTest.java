package io.blockdesigner.aibuilder;

import io.blockdesigner.aibuilder.models.Engine;
import io.blockdesigner.plugin.OptionValues;
import io.blockdesigner.plugin.Options;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** The Settings window's page and the plugin's own settings agree, both ways; older settings files move over once. */
class AiOptionsTest {
    @TempDir
    Path dir;

    @Test
    void settingsRoundTripThroughThePage() {
        AiSettings s = new AiSettings();
        s.provider = AiSettings.Provider.LOCAL_SERVER;
        s.localServerUrl = "http://localhost:1234/v1";
        s.localServerModel = "gemma3:12b";
        s.localServerVision = false;
        s.claudeModel = "claude-x";
        s.openaiUrl = "https://example.com/v1";
        s.openaiModel = "gpt-x";
        s.engineVariant = Engine.Variant.CPU.id;
        s.startAtLaunch = true;
        s.idleMinutes = 0;
        s.style = "auto";
        s.contextSize = 32768;
        s.maxBlocks = 5000;

        OptionValues v = AiOptions.values(AiOptions.OPTIONS.defaults(), s);
        AiSettings back = new AiSettings();
        AiOptions.apply(v, back);
        assertThat(back.provider).isEqualTo(AiSettings.Provider.LOCAL_SERVER);
        assertThat(back.localServerUrl).isEqualTo("http://localhost:1234/v1");
        assertThat(back.localServerModel).isEqualTo("gemma3:12b");
        assertThat(back.localServerVision).isFalse();
        assertThat(back.claudeModel).isEqualTo("claude-x");
        assertThat(back.openaiUrl).isEqualTo("https://example.com/v1");
        assertThat(back.openaiModel).isEqualTo("gpt-x");
        assertThat(back.engineVariant).isEqualTo("cpu");
        assertThat(back.startAtLaunch).isTrue();
        assertThat(back.idleMinutes).isZero();
        assertThat(back.style).isEqualTo("auto");
        assertThat(back.contextSize).isEqualTo(32768);
        assertThat(back.maxBlocks).isEqualTo(5000);
    }

    @Test
    void defaultsMatchTheOldDefaults() {
        AiSettings fromPage = new AiSettings();
        AiOptions.apply(AiOptions.OPTIONS.defaults(), fromPage);
        AiSettings old = new AiSettings();
        assertThat(fromPage.provider).isEqualTo(old.provider);
        assertThat(fromPage.engineVariant).isEqualTo(old.engineVariant);
        assertThat(fromPage.idleMinutes).isEqualTo(old.idleMinutes);
        assertThat(fromPage.contextSize).isEqualTo(old.contextSize);
        assertThat(fromPage.maxBlocks).isEqualTo(old.maxBlocks);
        assertThat(fromPage.style).isEqualTo(old.style);
        assertThat(fromPage.localServerUrl).isEqualTo(old.localServerUrl);
    }

    @Test
    void grouped_withTheLimitsUnderAdvanced() {
        assertThat(AiOptions.OPTIONS.groups()).extracting(Options.Group::title)
                .containsExactly("Answers come from", "Built-in model", "Building", "Advanced");
        assertThat(AiOptions.OPTIONS.groups().getLast().advanced()).isTrue();
        assertThat(AiOptions.OPTIONS.unit("contextSize")).contains("tokens");
        assertThat(AiOptions.OPTIONS.get("style")).isPresent();
    }

    @Test
    void theMigrationFlagIsSavedSoItRunsOnce() throws Exception {
        Path file = dir.resolve("settings.json");
        AiSettings s = new AiSettings();
        s.style = "medieval";
        s.save(file);
        assertThat(AiSettings.load(file).migratedToApp).as("files from before 0.2.0").isFalse();
        s.migratedToApp = true;
        s.save(file);
        AiSettings again = AiSettings.load(file);
        assertThat(again.migratedToApp).isTrue();
        assertThat(again.style).isEqualTo("medieval");
    }
}
