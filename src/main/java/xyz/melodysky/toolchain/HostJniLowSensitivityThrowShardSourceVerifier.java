package xyz.melodysky.toolchain;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/** Final translation-unit closure and fanout gate for physical throw leaves. */
final class HostJniLowSensitivityThrowShardSourceVerifier {
    private final HostJniLowSensitivityThrowShardBodyVerifier bodyVerifier =
            new HostJniLowSensitivityThrowShardBodyVerifier();

    void verify(
            String source,
            HostJniLowSensitivityThrowShardPlan plan) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(plan, "plan");
        Map<String, Counts> counts = new HashMap<>();
        for (HostJniLowSensitivityThrowShardPlan.Shard shard
                : plan.shards()) {
            counts.put(shard.symbol(), new Counts());
        }
        scanIdentifiers(source, (token, start, end) -> {
            if (reservedToken(
                            token,
                            HostJniLowSensitivityThrowShardDeriver
                                    .placeholderPrefix())
                    || reservedToken(
                            token,
                            HostJniLowSensitivityThrowShardDeriver
                                    .anchorPrefix())) {
                throw new IllegalStateException(
                        "residual low-sensitivity placeholder in final C source");
            }
            Counts symbolCounts = counts.get(token);
            if (symbolCounts == null) {
                return;
            }
            if (matchesBefore(source, start, "static void ")
                    && matchesAfter(
                            source,
                            end,
                            "(JNIEnv* env) __attribute__((noinline, cold));")) {
                symbolCounts.prototypes++;
                symbolCounts.prototypePosition = start;
            } else if (matchesBefore(source, start, "static void ")
                    && matchesAfter(source, end, "(JNIEnv* env) {")) {
                symbolCounts.definitions++;
                symbolCounts.definitionPosition = start;
                symbolCounts.definitionNameEnd = end;
            } else if (matchesAfter(source, end, "(env);")
                    && directCallPrefix(source, start)) {
                symbolCounts.calls++;
                symbolCounts.firstCallPosition = Math.min(
                        symbolCounts.firstCallPosition,
                        start);
            } else {
                throw new IllegalStateException(
                        "low-sensitivity physical leaf has a non-direct reference");
            }
        });

        int totalCalls = 0;
        for (HostJniLowSensitivityThrowShardPlan.Shard shard
                : plan.shards()) {
            Counts actual = counts.get(shard.symbol());
            if (actual.prototypes != 1 || actual.definitions != 1) {
                throw new IllegalStateException(
                        "low-sensitivity physical leaf definition closure failed");
            }
            if (actual.prototypePosition >= actual.definitionPosition
                    || actual.prototypePosition
                            >= actual.firstCallPosition) {
                throw new IllegalStateException(
                        "low-sensitivity physical leaf declaration ordering failed");
            }
            bodyVerifier.verify(source, actual.definitionNameEnd);
            if (actual.calls != shard.sites().size()
                    || actual.calls < 1
                    || actual.calls
                            > HostJniLowSensitivityThrowShardPlan
                                    .MAX_DIRECT_CALL_SITES_PER_SHARD) {
                throw new IllegalStateException(
                        "low-sensitivity physical leaf fanout verification failed");
            }
            totalCalls += actual.calls;
        }
        if (totalCalls != plan.siteCount()) {
            throw new IllegalStateException(
                    "low-sensitivity final-source call conservation failed");
        }
    }

    private void scanIdentifiers(
            String source,
            IdentifierConsumer consumer) {
        LexicalState state = LexicalState.CODE;
        boolean escaped = false;
        int index = 0;
        while (index < source.length()) {
            char ch = source.charAt(index);
            if (state == LexicalState.LINE_COMMENT) {
                index++;
                if (ch == '\n') {
                    state = LexicalState.CODE;
                }
                continue;
            }
            if (state == LexicalState.BLOCK_COMMENT) {
                index++;
                if (ch == '*'
                        && index < source.length()
                        && source.charAt(index) == '/') {
                    index++;
                    state = LexicalState.CODE;
                }
                continue;
            }
            if (state == LexicalState.STRING
                    || state == LexicalState.CHARACTER) {
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
                    index += 2;
                    state = LexicalState.LINE_COMMENT;
                    continue;
                }
                if (next == '*') {
                    index += 2;
                    state = LexicalState.BLOCK_COMMENT;
                    continue;
                }
            }
            if (ch == '"') {
                index++;
                state = LexicalState.STRING;
                continue;
            }
            if (ch == '\'') {
                index++;
                state = LexicalState.CHARACTER;
                continue;
            }
            if (!identifierStart(ch)) {
                index++;
                continue;
            }
            int end = index + 1;
            while (end < source.length()
                    && identifierPart(source.charAt(end))) {
                end++;
            }
            consumer.accept(source.substring(index, end), index, end);
            index = end;
        }
        if (state == LexicalState.BLOCK_COMMENT
                || state == LexicalState.STRING
                || state == LexicalState.CHARACTER) {
            throw new IllegalStateException(
                    "incomplete generated-C lexical evidence");
        }
    }

    private boolean matchesBefore(
            String source,
            int start,
            String expected) {
        int expectedStart = start - expected.length();
        return expectedStart >= 0
                && source.regionMatches(
                        expectedStart,
                        expected,
                        0,
                        expected.length());
    }

    private boolean matchesAfter(
            String source,
            int end,
            String expected) {
        return end + expected.length() <= source.length()
                && source.regionMatches(
                        end,
                        expected,
                        0,
                        expected.length());
    }

    private boolean directCallPrefix(
            String source,
            int start) {
        int index = start - 1;
        while (index >= 0 && Character.isWhitespace(source.charAt(index))) {
            index--;
        }
        if (index < 0) {
            return true;
        }
        char previous = source.charAt(index);
        return previous != '&'
                && previous != '*'
                && previous != '.'
                && previous != '>';
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

    private static final class Counts {
        private int prototypes;
        private int definitions;
        private int calls;
        private int prototypePosition = Integer.MAX_VALUE;
        private int definitionPosition = Integer.MAX_VALUE;
        private int definitionNameEnd = -1;
        private int firstCallPosition = Integer.MAX_VALUE;
    }

    @FunctionalInterface
    private interface IdentifierConsumer {
        void accept(String token, int start, int end);
    }

    private enum LexicalState {
        CODE,
        STRING,
        CHARACTER,
        LINE_COMMENT,
        BLOCK_COMMENT
    }
}
