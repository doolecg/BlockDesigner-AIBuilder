package io.blockdesigner.aibuilder.llm;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * API keys kept encrypted with Windows DPAPI for the current user, through PowerShell's
 * {@code ConvertFrom-SecureString} / {@code ConvertTo-SecureString}, in {@code <plugin folder>/keys/<name>.dpapi}.
 * The key goes to PowerShell on standard input, never on its command line. Decrypted keys are cached in memory.
 */
public final class Secrets {
    /** Runs a PowerShell script with some standard input and returns its standard output. */
    @FunctionalInterface
    public interface Runner {
        String run(String script, String stdin) throws IOException;
    }

    static final String ENCRYPT = "$s = [Console]::In.ReadToEnd(); if ($s.Length -eq 0) { exit 2 }; "
            + "ConvertTo-SecureString -String $s -AsPlainText -Force | ConvertFrom-SecureString";
    static final String DECRYPT = "$e = [Console]::In.ReadToEnd().Trim(); $ss = ConvertTo-SecureString -String $e; "
            + "$b = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($ss); "
            + "try { [Console]::Out.Write([Runtime.InteropServices.Marshal]::PtrToStringBSTR($b)) } "
            + "finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($b) }";

    public static final Runner POWERSHELL = (script, stdin) -> {
        String root = Optional.ofNullable(System.getenv("SystemRoot")).orElse("C:\\Windows");
        Path exe = Path.of(root, "System32", "WindowsPowerShell", "v1.0", "powershell.exe");
        String encoded = Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE));
        List<String> cmd = List.of(Files.isRegularFile(exe) ? exe.toString() : "powershell", "-NoProfile", "-NonInteractive",
                "-ExecutionPolicy", "Bypass", "-EncodedCommand", encoded);
        Process p = new ProcessBuilder(cmd).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        try (OutputStream in = p.getOutputStream()) {
            in.write(stdin.getBytes(StandardCharsets.UTF_8));
        }
        String out;
        try (InputStream o = p.getInputStream()) {
            out = new String(o.readAllBytes(), StandardCharsets.UTF_8);
        }
        try {
            if (!p.waitFor(30, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new IOException("PowerShell didn't answer");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted");
        }
        if (p.exitValue() != 0) throw new IOException("PowerShell couldn't protect the key (exit " + p.exitValue() + ")");
        return out;
    };

    private final Path dir;
    private final Runner runner;
    private final Map<String, String> cache = new ConcurrentHashMap<>();

    public Secrets(Path dir, Runner runner) {
        this.dir = dir;
        this.runner = runner;
    }

    private Path file(String name) {
        if (!name.matches("[a-z0-9_-]+")) throw new IllegalArgumentException("Bad key name");
        return dir.resolve(name + ".dpapi");
    }

    public boolean has(String name) {
        return cache.containsKey(name) || Files.isRegularFile(file(name));
    }

    /** Stores a key (blank deletes it). */
    public void save(String name, String secret) throws IOException {
        if (secret == null || secret.isBlank()) {
            delete(name);
            return;
        }
        String s = secret.strip();
        if (!s.chars().allMatch(ch -> ch > 32 && ch < 127)) throw new IOException("An API key has only letters, digits and symbols, no spaces");
        String protectedText = runner.run(ENCRYPT, s).strip();
        if (!protectedText.matches("[0-9a-fA-F]{32,}")) throw new IOException("PowerShell gave an unexpected answer when protecting the key");
        Files.createDirectories(dir);
        Files.writeString(file(name), protectedText, StandardCharsets.US_ASCII);
        cache.put(name, s);
    }

    public Optional<String> load(String name) throws IOException {
        String cached = cache.get(name);
        if (cached != null) return Optional.of(cached);
        Path f = file(name);
        if (!Files.isRegularFile(f)) return Optional.empty();
        String s = runner.run(DECRYPT, Files.readString(f, StandardCharsets.US_ASCII).strip());
        if (s.isEmpty()) throw new IOException("The saved key couldn't be read (was it saved by another Windows user?)");
        cache.put(name, s);
        return Optional.of(s);
    }

    public void delete(String name) throws IOException {
        cache.remove(name);
        Files.deleteIfExists(file(name));
    }
}
