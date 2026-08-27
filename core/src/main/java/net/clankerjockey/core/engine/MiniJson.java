package net.clankerjockey.core.engine;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal recursive-descent JSON parser using only the JDK. Parses into nested
 * {@link Map}/{@link List}/String/Double/Long/Boolean/null trees. Not a
 * general-purpose library: duplicate keys resolve to the last value, and
 * numbers parse as Long or Double. Used to decode llama-server HTTP responses
 * without adding a runtime dependency.
 */
public final class MiniJson {

    private MiniJson() {
    }

    /** Parse the whole text as one JSON value. */
    public static Object parse(String text) {
        Parser p = new Parser(text);
        Object value = p.parseValue();
        p.skipWs();
        if (!p.atEnd()) {
            throw new IllegalArgumentException("trailing content at offset " + p.i);
        }
        return value;
    }

    /**
     * Navigate a dotted/bracketed path such as {@code "choices[0].message.content"}.
     * Returns {@code null} when any segment is absent.
     */
    public static Object at(Object tree, String path) {
        Object cur = tree;
        int n = path.length();
        int start = 0;
        while (start < n) {
            while (start < n && path.charAt(start) == '.') {
                start++;
            }
            if (start >= n) {
                break;
            }
            int end = start;
            while (end < n && path.charAt(end) != '.') {
                end++;
            }
            String seg = path.substring(start, end);
            int bracket = seg.indexOf('[');
            String key = bracket < 0 ? seg : seg.substring(0, bracket);
            if (!(cur instanceof Map<?, ?> map) || !map.containsKey(key)) {
                return null;
            }
            cur = map.get(key);
            if (bracket >= 0) {
                int idx = bracket;
                while (idx < seg.length()) {
                    int close = seg.indexOf(']', idx);
                    if (close < 0) {
                        return null;
                    }
                    int i;
                    try {
                        i = Integer.parseInt(seg.substring(idx + 1, close));
                    } catch (NumberFormatException e) {
                        return null;
                    }
                    if (!(cur instanceof List<?> list) || i < 0 || i >= list.size()) {
                        return null;
                    }
                    cur = list.get(i);
                    idx = close + 1;
                }
            }
            start = end;
        }
        return cur;
    }

    /** Navigate a path and return the value as a String, or {@code null} if absent/not a string. */
    public static String stringAt(Object tree, String path) {
        Object v = at(tree, path);
        return v instanceof String s ? s : null;
    }

    /** Minimal JSON serializer for request bodies (maps, lists, strings,
     *  numbers, booleans, null). Strings are escaped per RFC 8259. */
    public static String stringify(Object v) {
        StringBuilder sb = new StringBuilder();
        writeValue(sb, v);
        return sb.toString();
    }

    private static void writeValue(StringBuilder sb, Object v) {
        if (v == null) {
            sb.append("null");
        } else if (v instanceof String s) {
            writeString(sb, s);
        } else if (v instanceof Boolean || v instanceof Long || v instanceof Integer) {
            sb.append(v);
        } else if (v instanceof Double d) {
            sb.append(Double.toString(d));
        } else if (v instanceof Map<?, ?> m) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                writeString(sb, String.valueOf(e.getKey()));
                sb.append(':');
                writeValue(sb, e.getValue());
            }
            sb.append('}');
        } else if (v instanceof List<?> l) {
            sb.append('[');
            for (int i = 0; i < l.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                writeValue(sb, l.get(i));
            }
            sb.append(']');
        } else {
            throw new IllegalArgumentException("unsupported JSON type: " + v.getClass());
        }
    }

    private static void writeString(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }

    private static final class Parser {
        private final String s;
        private int i;

        Parser(String s) {
            this.s = s;
        }

        boolean atEnd() {
            return i >= s.length();
        }

        void skipWs() {
            while (i < s.length()) {
                char c = s.charAt(i);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                    i++;
                } else {
                    break;
                }
            }
        }

        Object parseValue() {
            skipWs();
            if (atEnd()) {
                throw new IllegalArgumentException("unexpected end of JSON");
            }
            return switch (s.charAt(i)) {
                case '{' -> parseObject();
                case '[' -> parseArray();
                case '"' -> parseString();
                case 't' -> literal("true") ? Boolean.TRUE : fail("bad literal at offset " + i);
                case 'f' -> literal("false") ? Boolean.FALSE : fail("bad literal at offset " + i);
                case 'n' -> literal("null") ? null : fail("bad literal at offset " + i);
                default -> parseNumber();
            };
        }

        Map<String, Object> parseObject() {
            i++; // consume '{'
            Map<String, Object> m = new LinkedHashMap<>();
            skipWs();
            if (peek() == '}') {
                i++;
                return m;
            }
            while (true) {
                skipWs();
                if (atEnd() || s.charAt(i) != '"') {
                    throw new IllegalArgumentException("expected string key at offset " + i);
                }
                String k = parseString();
                skipWs();
                if (atEnd() || s.charAt(i) != ':') {
                    throw new IllegalArgumentException("expected ':' at offset " + i);
                }
                i++;
                m.put(k, parseValue());
                skipWs();
                if (atEnd()) {
                    throw new IllegalArgumentException("unterminated object");
                }
                char c = s.charAt(i);
                if (c == ',') {
                    i++;
                } else if (c == '}') {
                    i++;
                    return m;
                } else {
                    throw new IllegalArgumentException("expected ',' or '}' at offset " + i);
                }
            }
        }

        List<Object> parseArray() {
            i++; // consume '['
            List<Object> l = new ArrayList<>();
            skipWs();
            if (peek() == ']') {
                i++;
                return l;
            }
            while (true) {
                l.add(parseValue());
                skipWs();
                if (atEnd()) {
                    throw new IllegalArgumentException("unterminated array");
                }
                char c = s.charAt(i);
                if (c == ',') {
                    i++;
                } else if (c == ']') {
                    i++;
                    return l;
                } else {
                    throw new IllegalArgumentException("expected ',' or ']' at offset " + i);
                }
            }
        }

        String parseString() {
            i++; // consume opening quote
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (atEnd()) {
                    throw new IllegalArgumentException("unterminated string");
                }
                char c = s.charAt(i++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c != '\\') {
                    sb.append(c);
                    continue;
                }
                if (atEnd()) {
                    throw new IllegalArgumentException("unterminated escape");
                }
                char e = s.charAt(i++);
                switch (e) {
                    case '"' -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    case '/' -> sb.append('/');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case 'u' -> {
                        if (i + 4 > s.length()) {
                            throw new IllegalArgumentException("bad \\u escape at offset " + i);
                        }
                        sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                        i += 4;
                    }
                    default -> throw new IllegalArgumentException("bad escape \\" + e + " at offset " + i);
                }
            }
        }

        Object parseNumber() {
            int start = i;
            while (i < s.length()) {
                char c = s.charAt(i);
                if (c == ',' || c == '}' || c == ']' || c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                    break;
                }
                i++;
            }
            String num = s.substring(start, i);
            try {
                if (num.contains(".") || num.contains("e") || num.contains("E")) {
                    return Double.parseDouble(num);
                }
                return Long.parseLong(num);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("bad number '" + num + "' at offset " + start);
            }
        }

        private boolean literal(String lit) {
            if (!s.startsWith(lit, i)) {
                return false;
            }
            i += lit.length();
            return true;
        }

        private Object fail(String message) {
            throw new IllegalArgumentException(message);
        }

        private char peek() {
            return atEnd() ? '\0' : s.charAt(i);
        }
    }
}
