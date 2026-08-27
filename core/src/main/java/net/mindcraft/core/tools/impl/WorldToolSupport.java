package net.mindcraft.core.tools.impl;

import net.mindcraft.core.tools.ToolDefinition;
import net.mindcraft.core.tools.ToolResult;
import net.mindcraft.core.world.ActionResult;

/** Shared helpers for tools that act through the {@code AgentWorld} adapter. */
final class WorldToolSupport {

    private WorldToolSupport() {
    }

    /** Map a world action outcome onto a tool result with the same semantics. */
    static ToolResult fromAction(ToolDefinition def, ActionResult ar) {
        return switch (ar.status()) {
            case "success" -> ToolResult.success(def.name(), ar.message(), ar.data());
            case "blocked" -> ToolResult.blocked(def.name(), ar.message(), ar.data());
            case "interrupted" -> ToolResult.interrupted(def.name(), ar.message());
            case "cancelled" -> ToolResult.cancelled(def.name(), ar.message());
            default -> ToolResult.failure(def.name(), ar.message());
        };
    }
}
