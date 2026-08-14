package xyz.melodysky.toolchain;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/** Single lexical pass that closes declaration and call-site placeholders. */
final class HostJniLowSensitivityThrowShardMaterializer {
    String materialize(
            String source,
            HostJniLowSensitivityThrowShardPlan plan) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(plan, "plan");
        StringBuilder result = new StringBuilder(
                source.length() + plan.declarations().length());
        Map<String, Integer> occurrences = new HashMap<>();
        int anchorOccurrences = 0;
        LexicalState state = LexicalState.CODE;
        boolean escaped = false;
        int index = 0;
        while (index < source.length()) {
            char ch = source.charAt(index);
            if (state == LexicalState.LINE_COMMENT) {
                result.append(ch);
                index++;
                if (ch == '\n') {
                    state = LexicalState.CODE;
                }
                continue;
            }
            if (state == LexicalState.BLOCK_COMMENT) {
                result.append(ch);
                index++;
                if (ch == '*'
                        && index < source.length()
                        && source.charAt(index) == '/') {
                    result.append('/');
                    index++;
                    state = LexicalState.CODE;
                }
                continue;
            }
            if (state == LexicalState.STRING
                    || state == LexicalState.CHARACTER) {
                result.append(ch);
                index++;
                if (escaped) {
                    escaped = false;
                } else if (ch == '\\') {
                    escaped = true;
                } else if ((state == LexicalState.STRING && ch == '"')
                        || (state == LexicalState.CHARACTER && ch == '\'')) {
                    state = LexicalState.CODE;
                }
                continue;
            }

            if (ch == '/' && index + 1 < source.length()) {
                char next = source.charAt(index + 1);
                if (next == '/') {
                    result.append("//");
                    index += 2;
                    state = LexicalState.LINE_COMMENT;
                    continue;
                }
                if (next == '*') {
                    result.append("/*");
                    index += 2;
                    state = LexicalState.BLOCK_COMMENT;
                    continue;
                }
            }
            if (ch == '"') {
                result.append(ch);
                index++;
                state = LexicalState.STRING;
                continue;
            }
            if (ch == '\'') {
                result.append(ch);
                index++;
                state = LexicalState.CHARACTER;
                continue;
            }
            if (!identifierStart(ch)) {
                result.append(ch);
                index++;
                continue;
            }

            int end = index + 1;
            while (end < source.length()
                    && identifierPart(source.charAt(end))) {
                end++;
            }
            String token = source.substring(index, end);
            if (token.equals(plan.declarationAnchor())) {
                anchorOccurrences++;
                if (anchorOccurrences > 1) {
                    throw new IllegalStateException(
                            "duplicate low-sensitivity declaration anchor");
                }
                result.append(plan.declarations());
            } else if (plan.symbolForPlaceholder(token) != null) {
                String symbol = plan.symbolForPlaceholder(token);
                int count = occurrences.merge(token, 1, Integer::sum);
                if (count > 1) {
                    throw new IllegalStateException(
                            "duplicate low-sensitivity throw-site placeholder");
                }
                result.append(symbol);
            } else if (reservedToken(
                    token,
                    HostJniLowSensitivityThrowShardDeriver
                            .placeholderPrefix())) {
                throw new IllegalStateException(
                        "unknown low-sensitivity throw-site placeholder");
            } else if (reservedToken(
                    token,
                    HostJniLowSensitivityThrowShardDeriver
                            .anchorPrefix())) {
                throw new IllegalStateException(
                        "unknown low-sensitivity declaration anchor");
            } else {
                result.append(token);
            }
            index = end;
        }

        if (state == LexicalState.BLOCK_COMMENT
                || state == LexicalState.STRING
                || state == LexicalState.CHARACTER) {
            throw new IllegalStateException(
                    "incomplete generated-C lexical evidence");
        }

        int expectedAnchors = plan.isEmpty() ? 0 : 1;
        if (anchorOccurrences != expectedAnchors) {
            throw new IllegalStateException(
                    "missing low-sensitivity declaration anchor");
        }
        for (String placeholder : plan.placeholders()) {
            if (occurrences.getOrDefault(placeholder, 0) != 1) {
                throw new IllegalStateException(
                        "missing low-sensitivity throw-site placeholder");
            }
        }
        return result.toString();
    }

    void materialize(
            StringBuilder source,
            HostJniLowSensitivityThrowShardPlan plan) {
        Objects.requireNonNull(source, "source");
        String materialized = materialize(source.toString(), plan);
        source.setLength(0);
        source.append(materialized);
    }

    private boolean identifierStart(char ch) {
        return ch == '_'
                || (ch >= 'a' && ch <= 'z')
                || (ch >= 'A' && ch <= 'Z');
    }

    private boolean identifierPart(char ch) {
        return identifierStart(ch)
                || (ch >= '0' && ch <= '9');
    }

    private boolean reservedToken(
            String token,
            String prefix) {
        return token.length() == prefix.length() + 32
                && token.startsWith(prefix);
    }

    private enum LexicalState {
        CODE,
        STRING,
        CHARACTER,
        LINE_COMMENT,
        BLOCK_COMMENT
    }
}
