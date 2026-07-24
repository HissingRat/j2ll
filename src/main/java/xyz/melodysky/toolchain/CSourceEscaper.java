package xyz.melodysky.toolchain;

public final class CSourceEscaper {
    private CSourceEscaper() {
    }

    public static String stringContents(String value) {
        StringBuilder escaped = new StringBuilder();
        for (int index = 0; index < value.length(); index++) {
            char ch = value.charAt(index);
            switch (ch) {
                case '\\' -> escaped.append("\\\\");
                case '"' -> escaped.append("\\\"");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                case '\b' -> escaped.append("\\b");
                case '\f' -> escaped.append("\\f");
                default -> {
                    if (ch < 0x20 || ch == 0x7f) {
                        escaped.append(String.format(
                                java.util.Locale.ROOT,
                                "\\%03o",
                                (int) ch));
                    } else {
                        escaped.append(ch);
                    }
                }
            }
        }
        return escaped.toString();
    }
}
