import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * MindCraft Task 1 feasibility spike harness.
 *
 * Drives a locally built llama.cpp llama-server binary over its OpenAI-compatible
 * HTTP API from Java (JDK 21, zero external dependencies) and measures:
 *   - model load time (wall time from process spawn to /health OK, plus
 *     llama.cpp's own reported model-load timing from the server log)
 *   - generation tokens/sec (cold first generation and warm second generation)
 *   - peak RSS of the inference process (VmHWM from /proc/<pid>/status)
 *
 * Route rationale: production will use JNI, but for THIS spike HTTP-to-llama-server
 * yields identical tokens/sec and RSS numbers because inference cost dominates;
 * it also avoids the fragile JNI-binding setup entirely.
 *
 * Usage: java SpikeHarness.java <llama-server-binary> <model.gguf> [port]
 */
public class SpikeHarness {

    static final String COLD_PROMPT = """
            <|im_start|>system
            You are a helpful assistant writing creative fiction. Be descriptive and detailed.
            <|im_end|>
            <|im_start|>user
            Write a detailed story about a day in the life of a village blacksmith in Minecraft. Make it at least 150 words long.
            <|im_end|>
            <|im_start|>assistant
            """;

    static final String WARM_PROMPT = """
            <|im_start|>system
            You are a helpful assistant writing creative fiction. Be descriptive and detailed.
            <|im_end|>
            <|im_start|>user
            Write a detailed story about a miner discovering a hidden cave full of treasure in Minecraft. Make it at least 150 words long.
            <|im_end|>
            <|im_start|>assistant
            """;

    static final int MAX_TOKENS = 120;

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Usage: SpikeHarness <llama-server> <model.gguf> [port]");
            System.exit(2);
        }
        Path serverBin = Path.of(args[0]).toAbsolutePath();
        Path model = Path.of(args[1]).toAbsolutePath();
        int port = args.length > 2 ? Integer.parseInt(args[2]) : 18080;

        Path logFile = Path.of("server.log");
        Files.deleteIfExists(logFile);

        // 1. Spawn llama-server
        long spawnNanos = System.nanoTime();
        List<String> cmd = new ArrayList<>(List.of(
                serverBin.toString(),
                "-m", model.toString(),
                "-c", "2048",
                "-t", "4",
                "-np", "1",
                "--port", String.valueOf(port),
                "--no-webui",
                "--log-file", logFile.toString()));
        System.out.println("[harness] spawning: " + String.join(" ", cmd));
        Process server = new ProcessBuilder(cmd)
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start();

        // 2. Wait for health endpoint (poll every 100ms, up to 120s)
        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        long healthyNanos = 0;
        boolean up = false;
        for (int i = 0; i < 1200; i++) {
            if (!server.isAlive()) {
                System.err.println("[harness] FATAL: llama-server exited early");
                System.err.println(Files.readString(logFile));
                System.exit(1);
            }
            try {
                HttpResponse<String> health = http.send(
                        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/health"))
                                .timeout(Duration.ofSeconds(2)).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                if (health.statusCode() == 200 && health.body().contains("ok")) {
                    healthyNanos = System.nanoTime();
                    up = true;
                    break;
                }
            } catch (Exception ignored) {
                // server not up yet
            }
            Thread.sleep(100);
        }
        if (!up) {
            System.err.println("[harness] FATAL: server never became healthy");
            System.err.println(Files.readString(logFile));
            server.destroyForcibly();
            System.exit(1);
        }
        double loadWallSec = (healthyNanos - spawnNanos) / 1e9;

        // 3. Two generations: cold then warm (different prompts, so the warm run
        //    does not benefit from the server's KV prompt cache - this isolates
        //    the effect of model weights being hot in the page cache).
        double[] gen = new double[2];
        String[] labels = {"cold", "warm"};
        String[] prompts = {COLD_PROMPT, WARM_PROMPT};
        for (int r = 0; r < 2; r++) {
            String body = """
                    {"model":"qwen2.5-0.5b","messages":[{"role":"user","content":%s}],"max_tokens":%d,"temperature":0.7,"stream":false}
                    """.formatted(jsonString(prompts[r]), MAX_TOKENS);
            long t0 = System.nanoTime();
            HttpResponse<String> resp = http.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/v1/chat/completions"))
                            .timeout(Duration.ofMinutes(5))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                    HttpResponse.BodyHandlers.ofString());
            double wallSec = (System.nanoTime() - t0) / 1e9;
            if (resp.statusCode() != 200) {
                System.err.println("[harness] generation " + labels[r] + " failed HTTP " + resp.statusCode() + ": " + resp.body());
                System.exit(1);
            }
            String json = resp.body();
            int predictedN = extractInt(json, "\"predicted_n\"");
            int promptN = extractInt(json, "\"prompt_n\"");
            double predictedMs = extractDouble(json, "\"predicted_ms\"");
            System.out.printf("[harness] %s generation: http wall=%.2fs, prompt_n=%d, predicted_n=%d, predicted_ms=%.0f, tok/s=%.2f%n",
                    labels[r], wallSec, promptN, predictedN, predictedMs,
                    predictedN > 0 ? predictedN / (predictedMs / 1000.0) : 0.0);
            gen[r] = predictedN > 0 ? predictedN / (predictedMs / 1000.0) : 0.0;
        }

        // 4. Peak RSS of the inference process
        long vmHwmKb = readVmField(server.pid(), "VmHWM");
        long vmRssKb = readVmField(server.pid(), "VmRSS");

        // 5. llama.cpp's own reported load time from server log
        String log = Files.readString(logFile);
        String loadTimeFromLog = grepTotalTime(log);

        server.destroy();
        server.waitFor(10, java.util.concurrent.TimeUnit.SECONDS);
        if (server.isAlive()) server.destroyForcibly();

        // 6. Results
        System.out.println("=== RESULTS ===");
        System.out.printf("load_time_wall_s=%.2f%n", loadWallSec);
        System.out.printf("load_time_llama_log=%s%n", loadTimeFromLog);
        System.out.printf("cold_tok_per_s=%.2f%n", gen[0]);
        System.out.printf("warm_tok_per_s=%.2f%n", gen[1]);
        System.out.printf("peak_rss_kb=%d%n", vmHwmKb);
        System.out.printf("current_rss_kb=%d%n", vmRssKb);
        System.out.printf("threads=4 context=2048%n");
        System.out.printf("server_pid=%d%n", server.pid());
    }

    static String jsonString(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r") + "\"";
    }

    static int extractInt(String json, String key) {
        Matcher m = Pattern.compile(key + "\\s*:\\s*(\\d+)").matcher(json);
        return m.find() ? Integer.parseInt(m.group(1)) : -1;
    }

    static double extractDouble(String json, String key) {
        Matcher m = Pattern.compile(key + "\\s*:\\s*([0-9.]+)").matcher(json);
        return m.find() ? Double.parseDouble(m.group(1)) : -1.0;
    }

    static long readVmField(long pid, String field) throws IOException {
        for (String line : Files.readAllLines(Path.of("/proc/" + pid + "/status"))) {
            if (line.startsWith(field + ":")) {
                return Long.parseLong(line.replaceAll("[^0-9]", ""));
            }
        }
        return -1;
    }

    /** extract the server's own "model loaded" log line (its leading timestamp is seconds since start) */
    static String grepTotalTime(String log) {
        for (String line : log.split("\n")) {
            if (line.contains("model loaded")) return line.trim();
            if (line.contains("llama_new_context_with_model") && line.contains("total time")) return line.trim();
            if (line.contains("llm_load_tensors") && line.contains("total time")) return line.trim();
        }
        return "not-found-in-log";
    }
}
