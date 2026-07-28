package xyz.melodysky.toolchain.nativetext;

import java.util.Objects;
import java.util.OptionalInt;

/** Finds a direct C string-literal second argument without treating comments as code. */
final class CSourceCallLiteralScanner {
    OptionalInt firstLiteralSecondArgument(String source, String callName) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(callName, "callName");
        if (callName.isBlank()) {
            throw new IllegalArgumentException("callName must not be blank");
        }
        String code = maskComments(source);
        int offset = 0;
        while ((offset = code.indexOf(callName, offset)) >= 0) {
            int afterName = offset + callName.length();
            if (!isIdentifierBoundary(code, offset - 1)
                    || !isIdentifierBoundary(code, afterName)) {
                offset = afterName;
                continue;
            }
            int open = skipWhitespace(code, afterName);
            if (open >= code.length() || code.charAt(open) != '(') {
                offset = afterName;
                continue;
            }
            int comma = firstTopLevelComma(code, open);
            if (comma >= 0) {
                int literalEnd = consumeStringLiteralSequence(
                        code,
                        skipWhitespace(code, comma + 1));
                if (literalEnd >= 0) {
                    int close = skipWhitespace(code, literalEnd);
                    if (close < code.length() && code.charAt(close) == ')') {
                        return OptionalInt.of(offset);
                    }
                }
            }
            offset = afterName;
        }
        return OptionalInt.empty();
    }

    private int firstTopLevelComma(String source, int open) {
        int depth = 1;
        for (int index = open + 1; index < source.length(); index++) {
            char current = source.charAt(index);
            if (current == '"' || current == '\'') {
                index = quotedEnd(source, index, current);
                if (index < 0) {
                    return -1;
                }
            } else if (current == '(') {
                depth++;
            } else if (current == ')') {
                depth--;
                if (depth == 0) {
                    return -1;
                }
            } else if (current == ',' && depth == 1) {
                return index;
            }
        }
        return -1;
    }

    private int consumeStringLiteralSequence(String source, int start) {
        int offset = consumeStringLiteral(source, start);
        if (offset < 0) {
            return -1;
        }
        while (true) {
            int next = skipWhitespace(source, offset);
            int adjacent = consumeStringLiteral(source, next);
            if (adjacent < 0) {
                return next;
            }
            offset = adjacent;
        }
    }

    private int consumeStringLiteral(String source, int start) {
        int quote = start;
        if (source.startsWith("u8\"", quote)) {
            quote += 2;
        } else if (quote < source.length()
                && (source.charAt(quote) == 'u'
                        || source.charAt(quote) == 'U'
                        || source.charAt(quote) == 'L')
                && quote + 1 < source.length()
                && source.charAt(quote + 1) == '"') {
            quote++;
        }
        if (quote >= source.length() || source.charAt(quote) != '"') {
            return -1;
        }
        int end = quotedEnd(source, quote, '"');
        return end < 0 ? -1 : end + 1;
    }

    private int quotedEnd(String source, int quote, char delimiter) {
        for (int index = quote + 1; index < source.length(); index++) {
            char current = source.charAt(index);
            if (current == '\\') {
                index++;
            } else if (current == delimiter) {
                return index;
            }
        }
        return -1;
    }

    private int skipWhitespace(String source, int start) {
        int offset = start;
        while (offset < source.length()
                && Character.isWhitespace(source.charAt(offset))) {
            offset++;
        }
        return offset;
    }

    private boolean isIdentifierBoundary(String source, int offset) {
        if (offset < 0 || offset >= source.length()) {
            return true;
        }
        char value = source.charAt(offset);
        return !(value == '_' || Character.isLetterOrDigit(value));
    }

    private String maskComments(String source) {
        char[] masked = source.toCharArray();
        for (int index = 0; index < masked.length; index++) {
            char current = masked[index];
            if (current == '"' || current == '\'') {
                int end = quotedEnd(source, index, current);
                if (end < 0) {
                    break;
                }
                index = end;
                continue;
            }
            if (current != '/' || index + 1 >= masked.length) {
                continue;
            }
            char next = masked[index + 1];
            if (next == '/') {
                masked[index] = ' ';
                masked[index + 1] = ' ';
                index += 2;
                while (index < masked.length && masked[index] != '\n') {
                    masked[index++] = ' ';
                }
                index--;
            } else if (next == '*') {
                masked[index] = ' ';
                masked[index + 1] = ' ';
                index += 2;
                while (index + 1 < masked.length
                        && !(masked[index] == '*' && masked[index + 1] == '/')) {
                    if (masked[index] != '\n' && masked[index] != '\r') {
                        masked[index] = ' ';
                    }
                    index++;
                }
                if (index + 1 < masked.length) {
                    masked[index] = ' ';
                    masked[index + 1] = ' ';
                    index++;
                }
            }
        }
        return new String(masked);
    }
}
