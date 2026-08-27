package net.clankerjockey.core.engine;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * Immutable configuration for a {@link TtsEngine} (PocketTTS.cpp sidecar).
 *
 * <p>Mirrors {@link EngineConfig}: the {@code pocket-tts} binary is spawned in
 * {@code --server} mode on a free loopback port and driven over HTTP, exactly
 * the locked design the llama-server spike settled on (HTTP-to-server, no JNI).
 *
 * <p>Defaults: host 127.0.0.1, port 0 (auto-pick), 8 threads, int8 precision,
 * 1 flow-matching step. The ONNX models and reference voice samples are
 * resolved from {@code <game dir>/clankerjockey/} so no external services are
 * involved.
 */
public record TtsConfig(
        Path binary,
        Path modelsDir,
        Path voicesDir,
        Path tokenizerPath,
        String host,
        int port,
        int threads,
        String precision,
        int lsdSteps,
        List<String> extraArgs) {

    public TtsConfig {
        Objects.requireNonNull(binary, "binary");
        Objects.requireNonNull(modelsDir, "modelsDir");
        Objects.requireNonNull(voicesDir, "voicesDir");
        Objects.requireNonNull(host, "host");
        // Default the tokenizer to <modelsDir>/tokenizer.model so the engine is
        // CWD-independent (the C++ side resolves a bare path against its CWD).
        if (tokenizerPath == null) {
            tokenizerPath = modelsDir.resolve("tokenizer.model");
        }
        if (port < 0) {
            throw new IllegalArgumentException("port must be >= 0 (0 = auto-pick), got " + port);
        }
        if (threads < 1) {
            throw new IllegalArgumentException("threads must be >= 1, got " + threads);
        }
        if (lsdSteps < 1) {
            throw new IllegalArgumentException("lsdSteps must be >= 1, got " + lsdSteps);
        }
        extraArgs = List.copyOf(extraArgs);
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Fluent builder; {@link #build()} requires binary, modelsDir, and voicesDir. */
    public static final class Builder {
        private Path binary;
        private Path modelsDir;
        private Path voicesDir;
        private Path tokenizerPath;
        private String host = "127.0.0.1";
        private int port;
        private int threads = 8;
        private String precision = "int8";
        private int lsdSteps = 1;
        private List<String> extraArgs = List.of();

        public Builder binary(Path binary) { this.binary = binary; return this; }
        public Builder modelsDir(Path modelsDir) { this.modelsDir = modelsDir; return this; }
        public Builder voicesDir(Path voicesDir) { this.voicesDir = voicesDir; return this; }
        public Builder tokenizerPath(Path tokenizerPath) { this.tokenizerPath = tokenizerPath; return this; }
        public Builder host(String host) { this.host = host; return this; }
        public Builder port(int port) { this.port = port; return this; }
        public Builder threads(int threads) { this.threads = threads; return this; }
        public Builder precision(String precision) { this.precision = precision; return this; }
        public Builder lsdSteps(int lsdSteps) { this.lsdSteps = lsdSteps; return this; }
        public Builder extraArgs(List<String> extraArgs) { this.extraArgs = List.copyOf(extraArgs); return this; }

        public TtsConfig build() {
            return new TtsConfig(binary, modelsDir, voicesDir, tokenizerPath, host, port, threads, precision, lsdSteps, extraArgs);
        }
    }
}
