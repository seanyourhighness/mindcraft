package net.clankerjockey.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** Trivial sanity test proving the core module test rig works. */
class SanityTest {

    @Test
    void sanity() {
        assertEquals(2, 1 + 1, "sanity check");
    }
}
