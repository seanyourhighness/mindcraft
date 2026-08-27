package net.mindcraft.mod.companion;

import net.mindcraft.mod.MindCraftMod;
import net.minecraft.entity.EntityData;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.ai.goal.LookAroundGoal;
import net.minecraft.entity.ai.goal.LookAtEntityGoal;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.LocalDifficulty;
import net.minecraft.world.ServerWorldAccess;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

/**
 * The companion's in-world body (Fabric 1.21.1): a villager-shaped entity
 * whose AI is entirely controlled by the MindCraft agent (follow player /
 * move to coordinates / stand still). Vanilla villager goals are removed so
 * the companion never wanders off or trades on its own.
 */
public class CompanionEntity extends VillagerEntity {

    private String followTargetName;
    private double followDistance = 4.0D;
    private BlockPos moveTarget;
    private double moveCloseness = 2.0D;

    public CompanionEntity(EntityType<? extends VillagerEntity> type, World world) {
        super(type, world);
    }

    public static CompanionEntity create(World world) {
        return MindCraftMod.COMPANION_TYPE.create(world);
    }

    @Override
    protected void initGoals() {
        this.goalSelector.clear(goal -> true);
        this.goalSelector.add(0, new CompanionMoveGoal(this));
        this.goalSelector.add(1, new CompanionFollowGoal(this));
        this.goalSelector.add(2, new LookAtEntityGoal(this, PlayerEntity.class, 12.0F));
        this.goalSelector.add(3, new LookAroundGoal(this));
    }

    @Override
    public EntityData initialize(ServerWorldAccess world, LocalDifficulty difficulty,
                                 SpawnReason spawnReason, @Nullable EntityData entityData) {
        this.setPersistent();
        this.setCanPickUpLoot(false);
        return super.initialize(world, difficulty, spawnReason, entityData);
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

    @Override
    public void writeCustomDataToNbt(NbtCompound nbt) {
        super.writeCustomDataToNbt(nbt);
        if (followTargetName != null) nbt.putString("MindCraftFollow", followTargetName);
        nbt.putDouble("MindCraftFollowDistance", followDistance);
        if (moveTarget != null) {
            nbt.putIntArray("MindCraftMoveTarget",
                    new int[]{moveTarget.getX(), moveTarget.getY(), moveTarget.getZ()});
            nbt.putDouble("MindCraftMoveCloseness", moveCloseness);
        }
    }

    @Override
    public void readCustomDataFromNbt(NbtCompound nbt) {
        super.readCustomDataFromNbt(nbt);
        if (nbt.contains("MindCraftFollow")) followTargetName = nbt.getString("MindCraftFollow");
        followDistance = nbt.contains("MindCraftFollowDistance") ? nbt.getDouble("MindCraftFollowDistance") : 4.0D;
        if (nbt.contains("MindCraftMoveTarget")) {
            int[] p = nbt.getIntArray("MindCraftMoveTarget");
            if (p.length == 3) moveTarget = new BlockPos(p[0], p[1], p[2]);
            moveCloseness = nbt.contains("MindCraftMoveCloseness") ? nbt.getDouble("MindCraftMoveCloseness") : 2.0D;
        }
    }
}
