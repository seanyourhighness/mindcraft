package net.mindcraft.mod;

import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** MindCraft main entrypoint. Game logic lands in later tasks. */
public class MindCraftMod implements ModInitializer {

    public static final String MOD_ID = "mindcraft";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        LOGGER.info("[{}] initialized", MOD_ID);
    }
}
