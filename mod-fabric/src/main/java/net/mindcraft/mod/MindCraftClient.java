package net.mindcraft.mod;

import net.fabricmc.api.ClientModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** MindCraft client-side entrypoint. Game logic lands in later tasks. */
public class MindCraftClient implements ClientModInitializer {

    public static final Logger LOGGER = LoggerFactory.getLogger(MindCraftMod.MOD_ID + "-client");

    @Override
    public void onInitializeClient() {
        LOGGER.info("[{}] client initialized", MindCraftMod.MOD_ID);
    }
}
