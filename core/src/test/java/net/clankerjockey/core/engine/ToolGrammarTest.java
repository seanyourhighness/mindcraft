package net.clankerjockey.core.engine;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.clankerjockey.core.tools.CoreTools;
import net.clankerjockey.core.tools.ToolRegistry;
import org.junit.jupiter.api.Test;

class ToolGrammarTest {

    @Test
    void grammarContainsAllToolNamesAndRespond() {
        ToolRegistry reg = new ToolRegistry();
        reg.registerAll(CoreTools.all());
        String g = ToolGrammar.generate(reg.definitions());

        assertTrue(g.contains("root ::= mcToolRespond"),
                "root must directly alternate flat per-tool rules");
        assertTrue(g.contains("\\\"respond\\\""),
                "tool-name literal must include escaped quotes so the grammar matches {\"tool\": \"respond\"}");
        for (String name : reg.names()) {
            assertTrue(g.contains("\\\"" + name + "\\\""), "grammar must quote tool name " + name);
        }
    }

    @Test
    void ruleNamesAreCamelCaseWithoutUnderscores() {
        ToolRegistry reg = new ToolRegistry();
        reg.registerAll(CoreTools.all());
        String g = ToolGrammar.generate(reg.definitions());

        // Every GBNF rule declaration must have a camelCase name (no underscores).
        Pattern rule = Pattern.compile("(?m)^([a-zA-Z0-9]+) ::=");
        Matcher m = rule.matcher(g);
        assertTrue(m.find(), "grammar must declare rules");
        int count = 0;
        while (m.find()) {
            String name = m.group(1);
            assertFalse(name.contains("_"), "rule name must be camelCase, got " + name);
            count++;
        }
        assertTrue(count >= 10, "expected many rules, got " + count);
    }

    @Test
    void respondArgumentsAllowFreeText() {
        String g = ToolGrammar.generate(List.of());
        assertTrue(g.contains("\\\"text\\\""), "respond args must reference the text param");
        assertTrue(g.contains("mcString"));
    }
}
