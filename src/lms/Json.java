package lms;

import java.util.*;

/** Tiny JSON writer so the project needs no libraries besides the JDBC driver. */
final class Json {
    private Json() {}

    static String str(Object o) {
        if (o == null) return "null";
        if (o instanceof Number || o instanceof Boolean) return o.toString();
        if (o instanceof Map<?, ?> m) return map(m);
        if (o instanceof List<?> l) return list(l);
        StringBuilder b = new StringBuilder("\"");
        for (char c : o.toString().toCharArray()) {
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> {
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                    else b.append(c);
                }
            }
        }
        return b.append('"').toString();
    }

    static String map(Map<?, ?> m) {
        StringJoiner j = new StringJoiner(",", "{", "}");
        for (Map.Entry<?, ?> e : m.entrySet()) j.add(str(e.getKey().toString()) + ":" + str(e.getValue()));
        return j.toString();
    }

    static String list(List<?> l) {
        StringJoiner j = new StringJoiner(",", "[", "]");
        for (Object o : l) j.add(str(o));
        return j.toString();
    }

    static String obj(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return map(m);
    }
}
