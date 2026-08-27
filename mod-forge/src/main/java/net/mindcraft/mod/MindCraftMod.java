package net.mindcraft.mod;

import net.mindcraft.core.engine.EngineConfig;
import net.mindcraft.core.engine.EngineException;
import net.mindcraft.core.engine.GenOptions;
import net.mindcraft.core.engine.InferenceEngine;
import net.mindcraft.mod.agent.MindCraftAgent;
import net.mindcraft.mod.companion.MindCraftModCompanion;
import net.minecraft.client.renderer.entity.VillagerRenderer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * MindCraft main entrypoint (Forge). On client setup, spawns llama-server as
 * a child process of the game JVM and keeps it ready for generation. The
 * server binary and model are resolved from {@code <game dir>/mindcraft/} so
 * no external services are involved.
 */
@Mod(MindCraftMod.MOD_ID)
public class MindCraftMod {

    public static final String MOD_ID = "mindcraft";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static volatile InferenceEngine engine;

    public MindCraftMod() {
        var modBus = FMLJavaModLoadingContext.get().getModEventBus();
        modBus.register(this);
        MindCraftModCompanion.ENTITY_TYPES.register(modBus);
    }

    @SubscribeEvent
    public void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            try {
                engine = createEngine();
                engine.start();
                LOGGER.info("[{}] inference engine healthy on port {}", MOD_ID, engine.port());
                MindCraftAgent.start(engine);
                LOGGER.info("[{}] companion agent online", MOD_ID);
            } catch (EngineException e) {
                // Never crash the game over the assistant: log loudly and run degraded.
                LOGGER.error("[{}] failed to start inference engine; mod runs degraded", MOD_ID, e);
                engine = null;
            }
        });
    }

    /** Companion body attributes (mod bus, both sides). */
    @Mod.EventBusSubscriber(modid = MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD)
    public static class CommonModEvents {
    }

    /** Client-only registration (companion renderer). */
    @Mod.EventBusSubscriber(modid = MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
    public static class ClientModEvents {
        @SubscribeEvent
        public static void registerEntityRenderers(EntityRenderersEvent.RegisterRenderers event) {
            event.registerEntityRenderer(MindCraftModCompanion.COMPANION_TYPE.get(),
                    VillagerRenderer::new);
        }
    }

    private static InferenceEngine createEngine() throws EngineException {
        Path gameDir = Path.of(".").toAbsolutePath();
        String os = System.getProperty("os.name").toLowerCase();
        String binaryName = os.contains("win") ? "llama-server.exe" : "llama-server";
        Path binary = gameDir.resolve("mindcraft/bin/" + binaryName);
        Path model = gameDir.resolve("mindcraft/models/littlelamb-0.3b-toolcalling-q8_0.gguf");

        if (!Files.isExecutable(binary)) {
            throw new EngineException("llama-server not found at " + binary
                    + " - unpack the MindCraft runtime bundle into the game directory");
        }
        if (!Files.isRegularFile(model)) {
            throw new EngineException("model not found at " + model
                    + " - unpack the MindCraft runtime bundle into the game directory");
        }

        return new InferenceEngine(EngineConfig.builder()
                .serverBinary(binary)
                .modelPath(model)
                .port(0) // auto-pick free port, loopback only
                .threads(Runtime.getRuntime().availableProcessors() >= 8 ? 4 : 2)
                .contextSize(8192)
                // thinking-style model: default template emits empty output
                .extraArgs(List.of("--jinja"))
                .build());
    }

    /** Current engine instance, or null if startup failed. */
    public static InferenceEngine engine() {
        return engine;
    }

    /** Generate with production defaults (LittleLamb needs noThink). */
    public static String generate(String prompt) throws EngineException {
        InferenceEngine e = engine;
        if (e == null || !e.isRunning()) {
            throw new EngineException("inference engine is not running");
        }
        return e.generate(prompt, GenOptions.noThink(120, 0.7));
    }
}
