package net.mindcraft.mod;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.mindcraft.mod.agent.MindCraftAgent;
import org.lwjgl.glfw.GLFW;

/**
 * Client-only keybind: press Y to toggle the full voice loop
 * (mic capture -> STT -> agent -> TTS) on and off. Toggling is announced
 * through Vera's chat cue so the player knows when the companion is listening.
 *
 * <p>Delegated to {@link MindCraftMod#startVoiceLoop()}/{@link
 * MindCraftMod#stopVoiceLoop()}: an unavailable microphone or STT bundle never
 * errors here, it just reports not-started via the cue.
 */
public final class VoiceKeybind {

    /** Default binding: Y, no modifier. */
    public static final KeyMapping TOGGLE_VOICE = new KeyMapping(
            "key.mindcraft.toggleVoice",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_Y,
            "category.mindcraft");

    private VoiceKeybind() {
    }

    @Mod.EventBusSubscriber(modid = MindCraftMod.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
    public static class KeyBindEvents {
        @SubscribeEvent
        public static void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
            event.register(VoiceKeybind.TOGGLE_VOICE);
        }
    }

    @Mod.EventBusSubscriber(modid = MindCraftMod.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
    public static class TickEvents {
        @SubscribeEvent
        public static void onClientTick(TickEvent.ClientTickEvent event) {
            // Client thread. Consume every click of the toggle press this tick;
            // a second phase of the tick sees no pending click, so no double-fire.
            while (TOGGLE_VOICE.consumeClick()) {
                toggleVoiceLoop();
            }
        }
    }

    private static void toggleVoiceLoop() {
        if (MindCraftMod.isVoiceLoopActive()) {
            MindCraftMod.stopVoiceLoop();
            MindCraftAgent.announceVoice("Voice chat off — talk to me in chat instead.");
        } else if (MindCraftMod.startVoiceLoop()) {
            MindCraftAgent.announceVoice("Listening...");
        } else {
            MindCraftAgent.announceVoice("No microphone or voice input available — type to me instead.");
        }
    }
}
