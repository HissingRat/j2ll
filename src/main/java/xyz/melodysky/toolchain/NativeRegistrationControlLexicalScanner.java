package xyz.melodysky.toolchain;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Builds one comment/string-free code view and identifier-position index. */
final class NativeRegistrationControlLexicalScanner {
    private static final String TRIGRAPH_THIRD_CHARACTERS =
            "=/'()!<>-";

    Result scan(String source) {
        String raw = Objects.requireNonNull(source, "source");
        rejectTrigraphs(raw);
        String code = codeOnly(splicePhysicalLines(raw));
        return new Result(code, scanIdentifiers(code));
    }

    private void rejectTrigraphs(String source) {
        for (int index = 0; index + 2 < source.length(); index++) {
            if (source.charAt(index) == '?'
                    && source.charAt(index + 1) == '?'
                    && TRIGRAPH_THIRD_CHARACTERS.indexOf(
                            source.charAt(index + 2)) >= 0) {
                throw new IllegalStateException(
                        "native registration control topology audit failed: TRIGRAPH_UNSUPPORTED");
            }
        }
    }

    private String splicePhysicalLines(String source) {
        StringBuilder result = new StringBuilder(source.length());
        for (int index = 0; index < source.length(); index++) {
            char ch = source.charAt(index);
            if (ch == '\\' && index + 1 < source.length()) {
                if (source.charAt(index + 1) == '\n') {
                    index++;
                    continue;
                }
                if (source.charAt(index + 1) == '\r'
                        && index + 2 < source.length()
                        && source.charAt(index + 2) == '\n') {
                    index += 2;
                    continue;
                }
            }
            result.append(ch);
        }
        return result.toString();
    }

    private String codeOnly(String value) {
        StringBuilder result = new StringBuilder(value.length());
        State state = State.CODE;
        boolean escaped = false;
        for (int index = 0; index < value.length(); index++) {
            char ch = value.charAt(index);
            if (state == State.LINE_COMMENT) {
                if (ch == '\n'
                        && (index == 0
                        || value.charAt(index - 1) != '\\')) {
                    result.append(ch);
                    state = State.CODE;
                } else {
                    result.append(ch == '\n' ? '\n' : ' ');
                }
                continue;
            }
            if (state == State.BLOCK_COMMENT) {
                result.append(ch == '\n' ? '\n' : ' ');
                if (ch == '*'
                        && index + 1 < value.length()
                        && value.charAt(index + 1) == '/') {
                    result.append(' ');
                    index++;
                    state = State.CODE;
                }
                continue;
            }
            if (state == State.STRING || state == State.CHARACTER) {
                result.append(ch == '\n' ? '\n' : ' ');
                if (escaped) {
                    escaped = false;
                } else if (ch == '\\') {
                    escaped = true;
                } else if ((state == State.STRING && ch == '"')
                        || (state == State.CHARACTER && ch == '\'')) {
                    state = State.CODE;
                }
                continue;
            }
            if (ch == '/' && index + 1 < value.length()) {
                char next = value.charAt(index + 1);
                if (next == '/' || next == '*') {
                    result.append("  ");
                    index++;
                    state = next == '/'
                            ? State.LINE_COMMENT
                            : State.BLOCK_COMMENT;
                    continue;
                }
            }
            if (ch == '"' || ch == '\'') {
                result.append(' ');
                state = ch == '"' ? State.STRING : State.CHARACTER;
                continue;
            }
            result.append(ch);
        }
        if (state == State.BLOCK_COMMENT
                || state == State.STRING
                || state == State.CHARACTER) {
            throw new IllegalStateException(
                    "native registration control topology audit failed: LEXICAL_EVIDENCE_INCOMPLETE");
        }
        return result.toString();
    }

    private Map<String, List<Integer>> scanIdentifiers(String value) {
        LinkedHashMap<String, ArrayList<Integer>> mutable =
                new LinkedHashMap<>();
        int index = 0;
        while (index < value.length()) {
            char ch = value.charAt(index);
            if (!identifierStart(ch)) {
                index++;
                continue;
            }
            int end = index + 1;
            while (end < value.length()
                    && identifierPart(value.charAt(end))) {
                end++;
            }
            String identifier = value.substring(index, end);
            mutable.computeIfAbsent(
                            identifier,
                            ignored -> new ArrayList<>())
                    .add(index);
            index = end;
        }
        LinkedHashMap<String, List<Integer>> frozen = new LinkedHashMap<>();
        mutable.forEach((identifier, offsets) ->
                frozen.put(identifier, List.copyOf(offsets)));
        return Map.copyOf(frozen);
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

    record Result(
            String code,
            Map<String, List<Integer>> identifierOffsets) {
        Result {
            Objects.requireNonNull(code, "code");
            identifierOffsets = Map.copyOf(Objects.requireNonNull(
                    identifierOffsets,
                    "identifierOffsets"));
        }
    }

    private enum State {
        CODE,
        STRING,
        CHARACTER,
        LINE_COMMENT,
        BLOCK_COMMENT
    }
}
