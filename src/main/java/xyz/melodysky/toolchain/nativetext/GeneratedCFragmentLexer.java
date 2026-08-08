package xyz.melodysky.toolchain.nativetext;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Lightweight lexer for generated C fragments.
 *
 * <p>It recognizes comments, preprocessing directives, character literals,
 * narrow string literals and top-level function bodies. It deliberately does
 * not attempt to parse C types or expressions.</p>
 */
final class GeneratedCFragmentLexer {
    ScanResult scan(String source) {
        Objects.requireNonNull(source, "source");
        ArrayList<StringLiteral> strings = new ArrayList<>();
        ArrayList<FunctionBody> functionBodies = new ArrayList<>();
        ArrayList<Token> topLevelTokens = new ArrayList<>();
        int braceDepth = 0;
        int parenthesisDepth = 0;
        int bracketDepth = 0;
        ArrayList<Integer> parenthesisStarts = new ArrayList<>();
        int activeFunctionStart = -1;
        boolean lineStart = true;

        for (int index = 0; index < source.length();) {
            char ch = source.charAt(index);
            if (lineStart && isHorizontalWhitespace(ch)) {
                index++;
                continue;
            }
            if (lineStart && ch == '#') {
                index = skipPreprocessorDirective(source, index);
                lineStart = true;
                topLevelTokens.clear();
                continue;
            }
            lineStart = false;
            if (ch == '\n' || ch == '\r') {
                lineStart = true;
                index++;
                continue;
            }
            if (ch == '/' && hasNext(source, index, '/')) {
                index = skipLineComment(source, index + 2);
                continue;
            }
            if (ch == '/' && hasNext(source, index, '*')) {
                index = skipBlockComment(source, index + 2);
                continue;
            }
            if (ch == '\'') {
                index = skipQuoted(source, index, '\'');
                continue;
            }
            if (ch == '"') {
                rejectPrefixedLiteral(source, index);
                int end = skipQuoted(source, index, '"');
                LiteralUse use = classifyLiteralUse(
                        source,
                        index,
                        end,
                        parenthesisDepth,
                        bracketDepth);
                int argumentListStart = use == LiteralUse.POINTER_ARGUMENT
                        ? directCallArgumentListStart(
                                source,
                                parenthesisStarts)
                        : -1;
                if (use == LiteralUse.POINTER_ARGUMENT
                        && argumentListStart < 0) {
                    throw new IllegalArgumentException(
                            "generated C pointer argument is not in a direct call argument list");
                }
                strings.add(new StringLiteral(
                        index,
                        end,
                        decodeString(source.substring(index + 1, end - 1)),
                        use,
                        argumentListStart));
                if (braceDepth == 0) {
                    topLevelTokens.add(new Token(TokenKind.STRING, "\"\""));
                }
                index = end;
                continue;
            }
            if (isIdentifierStart(ch)) {
                int end = index + 1;
                while (end < source.length() && isIdentifierPart(source.charAt(end))) {
                    end++;
                }
                if (braceDepth == 0) {
                    topLevelTokens.add(new Token(
                            TokenKind.IDENTIFIER,
                            source.substring(index, end)));
                }
                index = end;
                continue;
            }
            if (ch == '{') {
                if (braceDepth == 0 && isFunctionHeader(topLevelTokens)) {
                    activeFunctionStart = index + 1;
                }
                braceDepth++;
                index++;
                continue;
            }
            if (ch == '}') {
                if (braceDepth == 0) {
                    throw new IllegalArgumentException(
                            "generated C fragment has an unmatched closing brace");
                }
                braceDepth--;
                if (braceDepth == 0) {
                    if (activeFunctionStart >= 0) {
                        functionBodies.add(new FunctionBody(
                                activeFunctionStart,
                                index));
                        activeFunctionStart = -1;
                    }
                    topLevelTokens.clear();
                }
                index++;
                continue;
            }
            if (ch == '(') {
                parenthesisStarts.add(index);
                parenthesisDepth++;
            } else if (ch == ')') {
                if (parenthesisDepth == 0) {
                    throw new IllegalArgumentException(
                            "generated C fragment has an unmatched closing parenthesis");
                }
                parenthesisDepth--;
                parenthesisStarts.remove(parenthesisStarts.size() - 1);
            } else if (ch == '[') {
                bracketDepth++;
            } else if (ch == ']') {
                if (bracketDepth == 0) {
                    throw new IllegalArgumentException(
                            "generated C fragment has an unmatched closing bracket");
                }
                bracketDepth--;
            }
            if (braceDepth == 0 && !Character.isWhitespace(ch)) {
                topLevelTokens.add(new Token(TokenKind.PUNCTUATION, Character.toString(ch)));
                if (ch == ';') {
                    topLevelTokens.clear();
                }
            }
            index++;
        }
        if (braceDepth != 0) {
            throw new IllegalArgumentException(
                    "generated C fragment has an unmatched opening brace");
        }
        if (parenthesisDepth != 0) {
            throw new IllegalArgumentException(
                    "generated C fragment has an unmatched opening parenthesis");
        }
        if (bracketDepth != 0) {
            throw new IllegalArgumentException(
                    "generated C fragment has an unmatched opening bracket");
        }
        rejectAdjacentLiterals(source, strings);
        return new ScanResult(strings, functionBodies);
    }

    private void rejectPrefixedLiteral(String source, int start) {
        if (start >= 2 && source.regionMatches(start - 2, "u8", 0, 2)) {
            throw new IllegalArgumentException(
                    "prefixed generated C string literals are unsupported");
        }
        if (start >= 1) {
            char prefix = source.charAt(start - 1);
            if (prefix == 'L' || prefix == 'u' || prefix == 'U') {
                throw new IllegalArgumentException(
                        "prefixed generated C string literals are unsupported");
            }
        }
    }

    private LiteralUse classifyLiteralUse(
            String source,
            int start,
            int end,
            int parenthesisDepth,
            int bracketDepth) {
        if (bracketDepth != 0) {
            throw new IllegalArgumentException(
                    "generated C string literals in array expressions are unsupported");
        }
        int previous = previousSignificant(source, start - 1);
        int next = nextSignificant(source, end);
        if (isSizeOrAlignmentOperator(source, previous)) {
            throw new IllegalArgumentException(
                    "sizeof/_Alignof generated C string literals are unsupported");
        }
        if (parenthesisDepth > 0
                && previous >= 0
                && next >= 0
                && (source.charAt(previous) == '(' || source.charAt(previous) == ',')
                && (source.charAt(next) == ')' || source.charAt(next) == ',')) {
            return LiteralUse.POINTER_ARGUMENT;
        }

        int statementStart = statementStart(source, start);
        String prefix = source.substring(statementStart, start);
        if (prefix.matches(
                "(?s).*\\b(?:const\\s+)?char\\s*\\*"
                        + "\\s*[A-Za-z_][A-Za-z0-9_]*\\s*=\\s*")) {
            if (prefix.indexOf('[') >= 0 || prefix.indexOf(']') >= 0) {
                throw new IllegalArgumentException(
                        "generated C string array initializers are unsupported");
            }
            if (Pattern.compile(
                            "\\b(?:static|extern|register|_Thread_local|thread_local)\\b")
                    .matcher(prefix)
                    .find()) {
                throw new IllegalArgumentException(
                        "generated C activation-local text cannot initialize escaping storage");
            }
            return LiteralUse.POINTER_ASSIGNMENT;
        }
        if (prefix.matches("(?s).*\\breturn\\s*")) {
            int after = nextSignificant(source, end);
            if (after >= 0 && source.charAt(after) == ';') {
                return LiteralUse.DIRECT_RETURN;
            }
        }
        throw new IllegalArgumentException(
                "generated C string literal is not a verified pointer expression");
    }

    private int directCallArgumentListStart(
            String source,
            List<Integer> parenthesisStarts) {
        if (parenthesisStarts.isEmpty()) {
            return -1;
        }
        int opening = parenthesisStarts.get(parenthesisStarts.size() - 1);
        int previous = previousSignificant(source, opening - 1);
        if (previous < 0 || !isIdentifierPart(source.charAt(previous))) {
            return -1;
        }
        int end = previous + 1;
        while (previous >= 0 && isIdentifierPart(source.charAt(previous))) {
            previous--;
        }
        String callee = source.substring(previous + 1, end);
        return isControlKeyword(callee)
                        || callee.equals("sizeof")
                        || callee.equals("_Alignof")
                        || callee.equals("alignof")
                ? -1
                : opening;
    }

    private boolean isSizeOrAlignmentOperator(String source, int previous) {
        if (previous < 0) {
            return false;
        }
        int cursor = previous;
        if (source.charAt(cursor) == '(') {
            cursor = previousSignificant(source, cursor - 1);
        }
        if (cursor < 0 || !isIdentifierPart(source.charAt(cursor))) {
            return false;
        }
        int end = cursor + 1;
        while (cursor >= 0 && isIdentifierPart(source.charAt(cursor))) {
            cursor--;
        }
        String word = source.substring(cursor + 1, end);
        return word.equals("sizeof")
                || word.equals("_Alignof")
                || word.equals("alignof");
    }

    private int statementStart(String source, int before) {
        for (int index = before - 1; index >= 0; index--) {
            char ch = source.charAt(index);
            if (ch == ';' || ch == '{' || ch == '}') {
                return index + 1;
            }
        }
        return 0;
    }

    private int previousSignificant(String source, int from) {
        int cursor = from;
        while (cursor >= 0 && Character.isWhitespace(source.charAt(cursor))) {
            cursor--;
        }
        return cursor;
    }

    private int nextSignificant(String source, int from) {
        int cursor = from;
        while (cursor < source.length()
                && Character.isWhitespace(source.charAt(cursor))) {
            cursor++;
        }
        return cursor < source.length() ? cursor : -1;
    }

    private void rejectAdjacentLiterals(
            String source,
            List<StringLiteral> literals) {
        for (int index = 1; index < literals.size(); index++) {
            StringLiteral previous = literals.get(index - 1);
            StringLiteral current = literals.get(index);
            if (onlyTrivia(source.substring(previous.end(), current.start()))) {
                throw new IllegalArgumentException(
                        "adjacent generated C string literals are unsupported");
            }
        }
    }

    private boolean onlyTrivia(String value) {
        int cursor = 0;
        while (cursor < value.length()) {
            char ch = value.charAt(cursor);
            if (Character.isWhitespace(ch)) {
                cursor++;
                continue;
            }
            if (ch == '/' && cursor + 1 < value.length()) {
                char next = value.charAt(cursor + 1);
                if (next == '/') {
                    cursor += 2;
                    while (cursor < value.length()
                            && value.charAt(cursor) != '\n') {
                        cursor++;
                    }
                    continue;
                }
                if (next == '*') {
                    int end = value.indexOf("*/", cursor + 2);
                    if (end < 0) {
                        return false;
                    }
                    cursor = end + 2;
                    continue;
                }
            }
            return false;
        }
        return true;
    }

    private boolean isFunctionHeader(List<Token> tokens) {
        if (tokens.isEmpty() || !tokens.get(tokens.size() - 1).text().equals(")")) {
            return false;
        }
        int depth = 0;
        for (int index = tokens.size() - 1; index >= 0; index--) {
            String token = tokens.get(index).text();
            if (token.equals(")")) {
                depth++;
            } else if (token.equals("(")) {
                depth--;
                if (depth == 0) {
                    if (index == 0) {
                        return false;
                    }
                    Token name = tokens.get(index - 1);
                    return name.kind() == TokenKind.IDENTIFIER
                            && !isControlKeyword(name.text());
                }
            }
        }
        return false;
    }

    private boolean isControlKeyword(String value) {
        return value.equals("if")
                || value.equals("for")
                || value.equals("while")
                || value.equals("switch");
    }

    private int skipPreprocessorDirective(String source, int index) {
        int cursor = index;
        while (cursor < source.length()) {
            char ch = source.charAt(cursor++);
            if (ch == '\n') {
                int backslash = cursor - 2;
                while (backslash >= index
                        && (source.charAt(backslash) == ' '
                                || source.charAt(backslash) == '\t'
                                || source.charAt(backslash) == '\r')) {
                    backslash--;
                }
                if (backslash < index || source.charAt(backslash) != '\\') {
                    return cursor;
                }
            }
        }
        return cursor;
    }

    private int skipLineComment(String source, int index) {
        int cursor = index;
        while (cursor < source.length() && source.charAt(cursor) != '\n') {
            cursor++;
        }
        return cursor;
    }

    private int skipBlockComment(String source, int index) {
        int cursor = index;
        while (cursor + 1 < source.length()) {
            if (source.charAt(cursor) == '*' && source.charAt(cursor + 1) == '/') {
                return cursor + 2;
            }
            cursor++;
        }
        throw new IllegalArgumentException(
                "generated C fragment has an unterminated block comment");
    }

    private int skipQuoted(String source, int start, char delimiter) {
        int cursor = start + 1;
        while (cursor < source.length()) {
            char ch = source.charAt(cursor++);
            if (ch == '\\') {
                if (cursor >= source.length()) {
                    break;
                }
                cursor++;
            } else if (ch == delimiter) {
                return cursor;
            } else if (ch == '\n' || ch == '\r') {
                break;
            }
        }
        throw new IllegalArgumentException(
                "generated C fragment has an unterminated "
                        + (delimiter == '"' ? "string" : "character")
                        + " literal");
    }

    private String decodeString(String contents) {
        StringBuilder decoded = new StringBuilder(contents.length());
        for (int index = 0; index < contents.length(); index++) {
            char ch = contents.charAt(index);
            if (ch != '\\') {
                decoded.append(ch);
                continue;
            }
            if (++index >= contents.length()) {
                throw new IllegalArgumentException(
                        "generated C string has an unterminated escape");
            }
            char escaped = contents.charAt(index);
            switch (escaped) {
                case '\\', '"', '\'', '?' -> decoded.append(escaped);
                case 'a' -> decoded.append('\u0007');
                case 'b' -> decoded.append('\b');
                case 'f' -> decoded.append('\f');
                case 'n' -> decoded.append('\n');
                case 'r' -> decoded.append('\r');
                case 't' -> decoded.append('\t');
                case 'v' -> decoded.append('\u000b');
                default -> {
                    if (escaped < '0' || escaped > '7') {
                        throw new IllegalArgumentException(
                                "unsupported generated C string escape: \\"
                                + escaped);
                    }
                    int value = escaped - '0';
                    int digits = 1;
                    while (digits < 3
                            && index + 1 < contents.length()
                            && contents.charAt(index + 1) >= '0'
                            && contents.charAt(index + 1) <= '7') {
                        value = (value << 3)
                                + (contents.charAt(++index) - '0');
                        digits++;
                    }
                    if (value > 0x7f) {
                        throw new IllegalArgumentException(
                                "generated C octal string escapes above ASCII are unsupported");
                    }
                    decoded.append((char) value);
                }
            }
        }
        return decoded.toString();
    }

    private boolean hasNext(String source, int index, char expected) {
        return index + 1 < source.length()
                && source.charAt(index + 1) == expected;
    }

    private boolean isHorizontalWhitespace(char ch) {
        return ch == ' ' || ch == '\t' || ch == '\f';
    }

    private boolean isIdentifierStart(char ch) {
        return ch == '_' || Character.isLetter(ch);
    }

    private boolean isIdentifierPart(char ch) {
        return ch == '_' || Character.isLetterOrDigit(ch);
    }

    record StringLiteral(
            int start,
            int end,
            String value,
            LiteralUse use,
            int argumentListStart) {
        StringLiteral {
            if ((use == LiteralUse.POINTER_ARGUMENT)
                    != (argumentListStart >= 0)) {
                throw new IllegalArgumentException(
                        "generated C literal argument-list identity is inconsistent");
            }
        }
    }

    record ScanResult(
            List<StringLiteral> stringLiterals,
            List<FunctionBody> functionBodies) {
        ScanResult {
            stringLiterals = List.copyOf(stringLiterals);
            functionBodies = List.copyOf(functionBodies);
        }
    }

    record FunctionBody(int start, int end) {
        boolean contains(StringLiteral literal) {
            return literal.start() >= start && literal.end() <= end;
        }
    }

    enum LiteralUse {
        POINTER_ARGUMENT,
        POINTER_ASSIGNMENT,
        DIRECT_RETURN
    }

    private record Token(TokenKind kind, String text) {
    }

    private enum TokenKind {
        IDENTIFIER,
        STRING,
        PUNCTUATION
    }
}
