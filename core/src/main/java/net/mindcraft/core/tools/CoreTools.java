package net.mindcraft.core.tools;

import net.mindcraft.core.tools.impl.FollowPlayerTool;
import net.mindcraft.core.tools.impl.CountItemTool;
import net.mindcraft.core.tools.impl.CancelTaskTool;
import net.mindcraft.core.tools.impl.AttackEntityTool;
import net.mindcraft.core.tools.impl.ConsumeItemTool;
import net.mindcraft.core.tools.impl.DefendPlayerTool;
import net.mindcraft.core.tools.impl.DropItemTool;
import net.mindcraft.core.tools.impl.EquipItemTool;
import net.mindcraft.core.tools.impl.FleeFromEntityTool;
import net.mindcraft.core.tools.impl.FindNearbyBlockTool;
import net.mindcraft.core.tools.impl.FindNearestEntityTool;
import net.mindcraft.core.tools.impl.ForgetPlaceTool;
import net.mindcraft.core.tools.impl.GetHostileEntitiesTool;
import net.mindcraft.core.tools.impl.GetInventoryTool;
import net.mindcraft.core.tools.impl.GetNearbyEntitiesTool;
import net.mindcraft.core.tools.impl.GetNearbyPlayersTool;
import net.mindcraft.core.tools.impl.GetPlayerDistanceTool;
import net.mindcraft.core.tools.impl.GetPlayerStateTool;
import net.mindcraft.core.tools.impl.GetSelfStateTool;
import net.mindcraft.core.tools.impl.GetTaskStatusTool;
import net.mindcraft.core.tools.impl.GetVisibleBlocksTool;
import net.mindcraft.core.tools.impl.GoToCoordinatesTool;
import net.mindcraft.core.tools.impl.GoToPlayerTool;
import net.mindcraft.core.tools.impl.GoToRememberedPlaceTool;
import net.mindcraft.core.tools.impl.HasItemTool;
import net.mindcraft.core.tools.impl.ListPlacesTool;
import net.mindcraft.core.tools.impl.ListTasksTool;
import net.mindcraft.core.tools.impl.LookAtPlayerTool;
import net.mindcraft.core.tools.impl.MoveAwayTool;
import net.mindcraft.core.tools.impl.OpenContainerTool;
import net.mindcraft.core.tools.impl.PutInContainerTool;
import net.mindcraft.core.tools.impl.RecallPlaceTool;
import net.mindcraft.core.tools.impl.RememberPlaceTool;
import net.mindcraft.core.tools.impl.StopFollowingTool;
import net.mindcraft.core.tools.impl.StartTaskTool;
import net.mindcraft.core.tools.impl.GiveItemTool;
import net.mindcraft.core.tools.impl.StartCollectTaskTool;
import net.mindcraft.core.tools.impl.SearchForBlockTool;
import net.mindcraft.core.tools.impl.SearchForEntityTool;
import net.mindcraft.core.tools.impl.TakeFromContainerTool;
import net.mindcraft.core.tools.impl.ViewContainerTool;

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
