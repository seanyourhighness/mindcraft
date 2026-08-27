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
 * Production inference backend: spawns llama.cpp's {@code llama-server} as a
 * child process and drives it over localhost HTTP using only JDK built-ins
 * (ProcessBuilder + java.net.http.HttpClient). JNI bindings were evaluated
 * and deferred; this process route is the locked design.
 *
 * <p>Lifecycle: {@link #start()} spawns the server and blocks until
 * {@code /health} reports ok (30s timeout); {@link #generate(String,
 * GenOptions)} POSTs to {@code /v1/chat/completions}; {@link #stop()} sends a
 * graceful terminate, waits up to 5s, then force-destroys. All three are safe
 * to call repeatedly.
 */
public final class InferenceEngine implements InferenceBackend {

    private static final Duration START_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration STOP_GRACE = Duration.ofSeconds(5);
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(120);
    private static final int STDOUT_TAIL = 30;
    private static final int STDERR_TAIL = 80;

    private final EngineConfig config;
    private final HttpClient http =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final Deque<String> stdoutTail = new ArrayDeque<>();
    private final Deque<String> stderrTail = new ArrayDeque<>();

    private volatile Process process;
    private volatile int resolvedPort = -1;
    private volatile int exitCode = -1;

    public InferenceEngine(EngineConfig config) {
        this.config = Objects.requireNonNull(config, "config");
    }

    /**
     * Spawn llama-server and wait until its /health endpoint reports ok.
     * Idempotent: no-op if already running.
     *
     * @throws EngineException if the binary cannot be launched, the server
     *         exits during startup, or health does not arrive within 30s
     *         (message includes the last stderr lines)
     */
    public synchronized void start() throws EngineException {
        if (process != null && process.isAlive()) {
            return; // already running
        }
        resolvedPort = config.port() == 0 ? findFreePort() : config.port();
        exitCode = -1;

        Process p;
        try {
            p = new ProcessBuilder(buildCommand()).start();
        } catch (IOException e) {
            throw new EngineException("failed to launch llama-server at " + config.serverBinary(), e);
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
        cmd.add(config.serverBinary().toString());
        cmd.add("--model");
        cmd.add(config.modelPath().toString());
        cmd.add("--host");
        cmd.add(config.host());
        cmd.add("--port");
        cmd.add(String.valueOf(resolvedPort));
        cmd.add("--threads");
        cmd.add(String.valueOf(config.threads()));
        cmd.add("--ctx-size");
        cmd.add(String.valueOf(config.contextSize()));
        cmd.addAll(config.extraArgs());
        return cmd;
    }

    private void waitForHealth() throws EngineException {
        URI healthUri = URI.create("http://" + config.host() + ":" + resolvedPort + "/health");
        long deadline = System.nanoTime() + START_TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            if (!process.isAlive()) {
                throw new EngineException("llama-server exited during startup (exit=" + process.exitValue()
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
                throw new EngineException("interrupted while waiting for llama-server health", e);
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new EngineException("interrupted while waiting for llama-server health", e);
            }
        }
        throw new EngineException("llama-server did not become healthy within "
                + START_TIMEOUT.getSeconds() + "s. stderr tail:\n" + tailText(stderrTail));
    }

    @Override
    public String generate(String prompt, GenOptions options) throws EngineException {
        if (prompt == null || prompt.isBlank()) {
            throw new IllegalArgumentException("prompt must not be blank");
        }
        GenOptions opts = options == null ? new GenOptions() : options;
        Process p = process;
        if (p == null || !p.isAlive()) {
            throw new EngineException("engine is not running; call start() first");
        }
        URI uri = URI.create("http://" + config.host() + ":" + resolvedPort + "/v1/chat/completions");
        HttpRequest req = HttpRequest.newBuilder(uri)
                .timeout(HTTP_TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(buildBody(prompt, opts)))
                .build();
        try {
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                throw new EngineException("llama-server returned HTTP " + resp.statusCode()
                        + ": " + truncate(resp.body()));
            }
            Object tree = MiniJson.parse(resp.body());
            String text = MiniJson.stringAt(tree, "choices[0].message.content");
            if (text == null) {
                throw new EngineException("unexpected llama-server response (no choices[0].message.content): "
                        + truncate(resp.body()));
            }
            return text;
        } catch (IOException e) {
            throw new EngineException("HTTP request to llama-server failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new EngineException("interrupted during generation", e);
        }
    }

    private String buildBody(String prompt, GenOptions opts) {
        Map<String, Object> body = new LinkedHashMap<>();
        List<Map<String, String>> messages = new ArrayList<>();
        // Split the assembled prompt: leading system block (persona/ledger) goes
        // in a system message so the chat template renders it with priority.
        String content = prompt;
        int split = prompt.indexOf("\n\n");
        if (split > 0 && prompt.startsWith("You are ")) {
            content = prompt.substring(split + 2);
            Map<String, String> sys = new LinkedHashMap<>();
            sys.put("role", "system");
            sys.put("content", prompt.substring(0, split));
            messages.add(sys);
        }
        Map<String, String> user = new LinkedHashMap<>();
        user.put("role", "user");
        user.put("content", content);
        messages.add(user);
        body.put("messages", messages);
        body.put("max_tokens", opts.maxTokens());
        body.put("temperature", opts.temperature());
        if (opts.seed() != null) {
            body.put("seed", opts.seed());
        }
        if (opts.grammar() != null && !opts.grammar().isBlank()) {
            body.put("grammar", opts.grammar());
        }
        body.putAll(opts.extraBody());
        body.put("stream", false);
        return MiniJson.stringify(body);
    }

    private static String jsonString(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 16);
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }

    /**
     * Gracefully terminate the server: SIGTERM, wait up to 5s, then
     * force-destroy. Idempotent; safe to call after stop() or before start().
     */
    @Override
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

    @Override
    public boolean isRunning() {
        Process p = process;
        return p != null && p.isAlive();
    }

    /** Exit code of the last llama-server process, or -1 if it never ran, -2 if it survived stop(). */
    public int exitCode() {
        return exitCode;
    }

    /** The port the server is (or was) listening on; -1 before start(). */
    public int port() {
        return resolvedPort;
    }

    private static int findFreePort() throws EngineException {
        try (ServerSocket ss = new ServerSocket(0)) {
            return ss.getLocalPort();
        } catch (IOException e) {
            throw new EngineException("could not find a free port", e);
        }
    }

    private void drainStreams(Process p) {
        Thread out = new Thread(() -> pump(p.getInputStream(), stdoutTail, STDOUT_TAIL), "llama-server-stdout");
        Thread err = new Thread(() -> pump(p.getErrorStream(), stderrTail, STDERR_TAIL), "llama-server-stderr");
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
