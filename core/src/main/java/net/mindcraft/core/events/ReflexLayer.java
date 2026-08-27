package net.mindcraft.core.events;

import net.mindcraft.core.world.AgentWorld;
import net.mindcraft.core.world.EntityInfo;
import net.mindcraft.core.world.SelfState;

import java.util.List;
import java.util.Optional;

/**
 * The companion's reflex nervous system: deterministic, LLM-free behaviors
 * that bypass the model entirely (critical health, imminent creeper, fire).
 * The loader runs this on its tick; the outcome is recorded as a semantic
 * event so the LLM is informed afterward instead of mid-crisis.
 */
public final class ReflexLayer {

    /** Below this health the companion retreats without asking the model. */
    public static final double CRITICAL_HEALTH = 5.0D;
    /** Hostiles inside this distance trigger an automatic evade. */
    public static final double HOSTILE_DANGER_DISTANCE = 4.0D;
    private static final double HOSTILE_SCAN_RADIUS = 8.0D;

    /** One reflex outcome, already executed through the world. */
    public record ReflexResult(String action, String reason, double value) {

        public SemanticEvent toEvent() {
            return SemanticEvent.of(EventPriority.P0, "REFLEX",
                    "You automatically " + action + " because " + reason + ".");
        }
    }

    /**
     * Check the world and, if a reflex is warranted, execute it and return the
     * outcome. Returns empty when everything is calm.
     */
    public Optional<ReflexResult> tick(AgentWorld world) {
        SelfState self = world.selfState();
        if (self.health() <= CRITICAL_HEALTH) {
            world.moveAway(12.0D);
            return Optional.of(new ReflexResult("moved away",
                    "health was critically low (" + round1(self.health()) + "/20)", self.health()));
        }
        List<EntityInfo> hostiles = world.nearbyEntities(HOSTILE_SCAN_RADIUS);
        for (EntityInfo e : hostiles) {
            if (e.hostile() && e.distance() <= HOSTILE_DANGER_DISTANCE) {
                world.moveAway(16.0D);
                return Optional.of(new ReflexResult("evaded",
                        "a " + e.type() + " was " + round1(e.distance())
                                + "m " + e.direction(), e.distance()));
            }
        }
        return Optional.empty();
    }

    private static String round1(double d) {
        return String.format(java.util.Locale.ROOT, "%.1f", d);
    }
}
