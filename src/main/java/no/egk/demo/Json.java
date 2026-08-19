package no.egk.demo;

import java.util.Map;

/**
 * A deliberately tiny JSON writer for flat objects, so the service stays
 * dependency-free. Swap in Jackson the moment you need real (de)serialisation.
 */
final class Json {

    private Json() {
    }

    static String object(Map<String, ?> values) {
        StringBuilder sb = new StringBuilder(128).append('{');
        boolean first = true;
        for (Map.Entry<String, ?> entry : values.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append('"').append(escape(entry.getKey())).append("\":");
            appendValue(sb, entry.getValue());
        }
        return sb.append('}').toString();
    }

    private static void appendValue(StringBuilder sb, Object value) {
        switch (value) {
            case null -> sb.append("null");
            case Number n -> sb.append(n);
            case Boolean b -> sb.append(b);
            default -> sb.append('"').append(escape(String.valueOf(value))).append('"');
        }
    }

    private static String escape(String raw) {
        StringBuilder sb = new StringBuilder(raw.length() + 16);
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) {
                        sb.append("\\u%04x".formatted((int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }
}
