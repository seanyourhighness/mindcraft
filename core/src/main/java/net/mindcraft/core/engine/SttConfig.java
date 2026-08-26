package net.mindcraft.core.engine;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * Immutable configuration for a {@link SttEngine} (whisper.cpp sidecar).
 *
 * <p>whisper.cpp's {@code whisper-server} is an OpenAI-compatible HTTP server
 * (the {@code examples/server} target in the whisper.cpp repo). It exposes
 * {@code POST /v1/audio/transcriptions} and {@code /v1/audio/translations},
 * so the engine drives it over localhost HTTP exactly like {@link TtsEngine}
 * drives PocketTTS.cpp — no JNI.
 *
 * @param binary     path to the {@code whisper-server} executable
 * @param modelPath  path to a whisper GGUF model (e.g. {@code ggml-small.en.bin})
 * @param host       loopback host to bind to
 * @param port       0 = auto-pick a free port
 * @param threads    CPU threads for inference
 * @param language   forced language (e.g. {@code "en"}); empty = auto-detect
 * @param extraArgs  extra CLI args passed through to whisper-server
 */
public record SttConfig(
        Path binary,
        Path modelPath,
        String host,
        int port,
        int threads,
        String language,
        List<String> extraArgs) {

    public SttConfig {
        Objects.requireNonNull(binary, "binary");
        Objects.requireNonNull(modelPath, "modelPath");
        host = host == null || host.isBlank() ? "127.0.0.1" : host;
        extraArgs = extraArgs == null ? List.of() : List.copyOf(extraArgs);
        language = language == null ? "" : language;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private Path binary;
        private Path modelPath;
        private String host = "127.0.0.1";
        private int port = 0;
        private int threads = 4;
        private String language = "";
        private List<String> extraArgs = List.of();

        public Builder binary(Path binary) { this.binary = binary; return this; }
        public Builder modelPath(Path modelPath) { this.modelPath = modelPath; return this; }
        public Builder host(String host) { this.host = host; return this; }
        public Builder port(int port) { this.port = port; return this; }
        public Builder threads(int threads) { this.threads = threads; return this; }
        public Builder language(String language) { this.language = language; return this; }
        public Builder extraArgs(List<String> extraArgs) { this.extraArgs = extraArgs; return this; }

        public SttConfig build() {
            return new SttConfig(binary, modelPath, host, port, threads, language, extraArgs);
        }
    }
}
