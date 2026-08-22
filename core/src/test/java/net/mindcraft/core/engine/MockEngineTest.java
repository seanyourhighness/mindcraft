package net.mindcraft.core.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Proves the {@link InferenceBackend} interface lets downstream code swap in a
 * stub backend (no llama-server process needed) and that generate() calls are
 * delegated through the interface.
 */
class MockEngineTest {

    /** Recording stub used in place of a real engine for downstream tests. */
    static final class StubBackend implements InferenceBackend {
        final List<String> prompts = new ArrayList<>();
        final List<GenOptions> options = new ArrayList<>();
        String canned = "stubbed reply";
        boolean stopped;
        boolean running = true;

        @Override
        public String generate(String prompt, GenOptions opts) {
            prompts.add(prompt);
            options.add(opts);
            return canned;
        }

        @Override
        public boolean isRunning() {
            return running;
        }

        @Override
        public void stop() {
            stopped = true;
            running = false;
        }
    }

    @Test
    void generateDelegatesThroughInterface() throws Exception {
        InferenceBackend backend = new StubBackend();
        GenOptions opts = new GenOptions(50, 0.5, 7L);

        String result = backend.generate("hello there", opts);

        assertEquals("stubbed reply", result);
        StubBackend stub = (StubBackend) backend;
        assertEquals(List.of("hello there"), stub.prompts, "prompt must reach the backend");
        assertEquals(List.of(opts), stub.options, "options must reach the backend");
    }

    @Test
    void inferenceEngineImplementsBackend() {
        assertTrue(InferenceBackend.class.isAssignableFrom(InferenceEngine.class),
                "InferenceEngine must be usable wherever an InferenceBackend is expected");
    }

    @Test
    void stubSupportsLifecycleQueries() {
        StubBackend b = new StubBackend();
        assertTrue(b.isRunning());
        b.stop();
        assertFalse(b.isRunning());
        assertTrue(b.stopped);
    }

    @Test
    void closeDelegatesToStop() {
        StubBackend b = new StubBackend();
        b.close();
        assertTrue(b.stopped, "close() must stop the backend");
    }
}
