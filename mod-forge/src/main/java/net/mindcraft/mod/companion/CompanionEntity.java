package net.mindcraft.mod.companion;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.SpawnGroupData;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * The companion's in-world body: a villager-shaped entity whose AI is
 * entirely controlled by the MindCraft agent (follow player / move to
 * coordinates / stand still). Vanilla villager goals are removed so the
 * companion never wanders off or trades on its own.
 */
public class CompanionEntity extends Villager {

    private String followTargetName;
    private double followDistance = 4.0D;
    private BlockPos moveTarget;
    private double moveCloseness = 2.0D;

    public CompanionEntity(EntityType<? extends Villager> type, Level level) {
        super(type, level);
    }

    public static CompanionEntity create(Level level) {
        return MindCraftModCompanion.COMPANION_TYPE.get().create(level);
    }

    @Override
    protected void registerGoals() {
        this.goalSelector.removeAllGoals(g -> true);
        this.goalSelector.addGoal(0, new CompanionMoveGoal(this));
        this.goalSelector.addGoal(1, new CompanionFollowGoal(this));
        this.goalSelector.addGoal(2, new LookAtPlayerGoal(this, Player.class, 12.0F));
        this.goalSelector.addGoal(3, new RandomLookAroundGoal(this));
    }

    @Override
    public SpawnGroupData finalizeSpawn(ServerLevelAccessor level, DifficultyInstance difficulty,
                                        MobSpawnType reason, @Nullable SpawnGroupData spawnData,
                                        @Nullable CompoundTag tag) {
        this.setPersistenceRequired();
        this.setCanPickUpLoot(false);
        return super.finalizeSpawn(level, difficulty, reason, spawnData, tag);
    }

    // --- agent-controlled behavior ------------------------------------------

    public void setFollow(String playerName, double distance) {
        this.followTargetName = playerName;
        this.followDistance = Math.max(1.0D, distance);
        this.moveTarget = null;
    }

    public void clearFollow() {
        this.followTargetName = null;
        this.getNavigation().stop();
    }

    public String getFollowTargetName() {
        return followTargetName;
    }

    public double getFollowDistance() {
        return followDistance;
    }

    public void setMoveTarget(BlockPos pos, double closeness) {
        this.moveTarget = pos;
        this.moveCloseness = Math.max(0.5D, closeness);
        this.followTargetName = null;
        this.getNavigation().stop();
    }

    public void clearMoveTarget() {
        this.moveTarget = null;
        this.getNavigation().stop();
    }

    public BlockPos getMoveTarget() {
        return moveTarget;
    }

    public double getMoveCloseness() {
        return moveCloseness;
    }

    /** True when this entity is actively following or walking somewhere. */
    public boolean isBusy() {
        return followTargetName != null || moveTarget != null;
    }

    public Vec3 positionVec() {
        return this.position();
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        if (followTargetName != null) tag.putString("MindCraftFollow", followTargetName);
        tag.putDouble("MindCraftFollowDistance", followDistance);
        if (moveTarget != null) {
            tag.putIntArray("MindCraftMoveTarget", new int[]{moveTarget.getX(), moveTarget.getY(), moveTarget.getZ()});
            tag.putDouble("MindCraftMoveCloseness", moveCloseness);
        }
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        if (tag.contains("MindCraftFollow")) followTargetName = tag.getString("MindCraftFollow");
        followDistance = tag.contains("MindCraftFollowDistance") ? tag.getDouble("MindCraftFollowDistance") : 4.0D;
        if (tag.contains("MindCraftMoveTarget")) {
            int[] p = tag.getIntArray("MindCraftMoveTarget");
            if (p.length == 3) moveTarget = new BlockPos(p[0], p[1], p[2]);
            moveCloseness = tag.contains("MindCraftMoveCloseness") ? tag.getDouble("MindCraftMoveCloseness") : 2.0D;
        }
    }
}
