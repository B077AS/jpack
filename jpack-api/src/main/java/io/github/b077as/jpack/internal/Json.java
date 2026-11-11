package io.github.b077as.jpack.internal;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A small JSON reader and writer, so that neither the library nor the Maven plugin needs a dependency for it.
 *
 * <p>Objects are {@link Map}s that keep their order, arrays are {@link List}s, numbers are {@link Long} or {@link Double}.
 * Not part of the public API.
 */
public final class Json {

    private Json() {
    }

    /** Reads one JSON value. Anything that is not valid JSON is an {@link IllegalArgumentException}. */
    public static Object parse(String text) {
        Reader reader = new Reader(text);
        reader.skipSpace();
        Object value = reader.value(0);
        reader.skipSpace();
        if (reader.at < text.length()) {
            throw reader.error("unexpected text after the value");
        }
        return value;
    }

    /** Reads a JSON object. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String text) {
        Object value = parse(text);
        if (!(value instanceof Map)) {
            throw new IllegalArgumentException("not a JSON object");
        }
        return (Map<String, Object>) value;
    }

    /** Writes a value on one line. */
    public static String write(Object value) {
        StringBuilder out = new StringBuilder();
        write(out, value, -1, 0);
        return out.toString();
    }

    /** Writes a value over several lines, indented by two spaces, for files people read. */
    public static String writePretty(Object value) {
        StringBuilder out = new StringBuilder();
        write(out, value, 2, 0);
        return out.append('\n').toString();
    }

    private static void write(StringBuilder out, Object value, int indent, int depth) {
        if (value == null) {
            out.append("null");
        } else if (value instanceof String) {
            quote(out, (String) value);
        } else if (value instanceof Boolean) {
            out.append(value);
        } else if (value instanceof Double || value instanceof Float) {
            double number = ((Number) value).doubleValue();
            if (Double.isNaN(number) || Double.isInfinite(number)) {
                throw new IllegalArgumentException("JSON has no way to write " + number);
            }
            out.append(number);
        } else if (value instanceof Number) {
            out.append(((Number) value).longValue());
        } else if (value instanceof Map) {
            Map<?, ?> map = (Map<?, ?>) value;
            if (map.isEmpty()) {
                out.append("{}");
                return;
            }
            out.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!first) {
                    out.append(',');
                }
                first = false;
                newline(out, indent, depth + 1);
                quote(out, String.valueOf(entry.getKey()));
                out.append(indent < 0 ? ":" : ": ");
                write(out, entry.getValue(), indent, depth + 1);
            }
            newline(out, indent, depth);
            out.append('}');
        } else if (value instanceof Iterable) {
            Iterable<?> items = (Iterable<?>) value;
            if (!items.iterator().hasNext()) {
                out.append("[]");
                return;
            }
            out.append('[');
            boolean first = true;
            for (Object item : items) {
                if (!first) {
                    out.append(',');
                }
                first = false;
                newline(out, indent, depth + 1);
                write(out, item, indent, depth + 1);
            }
            newline(out, indent, depth);
            out.append(']');
        } else {
            throw new IllegalArgumentException("cannot write a " + value.getClass().getName() + " as JSON");
        }
    }

    private static void newline(StringBuilder out, int indent, int depth) {
        if (indent >= 0) {
            out.append('\n');
            out.append(" ".repeat(indent * depth));
        }
    }

    private static void quote(StringBuilder out, String text) {
        out.append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }

    private static final class Reader {
        /** Deeper nesting than this is not something jpack ever writes; refusing it keeps a hostile text from overflowing the stack. */
        private static final int MAX_DEPTH = 64;

        private final String text;
        private int at;

        Reader(String text) {
            this.text = text;
        }

        Object value(int depth) {
            if (depth > MAX_DEPTH) {
                throw error("nested too deeply");
            }
            if (at >= text.length()) {
                throw error("unexpected end");
            }
            char c = text.charAt(at);
            switch (c) {
                case '{':
                    return object(depth);
                case '[':
                    return array(depth);
                case '"':
                    return string();
                case 't':
                    return literal("true", Boolean.TRUE);
                case 'f':
                    return literal("false", Boolean.FALSE);
                case 'n':
                    return literal("null", null);
                default:
                    return number();
            }
        }

        private Map<String, Object> object(int depth) {
            Map<String, Object> map = new LinkedHashMap<>();
            at++;
            skipSpace();
            if (peek() == '}') {
                at++;
                return map;
            }
            while (true) {
                skipSpace();
                if (peek() != '"') {
                    throw error("expected a name");
                }
                String name = string();
                skipSpace();
                expect(':');
                skipSpace();
                map.put(name, value(depth + 1));
                skipSpace();
                char next = peek();
                at++;
                if (next == '}') {
                    return map;
                }
                if (next != ',') {
                    throw error("expected ',' or '}'");
                }
            }
        }

        private List<Object> array(int depth) {
            List<Object> list = new ArrayList<>();
            at++;
            skipSpace();
            if (peek() == ']') {
                at++;
                return list;
            }
            while (true) {
                skipSpace();
                list.add(value(depth + 1));
                skipSpace();
                char next = peek();
                at++;
                if (next == ']') {
                    return list;
                }
                if (next != ',') {
                    throw error("expected ',' or ']'");
                }
            }
        }

        private String string() {
            StringBuilder out = new StringBuilder();
            at++;
            while (true) {
                if (at >= text.length()) {
                    throw error("a string does not end");
                }
                char c = text.charAt(at++);
                if (c == '"') {
                    return out.toString();
                }
                if (c != '\\') {
                    out.append(c);
                    continue;
                }
                if (at >= text.length()) {
                    throw error("a string does not end");
                }
                char escaped = text.charAt(at++);
                switch (escaped) {
                    case '"', '\\', '/' -> out.append(escaped);
                    case 'n' -> out.append('\n');
                    case 'r' -> out.append('\r');
                    case 't' -> out.append('\t');
                    case 'b' -> out.append('\b');
                    case 'f' -> out.append('\f');
                    case 'u' -> {
                        if (at + 4 > text.length()) {
                            throw error("a \\u escape is cut off");
                        }
                        try {
                            out.append((char) Integer.parseInt(text.substring(at, at + 4), 16));
                        } catch (NumberFormatException e) {
                            throw error("a \\u escape is not hexadecimal");
                        }
                        at += 4;
                    }
                    default -> throw error("unknown escape \\" + escaped);
                }
            }
        }

        private Object number() {
            int start = at;
            while (at < text.length() && "+-0123456789.eE".indexOf(text.charAt(at)) >= 0) {
                at++;
            }
            String number = text.substring(start, at);
            if (number.isEmpty()) {
                throw error("unexpected character '" + text.charAt(at) + "'");
            }
            try {
                if (number.indexOf('.') < 0 && number.indexOf('e') < 0 && number.indexOf('E') < 0) {
                    return Long.parseLong(number);
                }
                return Double.parseDouble(number);
            } catch (NumberFormatException e) {
                throw error("'" + number + "' is not a number");
            }
        }

        private Object literal(String word, Object value) {
            if (!text.startsWith(word, at)) {
                throw error("unexpected text");
            }
            at += word.length();
            return value;
        }

        private char peek() {
            if (at >= text.length()) {
                throw error("unexpected end");
            }
            return text.charAt(at);
        }

        private void expect(char c) {
            if (peek() != c) {
                throw error("expected '" + c + "'");
            }
            at++;
        }

        void skipSpace() {
            while (at < text.length() && " \t\r\n".indexOf(text.charAt(at)) >= 0) {
                at++;
            }
        }

        IllegalArgumentException error(String message) {
            return new IllegalArgumentException("invalid JSON at position " + at + ": " + message);
        }
    }
}
