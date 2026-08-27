package net.mindcraft.mod;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.mindcraft.core.engine.EngineConfig;
import net.mindcraft.core.engine.EngineException;
import net.mindcraft.core.engine.InferenceEngine;
import net.mindcraft.mod.agent.MindCraftAgent;
import net.minecraft.client.render.entity.VillagerEntityRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * MindCraft client-side entrypoint (Fabric): spawns llama-server as a child
 * process (same layout as Forge), registers the companion renderer and starts
 * the agent loop once the engine is healthy.
 */
public class MindCraftClient implements ClientModInitializer {

    public static final Logger LOGGER = LoggerFactory.getLogger(MindCraftMod.MOD_ID + "-client");

    private static volatile InferenceEngine engine;

    @Override
    public void onInitializeClient() {
        EntityRendererRegistry.register(MindCraftMod.COMPANION_TYPE, VillagerEntityRenderer::new);
        Thread engineThread = new Thread(() -> {
            try {
                engine = createEngine();
                engine.start();
                LOGGER.info("[{}] inference engine healthy on port {}", MindCraftMod.MOD_ID, engine.port());
                MindCraftAgent.start(engine);
                LOGGER.info("[{}] companion agent online", MindCraftMod.MOD_ID);
            } catch (EngineException e) {
                // Never crash the game over the assistant: log loudly and run degraded.
                LOGGER.error("[{}] failed to start inference engine; mod runs degraded", MindCraftMod.MOD_ID, e);
                engine = null;
            }
        }, "mindcraft-engine");
        engineThread.setDaemon(true);
        engineThread.start();
        LOGGER.info("[{}] client initialized", MindCraftMod.MOD_ID);
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
                .port(0)
                .threads(Runtime.getRuntime().availableProcessors() >= 8 ? 4 : 2)
                .contextSize(8192)
                .extraArgs(List.of("--jinja"))
                .build());
    }
}
