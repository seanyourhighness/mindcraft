package net.clankerjockey.core.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Unit tests for the tiny JDK-only JSON parser used to decode llama-server responses. */
class MiniJsonTest {

    @Test
    void parsesScalars() {
        assertEquals(Map.of(), MiniJson.parse("{}"));
        assertEquals(List.of(), MiniJson.parse("[]"));
        assertEquals(Boolean.TRUE, MiniJson.parse("true"));
        assertEquals(Boolean.FALSE, MiniJson.parse("false"));
        assertNull(MiniJson.parse("null"));
        assertEquals(42L, MiniJson.parse("42"));
        assertEquals(0.7, MiniJson.parse("0.7"));
        assertEquals("hi", MiniJson.parse("\"hi\""));
    }

    @Test
    void parsesNestedStructure() {
        Object tree = MiniJson.parse("""
                {"a": {"b": [1, {"c": "deep"}, 3]}, "n": -2.5e1}
                """);
        assertEquals("deep", MiniJson.stringAt(tree, "a.b[1].c"));
        assertEquals(1L, MiniJson.at(tree, "a.b[0]"));
        assertEquals(3L, MiniJson.at(tree, "a.b[2]"));
        assertEquals(-25.0, MiniJson.at(tree, "n"));
        assertNull(MiniJson.at(tree, "a.b[5]"));
        assertNull(MiniJson.at(tree, "missing"));
    }

    @Test
    void decodesEscapes() {
        Object tree = MiniJson.parse("\"line1\\nline2 \\u0041 \\\"q\\\" \\\\ \\t\"");
        assertEquals("line1\nline2 A \"q\" \\ \t", tree);
    }

    @Test
    void llamaServerCompletionShape() {
        // Real response shape from llama-server /v1/chat/completions.
        String body = "{\"choices\":[{\"finish_reason\":\"stop\",\"index\":0,"
                + "\"message\":{\"role\":\"assistant\",\"content\":\"Hello, how can I assist you today?\"}}],"
                + "\"created\":1787361585,\"object\":\"chat.completion\"}";
        Object tree = MiniJson.parse(body);
        assertEquals("Hello, how can I assist you today?",
                MiniJson.stringAt(tree, "choices[0].message.content"));
    }

    @Test
    void rejectsMalformedInput() {
        assertThrows(IllegalArgumentException.class, () -> MiniJson.parse("{"));
        assertThrows(IllegalArgumentException.class, () -> MiniJson.parse("\"unterminated"));
        assertThrows(IllegalArgumentException.class, () -> MiniJson.parse("[1, 2"));
        assertThrows(IllegalArgumentException.class, () -> MiniJson.parse("{\"a\":1} trailing"));
        assertThrows(IllegalArgumentException.class, () -> MiniJson.parse("\"bad \\x escape\""));
    }
}
