package net.clankerjockey.core.engine;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.UUID;

/**
 * Production STT backend: spawns whisper.cpp's {@code whisper-server} as a
 * child process and drives it over localhost HTTP using only JDK built-ins
 * (ProcessBuilder + java.net.http.HttpClient). Same locked design as
 * {@link TtsEngine} and {@link InferenceEngine} (HTTP-to-server, no JNI).
 *
 * <p>whisper-server (whisper.cpp {@code examples/server}) exposes:
 * <ul>
 *   <li>{@code GET /health} — readiness probe</li>
 *   <li>{@code POST /inference} — multipart: {@code file} (audio) + optional
 *       {@code language}, {@code response_format} (default json),
 *       {@code temperature}, {@code translate}. Returns
 *       {@code {"text": "..."}} (json format).</li>
 * </ul>
 *
 * <p>Lifecycle mirrors {@link TtsEngine}: {@link #start()} spawns the server
 * (model loads at startup, so readiness = /health answering) and blocks until
 * it responds; {@link #stop()} sends a graceful terminate, waits up to 5s,
 * then force-destroys. All are safe to call repeatedly.
 */
public final class SttEngine implements AutoCloseable {

    private static final Duration START_TIMEOUT = Duration.ofSeconds(120);
    private static final Duration STOP_GRACE = Duration.ofSeconds(5);
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(120);
    private static final int STDOUT_TAIL = 30;
    private static final int STDERR_TAIL = 80;

    private final SttConfig config;
    private final HttpClient http =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final Deque<String> stdoutTail = new ArrayDeque<>();
    private final Deque<String> stderrTail = new ArrayDeque<>();

    private volatile Process process;
    private volatile int resolvedPort = -1;
    private volatile int exitCode = -1;

    public SttEngine(SttConfig config) {
        this.config = Objects.requireNonNull(config, "config");
    }

    /**
     * Spawn whisper-server and wait until /health answers. Idempotent: no-op
     * if already running.
     *
     * @throws EngineException if the binary cannot be launched or the server
     *         does not come up within 120s (model load included)
     */
    public synchronized void start() throws EngineException {
        if (process != null && process.isAlive()) {
            return; // already running
        }
        resolvedPort = config.port() == 0 ? findFreePort() : config.port();
        exitCode = -1;

        Process p;
        try {
            ProcessBuilder pb = new ProcessBuilder(buildCommand());
            // Make the bundled libwhisper/libggml resolvable regardless of the
            // game CWD: point LD_LIBRARY_PATH at the binary's own directory
            // (the .so files ship next to whisper-server).
            String binDir = config.binary().toAbsolutePath().getParent().toString();
            String existing = pb.environment().get("LD_LIBRARY_PATH");
            pb.environment().put("LD_LIBRARY_PATH",
                    existing == null || existing.isEmpty() ? binDir : binDir + ":" + existing);
            p = pb.start();
        } catch (IOException e) {
            throw new EngineException("failed to launch whisper-server at " + config.binary(), e);
        }
        process = p;
        drainStreams(p);
        try {
            waitForReady();
        } catch (EngineException e) {
            stop();
            throw e;
        }
    }

    private List<String> buildCommand() {
        List<String> cmd = new ArrayList<>();
        cmd.add(config.binary().toString());
        cmd.add("--port");
        cmd.add(String.valueOf(resolvedPort));
        cmd.add("--host");
        cmd.add(config.host());
        cmd.add("--model");
        cmd.add(config.modelPath().toString());
        cmd.add("--threads");
        cmd.add(String.valueOf(config.threads()));
        // CPU-only by default; a GPU build ignores this harmlessly.
        cmd.add("--no-gpu");
        if (!config.language().isBlank()) {
            cmd.add("--language");
            cmd.add(config.language());
        }
        cmd.addAll(config.extraArgs());
        return cmd;
    }

    /** Poll GET /health until it answers (model is loaded at startup). */
    private void waitForReady() throws EngineException {
        URI health = URI.create("http://" + config.host() + ":" + resolvedPort + "/health");
        long deadline = System.nanoTime() + START_TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            if (!process.isAlive()) {
                throw new EngineException("whisper-server exited during startup (exit=" + process.exitValue()
                        + "). stderr tail:\n" + tailText(stderrTail));
            }
            try {
                HttpRequest req = HttpRequest.newBuilder(health)
                        .timeout(Duration.ofSeconds(2)).GET().build();
                HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() == 200) {
                    return;
                }
            } catch (IOException ignored) {
                // server not listening yet
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new EngineException("interrupted while waiting for whisper-server", e);
            }
            try {
                Thread.sleep(250);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new EngineException("interrupted while waiting for whisper-server", e);
            }
        }
        throw new EngineException("whisper-server did not become ready within "
                + START_TIMEOUT.getSeconds() + "s. stderr tail:\n" + tailText(stderrTail));
    }

    /**
     * Transcribe audio (WAV or any format whisper-server can read; WAV is the
     * safe bet) to text.
     *
     * @param audioBytes  audio payload
     * @param fileName    logical file name for the multipart part
     * @return the transcribed text (trimmed), or empty string if silence
     */
    public String transcribe(byte[] audioBytes, String fileName) throws EngineException {
        return postInference(audioBytes, fileName, false);
    }

    /** Translate non-English audio to English text (whisper translation mode). */
    public String translate(byte[] audioBytes, String fileName) throws EngineException {
        return postInference(audioBytes, fileName, true);
    }

    private String postInference(byte[] audioBytes, String fileName, boolean translate) throws EngineException {
        if (audioBytes == null || audioBytes.length == 0) {
            throw new IllegalArgumentException("audio must not be empty");
        }
        requireRunning();
        String boundary = "----clankerjockey" + UUID.randomUUID();
        byte[] body = multipartBody(audioBytes, fileName, boundary, translate);
        String response = postMultipart("/inference", body, boundary);
        return extractText(response);
    }

    private String postMultipart(String path, byte[] body, String boundary) throws EngineException {
        Process p = process;
        if (p == null || !p.isAlive()) {
            throw new EngineException("stt engine is not running; call start() first");
        }
        URI uri = URI.create("http://" + config.host() + ":" + resolvedPort + path);
        HttpRequest req = HttpRequest.newBuilder(uri)
                .timeout(HTTP_TIMEOUT)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        try {
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                throw new EngineException("whisper-server returned HTTP " + resp.statusCode()
                        + ": " + truncate(resp.body()));
            }
            return resp.body();
        } catch (IOException e) {
            throw new EngineException("HTTP request to whisper-server failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new EngineException("interrupted during transcription", e);
        }
    }

    /**
     * Build the multipart body for POST /inference:
     * {@code file} (audio) + {@code response_format=json} + optional
     * {@code language} / {@code translate}.
     */
    private byte[] multipartBody(byte[] audio, String fileName, String boundary, boolean translate) {
        StringBuilder sb = new StringBuilder();
        sb.append("--").append(boundary).append("\r\n");
        sb.append("Content-Disposition: form-data; name=\"file\"; filename=\"").append(fileName).append("\"\r\n");
        sb.append("Content-Type: ").append(guessContentType(fileName)).append("\r\n\r\n");
        byte[] head = sb.toString().getBytes(StandardCharsets.UTF_8);

        sb.setLength(0);
        sb.append("--").append(boundary).append("\r\n");
        sb.append("Content-Disposition: form-data; name=\"response_format\"\r\n\r\n");
        sb.append("json\r\n");
        if (!config.language().isBlank()) {
            sb.append("--").append(boundary).append("\r\n");
            sb.append("Content-Disposition: form-data; name=\"language\"\r\n\r\n");
            sb.append(config.language()).append("\r\n");
        }
        if (translate) {
            sb.append("--").append(boundary).append("\r\n");
            sb.append("Content-Disposition: form-data; name=\"translate\"\r\n\r\n");
            sb.append("true\r\n");
        }
        sb.append("--").append(boundary).append("--\r\n");
        byte[] tail = sb.toString().getBytes(StandardCharsets.UTF_8);

        byte[] out = new byte[head.length + audio.length + tail.length];
        System.arraycopy(head, 0, out, 0, head.length);
        System.arraycopy(audio, 0, out, head.length, audio.length);
        System.arraycopy(tail, 0, out, head.length + audio.length, tail.length);
        return out;
    }

    private static String extractText(String json) {
        // whisper-server returns {"text": "..."} (json format) — pull the text
        // field without a full JSON parser (the response is a flat object).
        int i = json.indexOf("\"text\"");
        if (i < 0) {
            return "";
        }
        int colon = json.indexOf(':', i);
        if (colon < 0) {
            return "";
        }
        int start = json.indexOf('"', colon + 1);
        if (start < 0) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (int k = start + 1; k < json.length(); k++) {
            char c = json.charAt(k);
            if (c == '"' && json.charAt(k - 1) != '\\') {
                break;
            }
            if (c == '\\' && k + 1 < json.length()) {
                char n = json.charAt(k + 1);
                switch (n) {
                    case 'n' -> out.append('\n');
                    case 't' -> out.append('\t');
                    case '"' -> out.append('"');
                    case '\\' -> out.append('\\');
                    default -> out.append(n);
                }
                k++;
            } else {
                out.append(c);
            }
        }
        return out.toString().trim();
    }

    private static String guessContentType(String fileName) {
        String lower = fileName == null ? "" : fileName.toLowerCase();
        if (lower.endsWith(".wav")) return "audio/wav";
        if (lower.endsWith(".mp3")) return "audio/mpeg";
        if (lower.endsWith(".flac")) return "audio/flac";
        if (lower.endsWith(".ogg")) return "audio/ogg";
        if (lower.endsWith(".m4a")) return "audio/mp4";
        return "application/octet-stream";
    }

    private void requireRunning() {
        Process p = process;
        if (p == null || !p.isAlive()) {
            throw new IllegalStateException("stt engine is not running; call start() first");
        }
    }

    /** Gracefully terminate the server: SIGTERM, wait up to 5s, then force-destroy. */
    public synchronized void stop() {
        Process p = process;
        if (p == null) {
            return;
        }
        p.destroy();
        try {
            if (!p.waitFor(STOP_GRACE.toMillis(), TimeUnit.MILLISECONDS)) {
                p.destroyForcibly();
                p.waitFor(2, TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            p.destroyForcibly();
        }
        exitCode = p.isAlive() ? -2 : p.exitValue();
        process = null;
    }

    public boolean isRunning() {
        Process p = process;
        return p != null && p.isAlive();
    }

    /** Exit code of the last whisper-server process, or -1 if it never ran, -2 if it survived stop(). */
    public int exitCode() {
        return exitCode;
    }

    /** The port the server is (or was) listening on; -1 before start(). */
    public int port() {
        return resolvedPort;
    }

    @Override
    public void close() {
        stop();
    }

    private static int findFreePort() throws EngineException {
        try (ServerSocket ss = new ServerSocket(0)) {
            return ss.getLocalPort();
        } catch (IOException e) {
            throw new EngineException("could not find a free port", e);
        }
    }

    private void drainStreams(Process p) {
        Thread out = new Thread(() -> pump(p.getInputStream(), stdoutTail, STDOUT_TAIL), "whisper-stdout");
        Thread err = new Thread(() -> pump(p.getErrorStream(), stderrTail, STDERR_TAIL), "whisper-stderr");
        out.setDaemon(true);
        err.setDaemon(true);
        out.start();
        err.start();
    }

    private static void pump(InputStream in, Deque<String> tail, int maxLines) {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                synchronized (tail) {
                    tail.addLast(line);
                    while (tail.size() > maxLines) {
                        tail.removeFirst();
                    }
                }
            }
        } catch (IOException ignored) {
            // stream closed when the process exits
        }
    }

    private static String tailText(Deque<String> tail) {
        synchronized (tail) {
            return String.join("\n", tail);
        }
    }

    private static String truncate(String s) {
        return s.length() <= 500 ? s : s.substring(0, 500) + "...";
    }
}
