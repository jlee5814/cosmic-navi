package server.bots;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A small JSON reader for files the bot tooling writes (the learned grind target model), so the server needs
 * no JSON dependency. Objects become {@code Map<String, Object>}, arrays {@code List<Object>}, numbers
 * {@code Double}, plus {@code String}, {@code Boolean} and null. Accepts the {@code NaN}, {@code Infinity} and
 * {@code -Infinity} tokens Python's json module writes for undefined metrics; anything else malformed throws.
 */
final class BotJson {
    private final String s;
    private int i;

    private BotJson(String s) {
        this.s = s;
    }

    static Object parse(String text) {
        BotJson p = new BotJson(text);
        Object v = p.value();
        p.skipSpace();
        if (p.i != p.s.length()) {
            throw p.error("trailing text");
        }
        return v;
    }

    private Object value() {
        skipSpace();
        if (i >= s.length()) {
            throw error("unexpected end");
        }
        char c = s.charAt(i);
        return switch (c) {
            case '{' -> object();
            case '[' -> array();
            case '"' -> string();
            case 't' -> literal("true", Boolean.TRUE);
            case 'f' -> literal("false", Boolean.FALSE);
            case 'n' -> literal("null", null);
            case 'N' -> literal("NaN", Double.NaN);
            case 'I' -> literal("Infinity", Double.POSITIVE_INFINITY);
            default -> number();
        };
    }

    private Map<String, Object> object() {
        Map<String, Object> out = new LinkedHashMap<>();
        i++;
        skipSpace();
        if (peek() == '}') {
            i++;
            return out;
        }
        while (true) {
            skipSpace();
            if (peek() != '"') {
                throw error("expected a key");
            }
            String key = string();
            skipSpace();
            expect(':');
            out.put(key, value());
            skipSpace();
            char c = next();
            if (c == '}') {
                return out;
            }
            if (c != ',') {
                throw error("expected , or }");
            }
        }
    }

    private List<Object> array() {
        List<Object> out = new ArrayList<>();
        i++;
        skipSpace();
        if (peek() == ']') {
            i++;
            return out;
        }
        while (true) {
            out.add(value());
            skipSpace();
            char c = next();
            if (c == ']') {
                return out;
            }
            if (c != ',') {
                throw error("expected , or ]");
            }
        }
    }

    private String string() {
        expect('"');
        StringBuilder sb = new StringBuilder();
        while (true) {
            char c = next();
            if (c == '"') {
                return sb.toString();
            }
            if (c != '\\') {
                sb.append(c);
                continue;
            }
            char e = next();
            switch (e) {
                case '"', '\\', '/' -> sb.append(e);
                case 'b' -> sb.append('\b');
                case 'f' -> sb.append('\f');
                case 'n' -> sb.append('\n');
                case 'r' -> sb.append('\r');
                case 't' -> sb.append('\t');
                case 'u' -> {
                    if (i + 4 > s.length()) {
                        throw error("short unicode escape");
                    }
                    sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                    i += 4;
                }
                default -> throw error("bad escape");
            }
        }
    }

    private Double number() {
        if (s.startsWith("-Infinity", i)) {
            i += "-Infinity".length();
            return Double.NEGATIVE_INFINITY;
        }
        int start = i;
        while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) {
            i++;
        }
        String t = s.substring(start, i);
        if (t.isEmpty()) {
            throw error("unexpected character");
        }
        try {
            return Double.parseDouble(t);
        } catch (NumberFormatException e) {
            throw error("bad number " + t);
        }
    }

    private Object literal(String word, Object v) {
        if (!s.startsWith(word, i)) {
            throw error("expected " + word);
        }
        i += word.length();
        return v;
    }

    private void skipSpace() {
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
            i++;
        }
    }

    private char peek() {
        return i < s.length() ? s.charAt(i) : '\0';
    }

    private char next() {
        if (i >= s.length()) {
            throw error("unexpected end");
        }
        return s.charAt(i++);
    }

    private void expect(char c) {
        if (next() != c) {
            throw error("expected " + c);
        }
    }

    private IllegalArgumentException error(String what) {
        return new IllegalArgumentException("json: " + what + " at " + i);
    }
}
