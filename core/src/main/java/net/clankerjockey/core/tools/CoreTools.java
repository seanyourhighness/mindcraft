package net.clankerjockey.core.tools;

import net.clankerjockey.core.tools.impl.FollowPlayerTool;
import net.clankerjockey.core.tools.impl.CountItemTool;
import net.clankerjockey.core.tools.impl.CancelTaskTool;
import net.clankerjockey.core.tools.impl.AttackEntityTool;
import net.clankerjockey.core.tools.impl.ConsumeItemTool;
import net.clankerjockey.core.tools.impl.DefendPlayerTool;
import net.clankerjockey.core.tools.impl.DropItemTool;
import net.clankerjockey.core.tools.impl.EquipItemTool;
import net.clankerjockey.core.tools.impl.FleeFromEntityTool;
import net.clankerjockey.core.tools.impl.FindNearbyBlockTool;
import net.clankerjockey.core.tools.impl.FindNearestEntityTool;
import net.clankerjockey.core.tools.impl.ForgetPlaceTool;
import net.clankerjockey.core.tools.impl.GetHostileEntitiesTool;
import net.clankerjockey.core.tools.impl.GetInventoryTool;
import net.clankerjockey.core.tools.impl.GetNearbyEntitiesTool;
import net.clankerjockey.core.tools.impl.GetNearbyPlayersTool;
import net.clankerjockey.core.tools.impl.GetPlayerDistanceTool;
import net.clankerjockey.core.tools.impl.GetPlayerStateTool;
import net.clankerjockey.core.tools.impl.GetSelfStateTool;
import net.clankerjockey.core.tools.impl.GetTaskStatusTool;
import net.clankerjockey.core.tools.impl.GetVisibleBlocksTool;
import net.clankerjockey.core.tools.impl.GoToCoordinatesTool;
import net.clankerjockey.core.tools.impl.GoToPlayerTool;
import net.clankerjockey.core.tools.impl.GoToRememberedPlaceTool;
import net.clankerjockey.core.tools.impl.HasItemTool;
import net.clankerjockey.core.tools.impl.ListPlacesTool;
import net.clankerjockey.core.tools.impl.ListTasksTool;
import net.clankerjockey.core.tools.impl.LookAtPlayerTool;
import net.clankerjockey.core.tools.impl.MoveAwayTool;
import net.clankerjockey.core.tools.impl.OpenContainerTool;
import net.clankerjockey.core.tools.impl.PutInContainerTool;
import net.clankerjockey.core.tools.impl.RecallPlaceTool;
import net.clankerjockey.core.tools.impl.RememberPlaceTool;
import net.clankerjockey.core.tools.impl.StopFollowingTool;
import net.clankerjockey.core.tools.impl.StartTaskTool;
import net.clankerjockey.core.tools.impl.GiveItemTool;
import net.clankerjockey.core.tools.impl.StartCollectTaskTool;
import net.clankerjockey.core.tools.impl.SearchForBlockTool;
import net.clankerjockey.core.tools.impl.SearchForEntityTool;
import net.clankerjockey.core.tools.impl.TakeFromContainerTool;
import net.clankerjockey.core.tools.impl.ViewContainerTool;

import java.util.List;

/**
 * Standard milestone-1 tool set. Register these (plus the loop's synthetic
 * respond tool) into a {@link ToolRegistry} to enable the agent loop.
 */
public final class CoreTools {

    private CoreTools() {
    }

    /** Read-only query tools: safe, grounded observation. */
    public static List<Tool> queryTools() {
        return List.of(
                new GetSelfStateTool(),
                new GetNearbyEntitiesTool(),
                new GetInventoryTool(),
                new GetPlayerStateTool(),
                new GetNearbyPlayersTool(),
                new GetPlayerDistanceTool(),
                new GetHostileEntitiesTool(),
                new FindNearestEntityTool(),
                new FindNearbyBlockTool(),
                new HasItemTool(),
                new CountItemTool(),
                new RecallPlaceTool(),
                new ListPlacesTool(),
                new GetTaskStatusTool(),
                new ListTasksTool(),
                new ViewContainerTool(),
                new GetVisibleBlocksTool());
    }

    /** Action tools for milestone 1: movement and follow behavior. */
    public static List<Tool> actionTools() {
        return List.of(
                new GoToPlayerTool(),
                new GoToCoordinatesTool(),
                new GoToRememberedPlaceTool(),
                new FollowPlayerTool(),
                new StopFollowingTool(),
                new RememberPlaceTool(),
                new ForgetPlaceTool(),
                new StartTaskTool(),
                new CancelTaskTool(),
                new StartCollectTaskTool(),
                new GiveItemTool(),
                new DropItemTool(),
                new EquipItemTool(),
                new ConsumeItemTool(),
                new MoveAwayTool(),
                new LookAtPlayerTool(),
                new FleeFromEntityTool(),
                new AttackEntityTool(),
                new DefendPlayerTool(),
                new OpenContainerTool(),
                new PutInContainerTool(),
                new TakeFromContainerTool(),
                new SearchForBlockTool(),
                new SearchForEntityTool());
    }

    /** The full milestone-1 tool set. */
    public static List<Tool> all() {
        List<Tool> out = new java.util.ArrayList<>();
        out.addAll(queryTools());
        out.addAll(actionTools());
        return out;
    }
}
