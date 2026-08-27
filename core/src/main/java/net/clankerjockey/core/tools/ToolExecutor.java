package net.clankerjockey.core.tools;

import net.clankerjockey.core.agent.AgentContext;

import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Executes validated tool calls with independent checks and a per-call time
 * budget. Every call passes through: tool existence, schema validation,
 * security class vs. caller authorization, then execution with timeout.
 */
public final class ToolExecutor implements AutoCloseable {

    public static final long DEFAULT_TIMEOUT_MS = 30_000;

    private final ToolRegistry registry;
    private final long defaultTimeoutMs;
    private final ToolValidator validator = new ToolValidator();
    private final ExecutorService executor;

    public ToolExecutor(ToolRegistry registry) {
        this(registry, DEFAULT_TIMEOUT_MS);
    }

    public ToolExecutor(ToolRegistry registry, long defaultTimeoutMs) {
        this.registry = registry;
        this.defaultTimeoutMs = defaultTimeoutMs > 0 ? defaultTimeoutMs : DEFAULT_TIMEOUT_MS;
        this.executor = Executors.newSingleThreadExecutor(new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "clankerjockey-tool-executor");
                t.setDaemon(true);
                return t;
            }
        });
    }

    public ToolRegistry registry() {
        return registry;
    }

    /**
     * Validate and execute a call. Never throws for expected failures; returns
     * a structured {@link ToolResult} (denied, invalid, failed, timed out...).
     */
    public ToolResult execute(ToolCall call, AgentContext context) {
        if (context == null || context.isCancelled()) {
            return ToolResult.cancelled(call.name(), "Agent loop was cancelled before execution.");
        }
        Tool tool = registry.get(call.name());
        if (tool == null) {
            return ToolResult.failure(call.name(),
                    "Unknown tool '" + call.name() + "'. Available: " + String.join(", ", registry.names()));
        }
        ToolDefinition def = tool.definition();
        ValidationResult validation = validator.validate(call, def);
        if (!validation.valid()) {
            return ToolResult.failure(call.name(),
                    "Invalid arguments: " + String.join("; ", validation.issues()));
        }
        if (def.securityClass() == SecurityClass.PRIVILEGED && !context.isOwner()) {
            return ToolResult.denied(call.name(),
                    "This tool requires owner authorization; request denied.");
        }
        if (context.isCancelled()) {
            return ToolResult.cancelled(call.name(), "Agent loop was cancelled before execution.");
        }

        long timeoutMs = def.timeout().isZero() ? defaultTimeoutMs : def.timeout().toMillis();
        ValidatedCall vc = new ValidatedCall(def.name(), validation.normalizedArguments());
        ToolCall normalizedCall = new ToolCall(vc.toolName(), vc.arguments());

        Future<ToolResult> future = executor.submit(() -> {
            long start = System.nanoTime();
            ToolResult r = tool.execute(normalizedCall, context);
            long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            return r.withDuration(ms);
        });
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            return ToolResult.timedOut(call.name(), timeoutMs);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            return ToolResult.failure(call.name(), "Tool crashed: " + truncate(cause.getMessage()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            return ToolResult.interrupted(call.name(), "Execution was interrupted.");
        }
    }

    private static String truncate(String s) {
        if (s == null) return "unknown error";
        return s.length() <= 200 ? s : s.substring(0, 200) + "...";
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }

    /** For tests: any pending issues surfaced as validation feedback. */
    public List<String> validateOnly(ToolCall call) {
        Tool tool = registry.get(call.name());
        if (tool == null) {
            return List.of("unknown tool '" + call.name() + "'");
        }
        return validator.validate(call, tool.definition()).issues();
    }
}
