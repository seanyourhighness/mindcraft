package net.mindcraft.mod.companion;

import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.world.ServerWorld;

/**
 * Follows the entity's configured follow target at the configured distance.
 * Runs until {@code clearFollow()} is called; the world adapter starts/stops
 * follow behavior through the entity rather than blocking on a long call.
 */
public class CompanionFollowGoal extends Goal {

    private static final int REPATH_TICKS = 10;

    private final CompanionEntity mob;
    private PlayerEntity target;
    private int repathTimer;

    public CompanionFollowGoal(CompanionEntity mob) {
        this.mob = mob;
    }

    @Override
    public boolean canStart() {
        if (mob.getFollowTargetName() == null) return false;
        this.target = findPlayer();
        return this.target != null && this.target.isAlive();
    }

    @Override
    public boolean shouldContinue() {
        return canStart();
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
        this.mob.getLookControl().lookAt(this.target, 30.0F, 30.0F);
        double distSq = this.mob.squaredDistanceTo(this.target);
        double keep = mob.getFollowDistance();
        if (distSq > keep * keep) {
            if (--this.repathTimer <= 0) {
                this.repathTimer = REPATH_TICKS;
                this.mob.getNavigation().startMovingTo(this.target, 1.0D);
            }
        }
    }

    private PlayerEntity findPlayer() {
        String name = mob.getFollowTargetName();
        if (mob.getWorld() instanceof ServerWorld serverWorld) {
            for (PlayerEntity p : serverWorld.getPlayers()) {
                if (p.getName().getString().equalsIgnoreCase(name)) return p;
            }
        }
        return null;
    }
}
