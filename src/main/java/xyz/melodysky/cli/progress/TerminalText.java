package xyz.melodysky.cli.progress;

final class TerminalText {
    private TerminalText() {
    }

    static String sanitize(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        StringBuilder sanitized = new StringBuilder(value.length());
        boolean pendingSpace = false;
        for (int offset = 0; offset < value.length();) {
            int codePoint = value.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (codePoint == 0x1B) {
                offset = skipEscapeSequence(value, offset);
                continue;
            }
            if (Character.isISOControl(codePoint) || Character.isWhitespace(codePoint)) {
                pendingSpace = sanitized.length() > 0;
                continue;
            }
            if (pendingSpace) {
                sanitized.append(' ');
                pendingSpace = false;
            }
            sanitized.appendCodePoint(codePoint);
        }
        return sanitized.toString();
    }

    static String abbreviateHead(String value, int width) {
        if (displayWidth(value) <= width) {
            return value;
        }
        if (width <= 3) {
            return ".".repeat(Math.max(0, width));
        }
        return takeHead(value, width - 3) + "...";
    }

    static String abbreviateTail(String value, int width) {
        if (displayWidth(value) <= width) {
            return value;
        }
        if (width <= 3) {
            return ".".repeat(Math.max(0, width));
        }
        return "..." + takeTail(value, width - 3);
    }

    static String fitLine(String value, int width) {
        return abbreviateHead(value, Math.max(0, width));
    }

    static int displayWidth(String value) {
        int width = 0;
        for (int offset = 0; offset < value.length();) {
            int codePoint = value.codePointAt(offset);
            width += terminalCellWidth(codePoint);
            offset += Character.charCount(codePoint);
        }
        return width;
    }

    private static int skipEscapeSequence(String value, int offset) {
        if (offset >= value.length()) {
            return offset;
        }
        if (value.charAt(offset) != '[') {
            return Math.min(value.length(), offset + 1);
        }
        offset++;
        while (offset < value.length()) {
            char character = value.charAt(offset++);
            if (character >= 0x40 && character <= 0x7E) {
                break;
            }
        }
        return offset;
    }

    private static String takeHead(String value, int width) {
        StringBuilder result = new StringBuilder();
        int used = 0;
        for (int offset = 0; offset < value.length();) {
            int codePoint = value.codePointAt(offset);
            int cellWidth = terminalCellWidth(codePoint);
            if (used + cellWidth > width) {
                break;
            }
            result.appendCodePoint(codePoint);
            used += cellWidth;
            offset += Character.charCount(codePoint);
        }
        return result.toString();
    }

    private static String takeTail(String value, int width) {
        StringBuilder reversed = new StringBuilder();
        int used = 0;
        for (int offset = value.length(); offset > 0;) {
            int codePoint = value.codePointBefore(offset);
            int cellWidth = terminalCellWidth(codePoint);
            if (used + cellWidth > width) {
                break;
            }
            reversed.appendCodePoint(codePoint);
            used += cellWidth;
            offset -= Character.charCount(codePoint);
        }
        return reversed.reverse().toString();
    }

    private static int terminalCellWidth(int codePoint) {
        int type = Character.getType(codePoint);
        if (type == Character.NON_SPACING_MARK
                || type == Character.ENCLOSING_MARK
                || type == Character.FORMAT) {
            return 0;
        }
        return codePoint < 0x80 ? 1 : 2;
    }
}
