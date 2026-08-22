package net.mindcraft.core.engine;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * Immutable configuration for an {@link InferenceEngine} (llama-server child
 * process). Defaults: host 127.0.0.1, port 0 (auto-pick a free port), 4
 * threads, 2048-token context, no extra args.
 *
 * <p>Port race caveat: with {@code port == 0} a free port is found by binding
 * a {@code ServerSocket(0)} and closing it immediately before spawning
 * llama-server. Another process could grab the port in that window; treat
 * port 0 as best-effort auto-selection, and prefer an explicit fixed port in
 * production deployments.
 */
public record EngineConfig(
        Path modelPath,
        String host,
        int port,
        int threads,
        int contextSize,
        Path serverBinary,
        List<String> extraArgs) {

    public EngineConfig {
        Objects.requireNonNull(modelPath, "modelPath");
        Objects.requireNonNull(host, "host");
        Objects.requireNonNull(serverBinary, "serverBinary");
        if (port < 0) {
            throw new IllegalArgumentException("port must be >= 0 (0 = auto-pick), got " + port);
        }
        if (threads < 1) {
            throw new IllegalArgumentException("threads must be >= 1, got " + threads);
        }
        if (contextSize < 1) {
            throw new IllegalArgumentException("contextSize must be >= 1, got " + contextSize);
        }
        extraArgs = List.copyOf(extraArgs);
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Fluent builder; {@link #build()} requires modelPath and serverBinary. */
    public static final class Builder {
        private Path modelPath;
        private String host = "127.0.0.1";
        private int port;
        private int threads = 4;
        private int contextSize = 8192;
        private Path serverBinary;
        private List<String> extraArgs = List.of();

        public Builder modelPath(Path modelPath) {
            this.modelPath = modelPath;
            return this;
        }

        public Builder host(String host) {
            this.host = host;
            return this;
        }

        public Builder port(int port) {
            this.port = port;
            return this;
        }

        public Builder threads(int threads) {
            this.threads = threads;
            return this;
        }

        public Builder contextSize(int contextSize) {
            this.contextSize = contextSize;
            return this;
        }

        public Builder serverBinary(Path serverBinary) {
            this.serverBinary = serverBinary;
            return this;
        }

        public Builder extraArgs(List<String> extraArgs) {
            this.extraArgs = extraArgs;
            return this;
        }

        public EngineConfig build() {
            return new EngineConfig(modelPath, host, port, threads, contextSize, serverBinary, extraArgs);
        }
    }
}
