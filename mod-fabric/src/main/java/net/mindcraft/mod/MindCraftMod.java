package net.mindcraft.mod;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.mindcraft.mod.companion.CompanionEntity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnGroup;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.util.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** MindCraft main entrypoint: registers the companion body entity. */
public class MindCraftMod implements ModInitializer {

    public static final String MOD_ID = "mindcraft";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public static final EntityType<CompanionEntity> COMPANION_TYPE = Registry.register(
            Registries.ENTITY_TYPE,
            Identifier.of(MOD_ID, "companion"),
            EntityType.Builder.create(CompanionEntity::new, SpawnGroup.CREATURE)
                    .dimensions(0.6F, 1.95F)
                    .maxTrackingRange(10)
                    .build());

    @Override
    public void onInitialize() {
        FabricDefaultAttributeRegistry.register(COMPANION_TYPE, VillagerEntity.createVillagerAttributes());
        LOGGER.info("[{}] initialized (companion entity registered)", MOD_ID);
    }
}
