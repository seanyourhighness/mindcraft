package net.mindcraft.core.agent;

import net.mindcraft.core.engine.GenOptions;
import net.mindcraft.core.engine.InferenceBackend;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;

/**
 * Test inference backend that returns scripted responses in order, recording
 * every prompt and option for assertions.
 */
public class ScriptedBackend implements InferenceBackend {

    private final Queue<String> responses = new ArrayDeque<>();
    public final List<String> prompts = new ArrayList<>();
    public final List<GenOptions> options = new ArrayList<>();
    public String fallback = "{\"tool\":\"respond\",\"arguments\":{\"text\":\"Done.\"}}";
    public boolean stopped;
    public boolean running = true;

    public ScriptedBackend(String... responses) {
        for (String r : responses) {
            this.responses.add(r);
        }
    }

    @Override
    public String generate(String prompt, GenOptions opts) {
        prompts.add(prompt);
        options.add(opts);
        return responses.isEmpty() ? fallback : responses.poll();
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
