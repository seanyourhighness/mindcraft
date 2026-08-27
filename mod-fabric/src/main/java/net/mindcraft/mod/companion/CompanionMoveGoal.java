package net.mindcraft.mod.companion;

import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.util.math.BlockPos;

/**
 * Walks the companion to its configured move target and stops within the
 * configured closeness. When no target is set the goal is inactive, so the
 * companion stands still.
 */
public class CompanionMoveGoal extends Goal {

    private static final int REPATH_TICKS = 20;

    private final CompanionEntity mob;
    private int repathTimer;

    public CompanionMoveGoal(CompanionEntity mob) {
        this.mob = mob;
    }

    @Override
    public boolean canStart() {
        return mob.getMoveTarget() != null;
    }

    @Override
    public boolean shouldContinue() {
        BlockPos target = mob.getMoveTarget();
        if (target == null) return false;
        double closeness = mob.getMoveCloseness();
        return mob.squaredDistanceTo(target.getX() + 0.5D, target.getY(), target.getZ() + 0.5D)
                > closeness * closeness;
    }

    @Override
    public void start() {
        this.repathTimer = 0;
    }

    @Override
    public void stop() {
        mob.clearMoveTarget();
    }

    @Override
    public void tick() {
        BlockPos target = mob.getMoveTarget();
        if (target == null) return;
        if (--this.repathTimer <= 0) {
            this.repathTimer = REPATH_TICKS;
            this.mob.getNavigation().startMovingTo(
                    target.getX() + 0.5D, target.getY(), target.getZ() + 0.5D, 1.0D);
        }
    }
}
