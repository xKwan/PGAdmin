package spike.s1servlet;

import java.util.Collection;
import java.util.Map;

/** Minimal JSON writer for the spike's responses (strings, booleans, string lists). */
final class Json {

    private Json() {
    }

    static String object(Map<String, ?> values) {
        StringBuilder b = new StringBuilder("{");
        for (Map.Entry<String, ?> e : values.entrySet()) {
            if (b.length() > 1) {
                b.append(',');
            }
            b.append(string(e.getKey())).append(':').append(value(e.getValue()));
        }
        return b.append('}').toString();
    }

    private static String value(Object v) {
        if (v instanceof Boolean || v instanceof Number) {
            return v.toString();
        }
        if (v instanceof Collection<?> c) {
            StringBuilder b = new StringBuilder("[");
            for (Object o : c) {
                if (b.length() > 1) {
                    b.append(',');
                }
                b.append(value(o));
            }
            return b.append(']').toString();
        }
        return string(String.valueOf(v));
    }

    static String string(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '<' -> b.append("\\u003c");
                case '>' -> b.append("\\u003e");
                case '&' -> b.append("\\u0026");
                default -> {
                    if (c < 0x20) {
                        b.append(String.format("\\u%04x", (int) c));
                    } else {
                        b.append(c);
                    }
                }
            }
        }
        return b.append('"').toString();
    }
}
