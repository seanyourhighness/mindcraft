package net.clankerjockey.core.events;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ProactivePolicyTest {

    @Test
    void cooldownGatesAmbientSpeech() {
        ProactivePolicy policy = new ProactivePolicy(60_000L);
        long now = 1_000_000L;

        assertTrue(policy.shouldTrigger(now), "first proactive turn must be allowed");
        assertFalse(policy.shouldTrigger(now + 30_000L), "second within the interval must wait");
        assertTrue(policy.shouldTrigger(now + 61_000L), "after the interval a new turn is allowed");
    }
}
