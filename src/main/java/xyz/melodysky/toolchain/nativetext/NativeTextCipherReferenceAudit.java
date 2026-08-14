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
 * <p>Ciphertext may only be declared as immutable storage, sized and read by
 * the canonical volatile affine cursor. A mutable declaration, in-place write
 * or direct pointer use is never a classified production reference.</p>
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
                    + ")\\s*]");
    private static final Pattern REFERENCES = Pattern.compile(
            "\\b(" + CIPHER_IDENTIFIER + ")\\b");

    Index index(String source) {
        String structural = NativeTextCSourceMasker.maskNonCode(source);
        HashMap<Integer, Boolean> classifications = new HashMap<>();
        HashMap<String, List<String>> readIndexes = new HashMap<>();
        Matcher classified = CLASSIFIED_REFERENCES.matcher(structural);
        while (classified.find()) {
            if (classified.group(1) != null) {
                classifications.put(classified.start(1), true);
            } else if (classified.group(2) != null) {
                classifications.put(classified.start(2), true);
                readIndexes.computeIfAbsent(
                                classified.group(2),
                                ignored -> new ArrayList<>())
                        .add(classified.group(3));
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
                            classifications.containsKey(matcher.start(1))));
        }
        return new Index(references, readIndexes);
    }

    int firstUnexpectedReference(
            Index index,
            String cipher,
            int declarationNameStart,
            int declarationNameEnd) {
        for (Reference reference : index.references(cipher)) {
            if (reference.start() >= declarationNameStart
                    && reference.end() <= declarationNameEnd) {
                continue;
            }
            if (reference.classified()) {
                continue;
            }
            return reference.start();
        }
        return -1;
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

    private record Reference(int start, int end, boolean classified) {}
}
