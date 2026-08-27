package net.mindcraft.mod;

import net.mindcraft.core.engine.EngineConfig;
import net.mindcraft.core.engine.EngineException;
import net.mindcraft.core.engine.GenOptions;
import net.mindcraft.core.engine.InferenceEngine;
import net.mindcraft.core.engine.SttConfig;
import net.mindcraft.core.engine.SttEngine;
import net.mindcraft.core.engine.TtsConfig;
import net.mindcraft.core.engine.TtsEngine;
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
 * MindCraft main entrypoint (Forge). On client setup, spawns llama-server,
 * PocketTTS.cpp, and whisper-server as child processes of the game JVM and
 * keeps them ready. Each sidecar is resolved from {@code <game dir>/mindcraft/}
 * and started degraded-safe: a missing bundle logs a warning and that feature
 * runs disabled, never crashing the game.
 */
@Mod(MindCraftMod.MOD_ID)
public class MindCraftMod {

    public static final String MOD_ID = "mindcraft";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static volatile InferenceEngine engine;
    private static volatile TtsEngine tts;
    private static volatile SttEngine stt;
    private static volatile String ttsVoice = "jo.wav";

    public MindCraftMod() {
        FMLJavaModLoadingContext.get().getModEventBus().register(this);
    }

    @SubscribeEvent
    public void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            try {
                engine = createEngine();
                engine.start();
                LOGGER.info("[{}] inference engine healthy on port {}", MOD_ID, engine.port());
            } catch (EngineException e) {
                // Never crash the game over the assistant: log loudly and run degraded.
                LOGGER.error("[{}] failed to start inference engine; mod runs degraded", MOD_ID, e);
                engine = null;
            }
            startTts();
            startStt();
        });
    }

    /**
     * Start the PocketTTS.cpp sidecar. Independent of the inference engine:
     * a missing TTS bundle degrades to text-only chat, never a crash.
     */
    private void startTts() {
        try {
            tts = createTts();
            tts.start();
            LOGGER.info("[{}] TTS engine healthy on port {} (voice={})", MOD_ID, tts.port(), ttsVoice);
        } catch (EngineException e) {
            LOGGER.warn("[{}] TTS unavailable; chat stays text-only ({}: {})",
                    MOD_ID, e.getClass().getSimpleName(), e.getMessage());
            tts = null;
        }
    }

    private static TtsEngine createTts() throws EngineException {
        Path gameDir = Path.of(".").toAbsolutePath();
        String os = System.getProperty("os.name").toLowerCase();
        String binaryName = os.contains("win") ? "pocket-tts.exe" : "pocket-tts";
        Path binary = gameDir.resolve("mindcraft/tts/bin/" + binaryName);
        Path modelsDir = gameDir.resolve("mindcraft/tts/models");
        Path voicesDir = gameDir.resolve("mindcraft/tts/voices");

        if (!Files.isExecutable(binary)) {
            throw new EngineException("pocket-tts not found at " + binary
                    + " - unpack the MindCraft TTS bundle into the game directory");
        }
        if (!Files.isRegularFile(modelsDir.resolve("tokenizer.model"))) {
            throw new EngineException("TTS models not found at " + modelsDir
                    + " - unpack the MindCraft TTS bundle into the game directory");
        }

        return new TtsEngine(TtsConfig.builder()
                .binary(binary)
                .modelsDir(modelsDir)
                .voicesDir(voicesDir)
                .port(0) // auto-pick free port, loopback only
                .threads(Runtime.getRuntime().availableProcessors() >= 8 ? 4 : 2)
                .precision("int8")
                .lsdSteps(1)
                .build());
    }

    /**
     * Start the whisper.cpp sidecar. Independent of the inference engine and
     * TTS: a missing STT bundle degrades to text-only input, never a crash.
     */
    private void startStt() {
        try {
            stt = createStt();
            stt.start();
            LOGGER.info("[{}] STT engine healthy on port {}", MOD_ID, stt.port());
        } catch (EngineException e) {
            LOGGER.warn("[{}] STT unavailable; voice input disabled ({}: {})",
                    MOD_ID, e.getClass().getSimpleName(), e.getMessage());
            stt = null;
        }
    }

    private static SttEngine createStt() throws EngineException {
        Path gameDir = Path.of(".").toAbsolutePath();
        String os = System.getProperty("os.name").toLowerCase();
        String binaryName = os.contains("win") ? "whisper-server.exe" : "whisper-server";
        Path binary = gameDir.resolve("mindcraft/stt/bin/" + binaryName);
        Path model = gameDir.resolve("mindcraft/stt/models/ggml-small.en.bin");

        if (!Files.isExecutable(binary)) {
            throw new EngineException("whisper-server not found at " + binary
                    + " - unpack the MindCraft STT bundle into the game directory");
        }
        if (!Files.isRegularFile(model)) {
            throw new EngineException("whisper model not found at " + model
                    + " - unpack the MindCraft STT bundle into the game directory");
        }

        return new SttEngine(SttConfig.builder()
                .binary(binary)
                .modelPath(model)
                .port(0) // auto-pick free port, loopback only
                .threads(Runtime.getRuntime().availableProcessors() >= 8 ? 4 : 2)
                .language("en")
                .build());
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

    /** Current TTS engine instance, or null if the TTS bundle is absent. */
    public static TtsEngine tts() {
        return tts;
    }

    /**
     * Speak a line in the configured cloned voice. Returns the WAV bytes, or
     * {@code null} when TTS is unavailable (caller falls back to text-only).
     * Never throws: a TTS failure must not break chat.
     */
    public static byte[] speak(String text) {
        TtsEngine t = tts;
        if (t == null || !t.isRunning() || text == null || text.isBlank()) {
            return null;
        }
        try {
            return t.speakWav(text, ttsVoice);
        } catch (EngineException e) {
            LOGGER.warn("[{}] TTS synthesis failed; line stays text-only", MOD_ID, e);
            return null;
        }
    }

    /** Select the reference voice sample (inside the TTS voices dir). */
    public static void setTtsVoice(String voice) {
        ttsVoice = voice;
    }

    /**
     * Synthesize a line in the cloned voice AND play it in-game (client
     * thread only). Returns true if audio was queued for playback. This is
     * the full TTS loop: {@code reply -> speak -> play}.
     */
    public static boolean speakAndPlay(String text) {
        byte[] wav = speak(text);
        if (wav == null) {
            return false;
        }
        return TtsAudioPlayer.play(wav, 1.0f, 1.0f);
    }

    /**
     * Start continuous microphone capture. On each detected speech clip the
     * full voice loop runs: STT transcribes, the LLM replies, and the reply
     * is spoken in the cloned voice. No-op (returns false) if no mic or no
     * STT engine is available. Must be called on the client thread.
     *
     * <p>This is the immersive entry point: the player talks to the mic and
     * Vera answers out loud — no typing, no UI.
     */
    public static boolean startVoiceLoop() {
        SttEngine s = stt;
        if (s == null || !s.isRunning()) {
            LOGGER.warn("[{}] voice loop unavailable: STT engine not running", MOD_ID);
            return false;
        }
        VoiceCapture capture = new VoiceCapture();
        boolean started = capture.start(wav -> {
            String text;
            try {
                text = s.transcribe(wav, "mic.wav");
            } catch (EngineException e) {
                LOGGER.warn("[{}] STT failed on mic clip", MOD_ID, e);
                return;
            }
            if (text == null || text.isBlank()) {
                return; // silence / no speech
            }
            LOGGER.info("[{}] heard: {}", MOD_ID, text);
            String reply;
            try {
                reply = generate(text);
            } catch (EngineException e) {
                LOGGER.warn("[{}] LLM reply failed", MOD_ID, e);
                return;
            }
            speakAndPlay(reply);
        });
        if (!started) {
            LOGGER.warn("[{}] no microphone available; voice loop disabled", MOD_ID);
        }
        return started;
    }

    /** Current STT engine instance, or null if the STT bundle is absent. */
    public static SttEngine stt() {
        return stt;
    }

    /**
     * Transcribe 16 kHz mono audio (WAV or raw PCM) to text. Returns the
     * transcript, or {@code null} when STT is unavailable or it fails.
     * Never throws: an STT failure must not break chat.
     */
    public static String transcribe(byte[] audio, String fileName) {
        SttEngine s = stt;
        if (s == null || !s.isRunning() || audio == null || audio.length == 0) {
            return null;
        }
        try {
            return s.transcribe(audio, fileName);
        } catch (EngineException e) {
            LOGGER.warn("[{}] STT transcription failed", MOD_ID, e);
            return null;
        }
    }
}
