package xyz.melodysky.toolchain;

import java.nio.file.Path;
import java.util.Locale;

/** Shared path and string literal rendering for generated build.zig text. */
final class ZigBuildText {
    private ZigBuildText() {}

    static String relative(Path root, Path child) {
        return root.toAbsolutePath().normalize()
                .relativize(child.toAbsolutePath().normalize())
                .toString()
                .replace('\\', '/');
    }

    static String quote(String value) {
        StringBuilder quoted = new StringBuilder("\"");
        for (int index = 0; index < value.length(); index++) {
            char ch = value.charAt(index);
            switch (ch) {
                case '\\' -> quoted.append("\\\\");
                case '"' -> quoted.append("\\\"");
                case '\n' -> quoted.append("\\n");
                case '\r' -> quoted.append("\\r");
                case '\t' -> quoted.append("\\t");
                default -> {
                    if (ch < 0x20 || ch == 0x7f) {
                        quoted.append(String.format(Locale.ROOT, "\\x%02x", (int) ch));
                    } else {
                        quoted.append(ch);
                    }
                }
            }
        }
        return quoted.append('"').toString();
    }
}
