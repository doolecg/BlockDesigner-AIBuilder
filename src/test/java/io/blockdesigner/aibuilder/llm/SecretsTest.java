package io.blockdesigner.aibuilder.llm;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class SecretsTest {
    @TempDir
    Path dir;

    @Test
    void theKeyGoesOnStandardInputAndIsStoredEncrypted() throws IOException {
        List<String> stdins = new ArrayList<>();
        Secrets.Runner fake = (script, stdin) -> {
            stdins.add(stdin);
            if (script.equals(Secrets.ENCRYPT)) return "01000000d08c9ddf" + "ab".repeat(20) + "\r\n";
            return "sk-secret-123";
        };
        Secrets s = new Secrets(dir.resolve("keys"), fake);
        assertThat(s.has("anthropic")).isFalse();
        s.save("anthropic", "  sk-secret-123 ");
        assertThat(stdins.getFirst()).isEqualTo("sk-secret-123");
        String stored = Files.readString(dir.resolve("keys/anthropic.dpapi"));
        assertThat(stored).doesNotContain("secret").startsWith("01000000d08c9ddf");
        assertThat(s.load("anthropic")).contains("sk-secret-123");
        // A fresh instance decrypts through the runner.
        Secrets again = new Secrets(dir.resolve("keys"), fake);
        assertThat(again.load("anthropic")).contains("sk-secret-123");
        assertThat(stdins.getLast()).isEqualTo(stored);
        again.save("anthropic", "");
        assertThat(again.has("anthropic")).isFalse();
        assertThat(again.load("openai")).isEmpty();
        assertThatThrownBy(() -> again.save("openai", "has space")).hasMessageContaining("no spaces");
    }

    @Test
    void roundTripsThroughRealDpapiWhenPowerShellIsThere() throws IOException {
        assumeTrue(System.getProperty("os.name").toLowerCase().contains("win"), "Windows only");
        Secrets s = new Secrets(dir.resolve("keys"), Secrets.POWERSHELL);
        try {
            s.save("test", "dummy-KEY_0123456789");
        } catch (IOException e) {
            assumeTrue(false, "PowerShell unavailable: " + e.getMessage());
        }
        assertThat(Files.readString(dir.resolve("keys/test.dpapi"))).doesNotContain("dummy");
        Secrets fresh = new Secrets(dir.resolve("keys"), Secrets.POWERSHELL);
        assertThat(fresh.load("test")).contains("dummy-KEY_0123456789");
    }
}
