package net.clankerjockey.core.tools.impl;

import net.clankerjockey.core.agent.AgentContext;
import net.clankerjockey.core.tools.Tool;
import net.clankerjockey.core.tools.ToolCall;
import net.clankerjockey.core.tools.ToolDefinition;
import net.clankerjockey.core.tools.ToolResult;

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
