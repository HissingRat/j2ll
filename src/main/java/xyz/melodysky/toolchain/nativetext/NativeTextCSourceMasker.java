package xyz.melodysky.toolchain.nativetext;

/** Masks comments and literals while preserving source offsets and newlines. */
final class NativeTextCSourceMasker {
    private NativeTextCSourceMasker() {}

    static String maskNonCode(String source) {
        StringBuilder masked = new StringBuilder(source);
        Mode mode = Mode.CODE;
        for (int index = 0; index < source.length(); index++) {
            char value = source.charAt(index);
            char next = index + 1 < source.length()
                    ? source.charAt(index + 1)
                    : '\0';
            switch (mode) {
                case CODE -> {
                    if (value == '/' && next == '/') {
                        blank(masked, index);
                        blank(masked, ++index);
                        mode = Mode.LINE_COMMENT;
                    } else if (value == '/' && next == '*') {
                        blank(masked, index);
                        blank(masked, ++index);
                        mode = Mode.BLOCK_COMMENT;
                    } else if (value == '"') {
                        blank(masked, index);
                        mode = Mode.STRING;
                    } else if (value == '\'') {
                        blank(masked, index);
                        mode = Mode.CHARACTER;
                    }
                }
                case LINE_COMMENT -> {
                    if (value == '\n') {
                        mode = Mode.CODE;
                    } else {
                        blank(masked, index);
                    }
                }
                case BLOCK_COMMENT -> {
                    if (value == '*' && next == '/') {
                        blank(masked, index);
                        blank(masked, ++index);
                        mode = Mode.CODE;
                    } else {
                        blank(masked, index);
                    }
                }
                case STRING -> {
                    blank(masked, index);
                    if (value == '\\' && index + 1 < source.length()) {
                        blank(masked, ++index);
                    } else if (value == '"') {
                        mode = Mode.CODE;
                    }
                }
                case CHARACTER -> {
                    blank(masked, index);
                    if (value == '\\' && index + 1 < source.length()) {
                        blank(masked, ++index);
                    } else if (value == '\'') {
                        mode = Mode.CODE;
                    }
                }
            }
        }
        return masked.toString();
    }

    private static void blank(StringBuilder source, int index) {
        char value = source.charAt(index);
        if (value != '\n' && value != '\r') {
            source.setCharAt(index, ' ');
        }
    }

    private enum Mode {
        CODE,
        LINE_COMMENT,
        BLOCK_COMMENT,
        STRING,
        CHARACTER
    }
}
