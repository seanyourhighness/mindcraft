package net.mindcraft.core.agent;

/**
 * Optional observability sink for the agent loop (debug mode). Normal players
 * never see these lines; a developer can attach a logger to inspect the
 * hidden loop: trigger, tool requested, validated, started, completed, loop
 * iteration, final response.
 */
@FunctionalInterface
public interface AgentLogger {

    void log(String line);

    /** No-op logger for production. */
    AgentLogger NOOP = line -> {
    };
}
