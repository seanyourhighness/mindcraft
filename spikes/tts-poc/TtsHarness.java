import net.mindcraft.core.engine.EngineException;
import net.mindcraft.core.engine.TtsConfig;
import net.mindcraft.core.engine.TtsEngine;

import java.nio.file.Path;
import java.util.Arrays;

/**
 * Standalone harness: drives the real PocketTTS.cpp sidecar through the
 * production {@link TtsEngine} (Java 17, JDK-only) to prove the MindCraft
 * integration path end-to-end.
 */
public final class TtsHarness {
    public static void main(String[] args) throws Exception {
        Path binary = Path.of("/tmp/pocketcpp/pocket-tts");
        Path modelsDir = Path.of("/tmp/pocketcpp/models");
        Path voicesDir = Path.of("/tmp/tts-voices");

        TtsConfig cfg = TtsConfig.builder()
                .binary(binary)
                .modelsDir(modelsDir)
                .voicesDir(voicesDir)
                .port(0)
                .threads(8)
                .precision("int8")
                .lsdSteps(1)
                .build();

        TtsEngine engine = new TtsEngine(cfg);
        engine.start();
        System.out.println("[harness] TtsEngine healthy on port " + engine.port());

        String line = "Hey, I'm Vera. Welcome to the mine, partner. Grab your pickaxe and let's get digging.";

        long t0 = System.nanoTime();
        byte[] wav = engine.speakWav(line, "jo.wav");
        long t1 = System.nanoTime();
        boolean okWav = wav.length > 44 && wav[0] == 'R' && wav[1] == 'I' && wav[2] == 'F' && wav[3] == 'F';
        System.out.printf("[harness] speakWav: %d bytes, validRIFF=%b, %.0fms%n",
                wav.length, okWav, (t1 - t0) / 1_000_000.0);

        long t2 = System.nanoTime();
        byte[] pcm = engine.speakPcm(line, "jo.wav");
        long t3 = System.nanoTime();
        byte[] pcm16 = TtsEngine.f32leToPcm16(pcm);
        int seconds = pcm16.length / 2 / 24000;
        System.out.printf("[harness] speakPcm: %d f32 bytes -> %d pcm16 bytes (~%ds), %.0fms%n",
                pcm.length, pcm16.length, seconds, (t3 - t2) / 1_000_000.0);

        engine.stop();
        System.out.println("[harness] stopped, exitCode=" + engine.exitCode());
        System.out.println("[harness] " + (okWav ? "PASS" : "FAIL"));
        if (!okWav) System.exit(1);
    }
}
