package xyz.melodysky.toolchain;

import xyz.melodysky.toolchain.nativetext.NativeScratchZeroizerSource;

/** Verifies the closed activation-local body schema of one physical leaf. */
final class HostJniLowSensitivityThrowShardBodyVerifier {
    void verify(
            String source,
            int definitionNameEnd) {
        int openingBrace = source.indexOf('{', definitionNameEnd);
        if (openingBrace < 0) {
            throw new IllegalStateException(
                    "low-sensitivity physical leaf definition has no body");
        }
        int closingBrace = matchingBrace(source, openingBrace);
        String body = source.substring(openingBrace + 1, closingBrace);
        if (occurrences(body, "j2ll_throw_new(env, ") != 1
                || occurrences(
                        body,
                        "__attribute__((cleanup("
                                + NativeScratchZeroizerSource
                                        .CLEANUP_FUNCTION_NAME
                                + ")))") != 1) {
            throw new IllegalStateException(
                    "low-sensitivity physical leaf lacks an independent activation-local throw tuple");
        }
    }

    private int matchingBrace(
            String source,
            int openingBrace) {
        int depth = 0;
        LexicalState state = LexicalState.CODE;
        boolean escaped = false;
        for (int index = openingBrace; index < source.length(); index++) {
            char ch = source.charAt(index);
            if (state == LexicalState.LINE_COMMENT) {
                if (ch == '\n') {
                    state = LexicalState.CODE;
                }
                continue;
            }
            if (state == LexicalState.BLOCK_COMMENT) {
                if (ch == '*'
                        && index + 1 < source.length()
                        && source.charAt(index + 1) == '/') {
                    index++;
                    state = LexicalState.CODE;
                }
                continue;
            }
            if (state == LexicalState.STRING
                    || state == LexicalState.CHARACTER) {
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
                    index++;
                    state = LexicalState.LINE_COMMENT;
                    continue;
                }
                if (next == '*') {
                    index++;
                    state = LexicalState.BLOCK_COMMENT;
                    continue;
                }
            }
            if (ch == '"') {
                state = LexicalState.STRING;
            } else if (ch == '\'') {
                state = LexicalState.CHARACTER;
            } else if (ch == '{') {
                depth++;
            } else if (ch == '}' && --depth == 0) {
                return index;
            }
        }
        throw new IllegalStateException(
                "low-sensitivity physical leaf definition body is incomplete");
    }

    private int occurrences(
            String value,
            String needle) {
        int count = 0;
        int offset = 0;
        while ((offset = value.indexOf(needle, offset)) >= 0) {
            count++;
            offset += needle.length();
        }
        return count;
    }

    private enum LexicalState {
        CODE,
        STRING,
        CHARACTER,
        LINE_COMMENT,
        BLOCK_COMMENT
    }
}
