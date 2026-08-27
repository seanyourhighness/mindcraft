package net.clankerjockey.core.engine;

import net.clankerjockey.core.tools.ParamSpec;
import net.clankerjockey.core.tools.ToolDefinition;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Generates a llama.cpp GBNF grammar that constrains the model to emit exactly
 * one structured tool call:
 *
 * <pre>{"tool": "&lt;name&gt;", "arguments": {&lt;that tool's params&gt;}}</pre>
 *
 * <p>Every model output is therefore a tool call (including the synthetic
 * {@code respond} tool for final natural-language answers). This replaces the
 * old hand-written eval grammar with a schema-driven generator, so adding a
 * tool automatically extends the constraint.</p>
 *
 * <p>Constraints from the local llama.cpp build: rule names must be camelCase
 * (underscores rejected), while quoted literals may contain underscores. All
 * generated rule names are prefixed and sanitized accordingly.</p>
 *
 * <p>Verified against the fork's grammar sampler (commit d775b89): tool-name
 * literals must include the escaped surrounding quotes
 * ({@code "\\\"follow_player\\\""}), otherwise the grammar matches the bare
 * name and the model emits invalid JSON like {@code {"tool": follow_player}}.
 * Tool rules are emitted flat under {@code root}, matching the proven eval
 * grammar shape. See {@code docs/eval/grammar_bisect.py} for the evidence.</p>
 */
public final class ToolGrammar {

    /** Name of the synthetic final-answer tool used by the agent loop. */
    public static final String RESPOND_TOOL = "respond";

    private ToolGrammar() {
    }

    /**
     * Build the full GBNF grammar for the given tool definitions plus the
     * {@code respond} pseudo-tool.
     */
    public static String generate(List<ToolDefinition> tools) {
        List<ToolDefinition> defs = new ArrayList<>();
        defs.add(respondDefinition());
        if (tools != null) {
            for (ToolDefinition t : tools) {
                if (t != null && !RESPOND_TOOL.equals(t.name())) {
                    defs.add(t);
                }
            }
        }

        StringBuilder sb = new StringBuilder(2048);
        sb.append("# Clanker Jockey tool-call grammar (auto-generated; do not edit)\n");

        List<String> toolRules = new ArrayList<>();
        Set<String> usedRuleNames = new HashSet<>();
        for (ToolDefinition def : defs) {
            String toolRule = uniqueRule("mcTool" + toCamel(def.name()), usedRuleNames);
            toolRules.add(toolRule);
            sb.append(toolRule).append(" ::= ").append(toolBody(def, usedRuleNames)).append('\n');
        }

        sb.append("root ::= ").append(String.join(" | ", toolRules)).append('\n');
        appendSharedRules(sb);
        return sb.toString();
    }

    private static ToolDefinition respondDefinition() {
        return new ToolDefinition(
                RESPOND_TOOL,
                "Speak to the player in character. Use this as your final message.",
                List.of(new ParamSpec("text", net.clankerjockey.core.tools.ParamType.STRING,
                        "What you say, 1-2 sentences, fully in character.", true,
                        null, null, null, null)),
                true, false, java.time.Duration.ZERO,
                net.clankerjockey.core.tools.SecurityClass.SYSTEM);
    }

    /** One tool rule with the full JSON object inline (flat under root). */
    private static String toolBody(ToolDefinition def, Set<String> usedRuleNames) {
        StringBuilder sb = new StringBuilder();
        // Tool name literal must include the escaped quotes around the name:
        // "\"follow_player\"" so the grammar matches {"tool": "follow_player"}.
        sb.append("\"{\" mcWs \"\\\"tool\\\"\" mcWs \":\" mcWs ")
                .append("\"\\\"").append(escape(def.name())).append("\\\"\"");
        sb.append(" mcWs \",\" mcWs \"\\\"arguments\\\"\" mcWs \":\" mcWs \"{\" mcWs");
        List<ParamSpec> params = def.parameters();
        boolean first = true;
        for (int idx = 0; idx < params.size(); idx++) {
            ParamSpec p = params.get(idx);
            String entry = "\"\\\"" + escape(p.name()) + "\\\"\" mcWs \":\" mcWs " + typeRule(p, usedRuleNames);
            if (!p.required() && idx > 0) {
                // Optional trailing params: allow the whole entry to be omitted.
                sb.append(" (\",\" mcWs ").append(entry).append(")?");
            } else {
                if (!first) {
                    sb.append(" \",\" mcWs");
                }
                sb.append(' ').append(entry);
            }
            first = false;
        }
        // close the arguments object and the tool-call object
        sb.append(" mcWs \"}\" mcWs \"}\"");
        return sb.toString();
    }

    private static String typeRule(ParamSpec p, Set<String> usedRuleNames) {
        if (p.allowedValues() != null && !p.allowedValues().isEmpty()) {
            List<String> lits = new ArrayList<>();
            for (String v : p.allowedValues()) {
                lits.add(lit(v));
            }
            return "(" + String.join(" | ", lits) + ")";
        }
        return switch (p.type()) {
            case STRING -> "mcString";
            case INTEGER -> "mcInteger";
            case NUMBER -> "mcNumber";
            case BOOLEAN -> "mcBoolean";
        };
    }

    private static void appendSharedRules(StringBuilder sb) {
        sb.append("mcString ::= \"\\\"\" ( [^\"\\\\] | \"\\\\\" ([\"\\\\/bfnrt] | \"u\" [0-9a-fA-F] [0-9a-fA-F] [0-9a-fA-F] [0-9a-fA-F]) )* \"\\\"\"\n");
        sb.append("mcNumber ::= \"-\"? mcInt (\".\" [0-9]*)?\n");
        sb.append("mcInteger ::= \"-\"? mcInt\n");
        sb.append("mcBoolean ::= \"true\" | \"false\"\n");
        sb.append("mcInt ::= \"0\" | [1-9] [0-9]*\n");
        sb.append("mcWs ::= [ \\t\\n]*\n");
    }

    private static String uniqueRule(String base, Set<String> used) {
        String candidate = base;
        int n = 2;
        while (!used.add(candidate)) {
            candidate = base + n++;
        }
        return candidate;
    }

    private static String toCamel(String s) {
        StringBuilder sb = new StringBuilder();
        boolean upper = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '_' || c == '-' || c == ' ') {
                upper = true;
            } else if (Character.isLetterOrDigit(c)) {
                sb.append(upper ? Character.toUpperCase(c) : c);
                upper = false;
            }
        }
        if (sb.length() == 0) {
            return "Tool";
        }
        sb.setCharAt(0, Character.toUpperCase(sb.charAt(0)));
        if (Character.isDigit(sb.charAt(0))) {
            sb.insert(0, 'T');
        }
        return sb.toString();
    }

    private static String lit(String s) {
        return "\"" + escape(s) + "\"";
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
