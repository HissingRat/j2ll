package xyz.melodysky.toolchain;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Small lexical index used by the registration topology closure gate. */
final class NativeRegistrationControlSourceIndex {
    private final String code;
    private final Map<String, List<Integer>> identifiers;

    NativeRegistrationControlSourceIndex(String source) {
        NativeRegistrationControlLexicalScanner.Result result =
                new NativeRegistrationControlLexicalScanner().scan(
                        Objects.requireNonNull(source, "source"));
        this.code = result.code();
        this.identifiers = result.identifierOffsets();
    }

    int identifierCount(String identifier) {
        return identifiers.getOrDefault(identifier, List.of()).size();
    }

    int firstIdentifierOffset(String identifier) {
        List<Integer> offsets = identifiers.getOrDefault(
                identifier,
                List.of());
        return offsets.isEmpty() ? -1 : offsets.get(0);
    }

    List<Integer> identifierOffsets(String identifier) {
        return identifiers.getOrDefault(identifier, List.of());
    }

    String codeView() {
        return code;
    }

    String functionBody(String header) {
        int headerStart = uniqueEvidenceOffset(header);
        if (headerStart < 0) {
            return null;
        }
        int openingBrace = headerStart + header.length() - 1;
        int closingBrace = matchingBrace(openingBrace);
        return closingBrace < 0
                ? null
                : code.substring(openingBrace + 1, closingBrace);
    }

    int functionEndOffset(String header) {
        int headerStart = uniqueEvidenceOffset(header);
        if (headerStart < 0) {
            return -1;
        }
        int closingBrace = matchingBrace(
                headerStart + header.length() - 1);
        return closingBrace < 0 ? -1 : closingBrace + 1;
    }

    int codeCountExact(String needle) {
        int count = 0;
        int offset = 0;
        while ((offset = exactIndexOf(code, needle, offset)) >= 0) {
            count++;
            offset += needle.length();
        }
        return count;
    }

    int codeCountExactAtIdentifier(
            String needle,
            String identifier) {
        int relative = needle.indexOf(identifier);
        if (relative < 0
                || needle.indexOf(identifier, relative + identifier.length())
                        >= 0) {
            throw new IllegalArgumentException(
                    "registration control evidence must contain its identifier exactly once");
        }
        int count = 0;
        for (int tokenOffset : identifiers.getOrDefault(
                identifier,
                List.of())) {
            int candidate = tokenOffset - relative;
            if (matchesExactAt(code, needle, candidate)) {
                count++;
            }
        }
        return count;
    }

    static int exactIndexOf(
            String value,
            String needle,
            int fromIndex) {
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(needle, "needle");
        if (needle.isEmpty()) {
            throw new IllegalArgumentException(
                    "registration control evidence must not be empty");
        }
        int offset = Math.max(0, fromIndex);
        while ((offset = value.indexOf(needle, offset)) >= 0) {
            int end = offset + needle.length();
            boolean leftBoundary = !identifierPart(needle.charAt(0))
                    || offset == 0
                    || !identifierPart(value.charAt(offset - 1));
            boolean rightBoundary = !identifierPart(
                    needle.charAt(needle.length() - 1))
                    || end == value.length()
                    || !identifierPart(value.charAt(end));
            if (leftBoundary && rightBoundary) {
                return offset;
            }
            offset++;
        }
        return -1;
    }

    private int uniqueEvidenceOffset(String evidence) {
        String identifier = functionIdentifier(evidence);
        int relative = evidence.indexOf(identifier);
        int found = -1;
        for (int tokenOffset : identifiers.getOrDefault(
                identifier,
                List.of())) {
            int candidate = tokenOffset - relative;
            if (!matchesExactAt(code, evidence, candidate)) {
                continue;
            }
            if (found >= 0) {
                return -1;
            }
            found = candidate;
        }
        return found;
    }

    private String functionIdentifier(String header) {
        int parenthesis = header.indexOf('(');
        if (parenthesis <= 0) {
            throw new IllegalArgumentException(
                    "registration function header is invalid");
        }
        int end = parenthesis;
        int start = end;
        while (start > 0
                && identifierPart(header.charAt(start - 1))) {
            start--;
        }
        if (start == end || !identifierStart(header.charAt(start))) {
            throw new IllegalArgumentException(
                    "registration function header has no identifier");
        }
        return header.substring(start, end);
    }

    private static boolean matchesExactAt(
            String value,
            String needle,
            int offset) {
        if (offset < 0
                || offset + needle.length() > value.length()
                || !value.regionMatches(offset, needle, 0, needle.length())) {
            return false;
        }
        int end = offset + needle.length();
        boolean leftBoundary = !identifierPart(needle.charAt(0))
                || offset == 0
                || !identifierPart(value.charAt(offset - 1));
        boolean rightBoundary = !identifierPart(
                needle.charAt(needle.length() - 1))
                || end == value.length()
                || !identifierPart(value.charAt(end));
        return leftBoundary && rightBoundary;
    }

    private int matchingBrace(int openingBrace) {
        int depth = 0;
        for (int index = openingBrace; index < code.length(); index++) {
            char ch = code.charAt(index);
            if (ch == '{') {
                depth++;
            } else if (ch == '}' && --depth == 0) {
                return index;
            }
        }
        return -1;
    }

    private static boolean identifierStart(char ch) {
        return ch == '_'
                || (ch >= 'a' && ch <= 'z')
                || (ch >= 'A' && ch <= 'Z');
    }

    private static boolean identifierPart(char ch) {
        return identifierStart(ch)
                || (ch >= '0' && ch <= '9');
    }

}
