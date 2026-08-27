package net.clankerjockey.core.agent;

import net.clankerjockey.core.tools.ToolCall;

import java.util.List;

/**
 * Final outcome of one agent turn: the companion's spoken text plus an
 * observability trail of the tool calls that happened along the way.
 */
public record AgentResponse(
        String text,
        List<ToolTrace> toolCalls,
        int iterations,
        boolean interrupted,
        boolean limitExceeded,
        List<ToolCall> rawCalls) {

    public AgentResponse {
        text = text == null ? "" : text;
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        rawCalls = rawCalls == null ? List.of() : List.copyOf(rawCalls);
    }
}
