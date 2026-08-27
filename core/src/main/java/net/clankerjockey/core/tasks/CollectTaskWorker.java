package net.clankerjockey.core.tasks;

import net.clankerjockey.core.world.ActionResult;
import net.clankerjockey.core.world.AgentWorld;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Advances a {@code collect} task one step per call (search → walk → break →
 * add to inventory → repeat until the target count or a terminal state). A
 * loader schedules this on the server thread at a fixed interval; the worker
 * itself is a pure state machine and never blocks.
 *
 * <p>Task metadata (set by {@code start_collect_task}): {@code block} (block
 * id), {@code count} (target), {@code phase} (search/walk/break), and the
 * worker writes progress ({@code collected}, {@code target} coords).</p>
 */
public final class CollectTaskWorker {

    public static final String TYPE = "collect";

    private static final String PHASE = "phase";
    private static final String SEARCH = "search";
    private static final String WALK = "walk";
    private static final String BREAK = "break";
    private static final String COLLECTED = "collected";
    private static final String TARGET_X = "target_x";
    private static final String TARGET_Y = "target_y";
    private static final String TARGET_Z = "target_z";
    private static final String PHASE_STARTED_AT = "phase_started_at";
    private static final double ARRIVE_CLOSENESS = 3.0D;
    private static final double SEARCH_RADIUS = 32.0D;
    private static final long WALK_TIMEOUT_MS = 30_000L;

    private final AgentWorld world;

    public CollectTaskWorker(AgentWorld world) {
        this.world = world;
    }

    /**
     * Advance one step. Returns true when the task is terminal (done, failed,
     * blocked, cancelled...), false when more ticks are needed.
     */
    public boolean tick(AgentTask task, String blockId, int targetCount) {
        if (task.isTerminal()) return true;
        if (blockId == null || blockId.isBlank() || targetCount <= 0) {
            task.update(TaskStatus.FAILED, "Collect task is misconfigured.", Map.of());
            return true;
        }

        Map<String, Object> data = new LinkedHashMap<>(task.data());
        String phase = String.valueOf(data.getOrDefault(PHASE, SEARCH));
        int collected = data.get(COLLECTED) instanceof Number n ? n.intValue() : 0;
        long now = System.currentTimeMillis();

        switch (phase) {
            case SEARCH -> {
                Optional<net.clankerjockey.core.world.BlockInfo> found =
                        world.findNearbyBlock(blockId, SEARCH_RADIUS);
                if (found.isEmpty()) {
                    Map<String, Object> fail = new LinkedHashMap<>();
                    fail.put("distance_searched", SEARCH_RADIUS);
                    task.update(TaskStatus.BLOCKED,
                            "No " + blockId + " visible within " + SEARCH_RADIUS + " blocks.", fail);
                    return true;
                }
                net.clankerjockey.core.world.BlockInfo b = found.get();
                ActionResult walk = world.goTo(b.x(), b.y(), b.z(), ARRIVE_CLOSENESS);
                data.put(TARGET_X, b.x());
                data.put(TARGET_Y, b.y());
                data.put(TARGET_Z, b.z());
                data.put(PHASE, WALK);
                data.put(PHASE_STARTED_AT, now);
                data.put("walk_result", walk.message());
                data.put(COLLECTED, collected);
                task.update(TaskStatus.RUNNING, "Walking to " + blockId + " at "
                        + Math.round(b.x()) + ", " + Math.round(b.y()) + ", " + Math.round(b.z()) + ".", data);
                return false;
            }
            case WALK -> {
                double tx = toDouble(data.get(TARGET_X));
                double ty = toDouble(data.get(TARGET_Y));
                double tz = toDouble(data.get(TARGET_Z));
                if (world.isNear(tx, ty, tz, ARRIVE_CLOSENESS)) {
                    data.put(PHASE, BREAK);
                    task.update(TaskStatus.RUNNING, "Arrived; breaking " + blockId + ".", data);
                    return false;
                }
                long started = data.get(PHASE_STARTED_AT) instanceof Number n ? n.longValue() : now;
                if (now - started > WALK_TIMEOUT_MS) {
                    task.update(TaskStatus.BLOCKED, "Could not reach " + blockId + " in time.", data);
                    return true;
                }
                return false;
            }
            case BREAK -> {
                double tx = toDouble(data.get(TARGET_X));
                double ty = toDouble(data.get(TARGET_Y));
                double tz = toDouble(data.get(TARGET_Z));
                ActionResult broke = world.breakBlockAt(tx, ty, tz);
                if (!broke.ok() || !(broke.data().get("block") instanceof String broken)) {
                    task.update(TaskStatus.FAILED,
                            "Could not break " + blockId + ": " + broke.message(), data);
                    return true;
                }
                ActionResult added = world.addItem(broken, 1);
                if (!added.ok()) {
                    task.update(TaskStatus.FAILED,
                            "Could not store " + broken + ": " + added.message(), data);
                    return true;
                }
                int newCollected = collected + 1;
                data.put(COLLECTED, newCollected);
                if (newCollected >= targetCount) {
                    Map<String, Object> done = new LinkedHashMap<>();
                    done.put("collected", newCollected);
                    done.put("block", broken);
                    task.update(TaskStatus.SUCCEEDED,
                            "Collected " + newCollected + " " + broken + ".", done);
                    return true;
                }
                data.put(PHASE, SEARCH);
                data.remove("walk_result");
                task.update(TaskStatus.RUNNING,
                        "Collected " + newCollected + " of " + targetCount + " " + blockId + ".", data);
                return false;
            }
            default -> {
                task.update(TaskStatus.FAILED, "Unknown task phase '" + phase + "'.", data);
                return true;
            }
        }
    }

    private static double toDouble(Object v) {
        return v instanceof Number n ? n.doubleValue() : 0.0D;
    }
}
