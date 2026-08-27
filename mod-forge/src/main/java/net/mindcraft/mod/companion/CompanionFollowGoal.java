package net.mindcraft.mod.companion;

import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.player.Player;

/**
 * Follows the entity's configured follow target at the configured distance.
 * Runs until {@code clearFollow()} is called; the world adapter starts/stops
 * follow behavior through the entity rather than blocking on a long call.
 */
public class CompanionFollowGoal extends Goal {

    private static final int REPATH_TICKS = 10;

    private final CompanionEntity mob;
    private Player target;
    private int repathTimer;

    public CompanionFollowGoal(CompanionEntity mob) {
        this.mob = mob;
    }

    @Override
    public boolean canUse() {
        if (mob.getFollowTargetName() == null) return false;
        this.target = findPlayer();
        return this.target != null && this.target.isAlive();
    }

    @Override
    public boolean canContinueToUse() {
        return canUse();
    }

    @Override
    public void start() {
        this.repathTimer = 0;
    }

    @Override
    public void stop() {
        this.target = null;
    }

    @Override
    public void tick() {
        if (this.target == null) return;
        this.mob.getLookControl().setLookAt(this.target, 30.0F, 30.0F);
        double distSq = this.mob.distanceToSqr(this.target);
        double keep = mob.getFollowDistance();
        if (distSq > keep * keep) {
            if (--this.repathTimer <= 0) {
                this.repathTimer = REPATH_TICKS;
                this.mob.getNavigation().moveTo(this.target, 1.0D);
            }
        }
    }

    private Player findPlayer() {
        String name = mob.getFollowTargetName();
        for (Player p : mob.level().players()) {
            if (p.getName().getString().equalsIgnoreCase(name)) return p;
        }
        return null;
    }
}
