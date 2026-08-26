package net.mindcraft.core.engine;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * Production TTS backend: spawns PocketTTS.cpp's {@code pocket-tts --server}
 * as a child process and drives it over localhost HTTP using only JDK
 * built-ins (ProcessBuilder + java.net.http.HttpClient). Same locked design as
 * {@link InferenceEngine} (HTTP-to-server, no JNI): the C++ sidecar loads the
 * ONNX models, keeps the voice-embedding cache warm, and streams f32le PCM.
 *
 * <p>Endpoints (PocketTTS.cpp):
 * <ul>
 *   <li>{@code GET /health} — readiness gate</li>
 *   <li>{@code POST /v1/audio/speech} — OpenAI-compatible, returns a WAV
 *       (IEEE-float, 24 kHz mono) for {@code response_format=wav}</li>
 *   <li>{@code POST /tts} — streaming raw f32le PCM (chunked); lowest
 *       latency path, ~30 ms time-to-first-audio on a warm voice</li>
 * </ul>
 *
 * <p>Lifecycle mirrors {@link InferenceEngine}: {@link #start()} spawns the
 * server and blocks until {@code /health} reports ok; {@link #stop()} sends a
 * graceful terminate, waits up to 5s, then force-destroys. All three are safe
 * to call repeatedly.
 */
public final class TtsEngine implements AutoCloseable {

    private static final Duration START_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration STOP_GRACE = Duration.ofSeconds(5);
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(120);
    private static final int STDOUT_TAIL = 30;
    private static final int STDERR_TAIL = 80;

    /** Pocket TTS Mimi codec output rate. */
    public static final int SAMPLE_RATE = 24000;

    private final TtsConfig config;
    private final HttpClient http =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final Deque<String> stdoutTail = new ArrayDeque<>();
    private final Deque<String> stderrTail = new ArrayDeque<>();

    private volatile Process process;
    private volatile int resolvedPort = -1;
    private volatile int exitCode = -1;

    public TtsEngine(TtsConfig config) {
        this.config = Objects.requireNonNull(config, "config");
    }

    /**
     * Spawn the pocket-tts server and wait until /health reports ok.
     * Idempotent: no-op if already running.
     *
     * @throws EngineException if the binary cannot be launched or health does
     *         not arrive within 30s (message includes the last stderr lines)
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
            // Make the bundled onnxruntime resolvable regardless of the game
            // CWD: point LD_LIBRARY_PATH at the binary's own directory
            // (libonnxruntime.so ships next to the pocket-tts binary).
            String binDir = config.binary().toAbsolutePath().getParent().toString();
            String existing = pb.environment().get("LD_LIBRARY_PATH");
            pb.environment().put("LD_LIBRARY_PATH",
                    existing == null || existing.isEmpty() ? binDir : binDir + ":" + existing);
            p = pb.start();
        } catch (IOException e) {
            throw new EngineException("failed to launch pocket-tts at " + config.binary(), e);
        }
        process = p;
        drainStreams(p);
        try {
            waitForHealth();
        } catch (EngineException e) {
            stop();
            throw e;
        }
    }

    private List<String> buildCommand() {
        List<String> cmd = new ArrayList<>();
        cmd.add(config.binary().toString());
        cmd.add("--server");
        cmd.add("--port");
        cmd.add(String.valueOf(resolvedPort));
        cmd.add("--models-dir");
        cmd.add(config.modelsDir().toString());
        cmd.add("--voices-dir");
        cmd.add(config.voicesDir().toString());
        cmd.add("--tokenizer");
        cmd.add(config.tokenizerPath().toString());
        cmd.add("--precision");
        cmd.add(config.precision());
        cmd.add("--threads");
        cmd.add(String.valueOf(config.threads()));
        cmd.add("--lsd-steps");
        cmd.add(String.valueOf(config.lsdSteps()));
        cmd.addAll(config.extraArgs());
        return cmd;
    }

    private void waitForHealth() throws EngineException {
        URI healthUri = URI.create("http://" + config.host() + ":" + resolvedPort + "/health");
        long deadline = System.nanoTime() + START_TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            if (!process.isAlive()) {
                throw new EngineException("pocket-tts exited during startup (exit=" + process.exitValue()
                        + "). stderr tail:\n" + tailText(stderrTail));
            }
            try {
                HttpRequest req = HttpRequest.newBuilder(healthUri)
                        .timeout(Duration.ofSeconds(2)).GET().build();
                HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() == 200 && resp.body().contains("ok")) {
                    return;
                }
            } catch (IOException ignored) {
                // server not listening yet
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new EngineException("interrupted while waiting for pocket-tts health", e);
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new EngineException("interrupted while waiting for pocket-tts health", e);
            }
        }
        throw new EngineException("pocket-tts did not become healthy within "
                + START_TIMEOUT.getSeconds() + "s. stderr tail:\n" + tailText(stderrTail));
    }

    /**
     * Synthesize text in the cloned voice and return a WAV file (RIFF,
     * IEEE-float 24 kHz mono) ready for {@code SoundEvent} registration.
     *
     * @param text  the line to speak
     * @param voice reference voice sample name inside the voices dir
     * @return WAV bytes
     */
    public byte[] speakWav(String text, String voice) throws EngineException {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("text must not be blank");
        }
        requireRunning();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", "pocket-tts");
        body.put("input", text);
        body.put("voice", voice);
        body.put("response_format", "wav");
        return post("/v1/audio/speech", body);
    }

    /**
     * Synthesize text and return raw little-endian float32 PCM (24 kHz mono)
     * via the streaming {@code /tts} endpoint — the lowest-latency path for
     * chunked playback.
     */
    public byte[] speakPcm(String text, String voice) throws EngineException {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("text must not be blank");
        }
        requireRunning();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("text", text);
        body.put("voice", voice);
        return post("/tts", body);
    }

    /** Convert f32le PCM (24 kHz mono) to 16-bit little-endian PCM. */
    public static byte[] f32leToPcm16(byte[] f32le) {
        ByteBuffer in = ByteBuffer.wrap(f32le).order(ByteOrder.LITTLE_ENDIAN);
        int n = in.remaining() / 4;
        ByteBuffer out = ByteBuffer.allocate(n * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < n; i++) {
            float f = in.getFloat();
            if (f > 1.0f) f = 1.0f;
            if (f < -1.0f) f = -1.0f;
            out.putShort((short) Math.round(f * Short.MAX_VALUE));
        }
        return out.array();
    }

    private byte[] post(String path, Map<String, Object> body) throws EngineException {
        Process p = process;
        if (p == null || !p.isAlive()) {
            throw new EngineException("tts engine is not running; call start() first");
        }
        URI uri = URI.create("http://" + config.host() + ":" + resolvedPort + path);
        HttpRequest req = HttpRequest.newBuilder(uri)
                .timeout(HTTP_TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(MiniJson.stringify(body)))
                .build();
        try {
            HttpResponse<byte[]> resp = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
            if (resp.statusCode() != 200) {
                throw new EngineException("pocket-tts returned HTTP " + resp.statusCode()
                        + ": " + truncate(new String(resp.body(), StandardCharsets.UTF_8)));
            }
            return resp.body();
        } catch (IOException e) {
            throw new EngineException("HTTP request to pocket-tts failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new EngineException("interrupted during synthesis", e);
        }
    }

    private void requireRunning() {
        Process p = process;
        if (p == null || !p.isAlive()) {
            throw new IllegalStateException("tts engine is not running; call start() first");
        }
    }

    /**
     * Gracefully terminate the server: SIGTERM, wait up to 5s, then
     * force-destroy. Idempotent; safe to call after stop() or before start().
     */
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

    /** Exit code of the last pocket-tts process, or -1 if it never ran, -2 if it survived stop(). */
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
        Thread out = new Thread(() -> pump(p.getInputStream(), stdoutTail, STDOUT_TAIL), "pocket-tts-stdout");
        Thread err = new Thread(() -> pump(p.getErrorStream(), stderrTail, STDERR_TAIL), "pocket-tts-stderr");
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
