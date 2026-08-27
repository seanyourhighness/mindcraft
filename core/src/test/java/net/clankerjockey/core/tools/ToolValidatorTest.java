package net.clankerjockey.core.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ToolValidatorTest {

    private final ToolValidator validator = new ToolValidator();

    private static final ToolDefinition DEF = new ToolDefinition(
            "test_tool",
            "A test tool.",
            List.of(
                    new ParamSpec("name", ParamType.STRING, "A name.", true, null, null, null, null),
                    new ParamSpec("count", ParamType.INTEGER, "A count.", true, 1d, 10d, null, null),
                    new ParamSpec("mode", ParamType.STRING, "A mode.", false, null, null, List.of("fast", "slow"), "fast"),
                    new ParamSpec("ratio", ParamType.NUMBER, "A ratio.", false, 0d, 1d, null, 0.5)),
            true, false, java.time.Duration.ZERO, SecurityClass.QUERY);

    @Test
    void validCallIsNormalizedWithDefaults() {
        ToolCall call = new ToolCall("test_tool", Map.of("name", "Sean", "count", 3L));
        ValidationResult r = validator.validate(call, DEF);

        assertTrue(r.valid(), () -> "expected valid, got " + r.issues());
        assertEquals("Sean", r.normalizedArguments().get("name"));
        assertEquals(3L, r.normalizedArguments().get("count"));
        assertEquals("fast", r.normalizedArguments().get("mode"), "default must be applied");
        assertEquals(0.5, r.normalizedArguments().get("ratio"), "default must be applied");
    }

    @Test
    void missingRequiredRejected() {
        ValidationResult r = validator.validate(new ToolCall("test_tool", Map.of("count", 3L)), DEF);
        assertFalse(r.valid());
        assertTrue(r.issues().get(0).contains("name"));
    }

    @Test
    void unknownArgumentRejected() {
        ValidationResult r = validator.validate(
                new ToolCall("test_tool", Map.of("name", "x", "count", 3L, "bogus", 1L)), DEF);
        assertFalse(r.valid());
        assertTrue(r.issues().get(0).contains("bogus"));
    }

    @Test
    void wrongTypeRejected() {
        ValidationResult r = validator.validate(
                new ToolCall("test_tool", Map.of("name", 42L, "count", 3L)), DEF);
        assertFalse(r.valid());
    }

    @Test
    void outOfRangeRejected() {
        ValidationResult r = validator.validate(
                new ToolCall("test_tool", Map.of("name", "x", "count", 11L)), DEF);
        assertFalse(r.valid());
        assertTrue(r.issues().get(0).contains("range"));
    }

    @Test
    void disallowedValueRejected() {
        ValidationResult r = validator.validate(
                new ToolCall("test_tool", Map.of("name", "x", "count", 3L, "mode", "warp")), DEF);
        assertFalse(r.valid());
        assertTrue(r.issues().get(0).contains("mode"));
    }

    @Test
    void numberAcceptsIntegralValues() {
        ValidationResult r = validator.validate(
                new ToolCall("test_tool", Map.of("name", "x", "count", 3L, "ratio", 1L)), DEF);
        assertTrue(r.valid(), () -> "integral JSON numbers must satisfy NUMBER params: " + r.issues());
    }
}
