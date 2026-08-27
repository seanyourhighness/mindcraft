package net.clankerjockey.mod.companion;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.goal.Goal;

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
    public boolean canUse() {
        return mob.getMoveTarget() != null;
    }

    @Override
    public boolean canContinueToUse() {
        BlockPos target = mob.getMoveTarget();
        if (target == null) return false;
        double closeness = mob.getMoveCloseness();
        return mob.distanceToSqr(target.getX() + 0.5D, target.getY(), target.getZ() + 0.5D) > closeness * closeness;
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
            this.mob.getNavigation().moveTo(target.getX() + 0.5D, target.getY(), target.getZ() + 0.5D, 1.0D);
        }
    }
}
