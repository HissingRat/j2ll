package xyz.melodysky.toolchain.nativetext;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Rejects unclassified production-cipher references.
 *
 * <p>Const ciphertext may only be declared, sized and read by the canonical
 * volatile affine cursor. Mutable low-sensitivity lazy-once storage may also
 * receive the completed plaintext copy and be passed as the exact decoded
 * pointer value.</p>
 */
final class NativeTextCipherReferenceAudit {
    private static final String CIPHER_IDENTIFIER =
            "j2ll_nt_[0-9a-f]{24}_cipher";
    private static final String C_IDENTIFIER =
            "[A-Za-z_][A-Za-z0-9_]*";
    private static final Pattern CLASSIFIED_REFERENCES = Pattern.compile(
            "\\bsizeof\\s*\\(\\s*("
                    + CIPHER_IDENTIFIER
                    + ")\\s*\\)"
                    + "|\\(\\(\\s*const\\s+volatile\\s+unsigned\\s+char\\s*\\*"
                    + "\\s*\\)\\s*\\(\\s*("
                    + CIPHER_IDENTIFIER
                    + ")\\s*\\)\\s*\\)\\s*"
                    + "\\[\\s*("
                    + C_IDENTIFIER
                    + ")\\s*]"
                    + "|\\(\\(\\s*unsigned\\s+char\\s*\\*\\s*\\)"
                    + "\\s*\\(\\s*("
                    + CIPHER_IDENTIFIER
                    + ")\\s*\\)\\s*\\)\\s*"
                    + "\\[\\s*"
                    + C_IDENTIFIER
                    + "\\s*]\\s*="
                    + "|\\(\\s*char\\s*\\*\\s*\\)\\s*("
                    + CIPHER_IDENTIFIER
                    + ")(?=\\s*[,;)])");
    private static final Pattern REFERENCES = Pattern.compile(
            "\\b(" + CIPHER_IDENTIFIER + ")\\b");

    Index index(String source) {
        String structural = NativeTextCSourceMasker.maskNonCode(source);
        HashMap<Integer, Integer> classifications = new HashMap<>();
        HashMap<String, List<String>> readIndexes = new HashMap<>();
        Matcher classified = CLASSIFIED_REFERENCES.matcher(structural);
        while (classified.find()) {
            if (classified.group(1) != null) {
                allow(
                        classifications,
                        classified.start(1),
                        ReferenceKind.ALWAYS_ALLOWED);
            } else if (classified.group(2) != null) {
                allow(
                        classifications,
                        classified.start(2),
                        ReferenceKind.ALWAYS_ALLOWED);
                readIndexes.computeIfAbsent(
                                classified.group(2),
                                ignored -> new ArrayList<>())
                        .add(classified.group(3));
            } else if (classified.group(4) != null) {
                allow(
                        classifications,
                        classified.start(4),
                        ReferenceKind.MUTABLE_ONLY);
            } else if (classified.group(5) != null) {
                allow(
                        classifications,
                        classified.start(5),
                        ReferenceKind.MUTABLE_ONLY);
            }
        }

        HashMap<String, List<Reference>> references = new HashMap<>();
        Matcher matcher = REFERENCES.matcher(structural);
        while (matcher.find()) {
            references.computeIfAbsent(
                            matcher.group(1),
                            ignored -> new ArrayList<>())
                    .add(new Reference(
                            matcher.start(1),
                            matcher.end(1),
                            classifications.getOrDefault(
                                    matcher.start(1),
                                    0)));
        }
        return new Index(references, readIndexes);
    }

    int firstUnexpectedReference(
            Index index,
            String cipher,
            int declarationNameStart,
            int declarationNameEnd,
            boolean mutable) {
        for (Reference reference : index.references(cipher)) {
            if (reference.start() >= declarationNameStart
                    && reference.end() <= declarationNameEnd) {
                continue;
            }
            if (reference.allowed(ReferenceKind.ALWAYS_ALLOWED)
                    || (mutable
                            && reference.allowed(
                                    ReferenceKind.MUTABLE_ONLY))) {
                continue;
            }
            return reference.start();
        }
        return -1;
    }

    private void allow(
            Map<Integer, Integer> classifications,
            int offset,
            ReferenceKind kind) {
        classifications.merge(offset, kind.mask, (left, right) -> left | right);
    }

    static final class Index {
        private final Map<String, List<Reference>> references;
        private final Map<String, List<String>> readIndexes;

        private Index(
                Map<String, List<Reference>> references,
                Map<String, List<String>> readIndexes) {
            this.references = copyLists(references);
            this.readIndexes = copyLists(readIndexes);
        }

        List<String> readIndexes(String cipher) {
            return readIndexes.getOrDefault(cipher, List.of());
        }

        private List<Reference> references(String cipher) {
            return references.getOrDefault(cipher, List.of());
        }

        private static <T> Map<String, List<T>> copyLists(
                Map<String, List<T>> values) {
            HashMap<String, List<T>> copy = new HashMap<>();
            values.forEach((key, entries) -> copy.put(key, List.copyOf(entries)));
            return Map.copyOf(copy);
        }
    }

    private record Reference(int start, int end, int classifications) {
        private boolean allowed(ReferenceKind kind) {
            return (classifications & kind.mask) != 0;
        }
    }

    private enum ReferenceKind {
        ALWAYS_ALLOWED(1),
        MUTABLE_ONLY(2);

        private final int mask;

        ReferenceKind(int mask) {
            this.mask = mask;
        }
    }
}
