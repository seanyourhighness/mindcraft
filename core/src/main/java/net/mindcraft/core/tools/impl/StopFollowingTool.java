package net.mindcraft.core.tools.impl;

import net.mindcraft.core.agent.AgentContext;
import net.mindcraft.core.tools.Tool;
import net.mindcraft.core.tools.ToolCall;
import net.mindcraft.core.tools.ToolDefinition;
import net.mindcraft.core.tools.ToolResult;

/** {@code stop_following} — end any active follow behavior. */
public final class StopFollowingTool implements Tool {

    private static final ToolDefinition DEF = ToolDefinition.action(
            "stop_following",
            "Stop following whoever the companion is following and stand still.");

    @Override
    public ToolDefinition definition() {
        return DEF;
    }

    @Override
    public ToolResult execute(ToolCall call, AgentContext context) {
        return WorldToolSupport.fromAction(DEF, context.world().stopFollowing());
    }
}
