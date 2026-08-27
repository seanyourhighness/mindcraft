package net.mindcraft.core.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link EngineConfig} and {@link GenOptions} defaults and builder sanity. */
class ConfigTest {

    private static EngineConfig minimalConfig() {
        return EngineConfig.builder()
                .modelPath(Path.of("/tmp/model.gguf"))
                .serverBinary(Path.of("/tmp/llama-server"))
                .build();
    }

    @Test
    void defaultsAreSane() {
        EngineConfig c = minimalConfig();
        assertEquals(Path.of("/tmp/model.gguf"), c.modelPath());
        assertEquals("127.0.0.1", c.host());
        assertEquals(0, c.port(), "port 0 = auto-pick a free port");
        assertEquals(4, c.threads());
        assertEquals(8192, c.contextSize());
        assertEquals(Path.of("/tmp/llama-server"), c.serverBinary());
        assertTrue(c.extraArgs().isEmpty(), "no extra args by default");
    }

    @Test
    void builderOverridesEverything() {
        EngineConfig c = EngineConfig.builder()
                .modelPath(Path.of("/m"))
                .host("0.0.0.0")
                .port(12345)
                .threads(8)
                .contextSize(4096)
                .serverBinary(Path.of("/s"))
                .extraArgs(List.of("--no-mmap", "--parallel", "1"))
                .build();
        assertEquals(Path.of("/m"), c.modelPath());
        assertEquals("0.0.0.0", c.host());
        assertEquals(12345, c.port());
        assertEquals(8, c.threads());
        assertEquals(4096, c.contextSize());
        assertEquals(Path.of("/s"), c.serverBinary());
        assertEquals(List.of("--no-mmap", "--parallel", "1"), c.extraArgs());
    }

    @Test
    void modelPathIsRequired() {
        assertThrows(NullPointerException.class,
                () -> EngineConfig.builder().serverBinary(Path.of("/s")).build());
    }

    @Test
    void serverBinaryIsRequired() {
        assertThrows(NullPointerException.class,
                () -> EngineConfig.builder().modelPath(Path.of("/m")).build());
    }

    @Test
    void invalidValuesAreRejected() {
        EngineConfig.Builder b = EngineConfig.builder()
                .modelPath(Path.of("/m"))
                .serverBinary(Path.of("/s"));
        assertThrows(IllegalArgumentException.class, () -> b.port(-1).build());
        assertThrows(IllegalArgumentException.class, () -> b.threads(0).build());
        assertThrows(IllegalArgumentException.class, () -> b.contextSize(0).build());
    }

    @Test
    void extraArgsAreCopiedDefensively() {
        List<String> mutable = new ArrayList<>(List.of("--x"));
        EngineConfig c = EngineConfig.builder()
                .modelPath(Path.of("/m"))
                .serverBinary(Path.of("/s"))
                .extraArgs(mutable)
                .build();
        mutable.add("--y");
        assertEquals(List.of("--x"), c.extraArgs(), "later mutation of the source list must not leak in");
    }

    @Test
    void genOptionsDefaults() {
        GenOptions o = new GenOptions();
        assertEquals(120, o.maxTokens());
        assertTrue(o.temperature() >= 0.0);
        assertEquals(null, o.seed());
    }

    @Test
    void genOptionsExplicit() {
        GenOptions o = new GenOptions(30, 0.2, 42L, null);
        assertEquals(30, o.maxTokens());
        assertEquals(0.2, o.temperature());
        assertEquals(42L, o.seed());
    }

    @Test
    void genOptionsRejectsInvalidMaxTokens() {
        assertThrows(IllegalArgumentException.class, () -> new GenOptions(0, 0.7, null, null));
        assertThrows(IllegalArgumentException.class, () -> new GenOptions(-5, 0.7, null, null));
    }
}
