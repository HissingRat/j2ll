package xyz.melodysky.toolchain.nativetext;

import java.util.BitSet;
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
    int firstUnexpectedReference(
            String source,
            String cipher,
            int declarationNameStart,
            int declarationNameEnd,
            boolean mutable) {
        String structural = NativeTextCSourceMasker.maskNonCode(source);
        BitSet allowed = new BitSet(structural.length());
        allowed.set(declarationNameStart, declarationNameEnd);
        allowGroup(
                structural,
                Pattern.compile(
                        "\\bsizeof\\s*\\(\\s*("
                                + Pattern.quote(cipher)
                                + ")\\s*\\)"),
                1,
                allowed);
        allowGroup(
                structural,
                Pattern.compile(
                        "\\(\\(\\s*const\\s+volatile\\s+unsigned\\s+char\\s*\\*"
                                + "\\s*\\)\\s*\\(\\s*("
                                + Pattern.quote(cipher)
                                + ")\\s*\\)\\s*\\)\\s*"
                                + "\\[\\s*[A-Za-z_][A-Za-z0-9_]*\\s*]"),
                1,
                allowed);
        if (mutable) {
            allowGroup(
                    structural,
                    Pattern.compile(
                            "\\(\\(\\s*unsigned\\s+char\\s*\\*\\s*\\)"
                                    + "\\s*\\(\\s*("
                                    + Pattern.quote(cipher)
                                    + ")\\s*\\)\\s*\\)\\s*"
                                    + "\\[\\s*[A-Za-z_][A-Za-z0-9_]*\\s*]"
                                    + "\\s*="),
                    1,
                    allowed);
            allowGroup(
                    structural,
                    Pattern.compile(
                            "\\(\\s*char\\s*\\*\\s*\\)\\s*("
                                    + Pattern.quote(cipher)
                                    + ")(?=\\s*[,;)])"),
                    1,
                    allowed);
        }

        Matcher references = Pattern.compile(
                        "\\b" + Pattern.quote(cipher) + "\\b")
                .matcher(structural);
        while (references.find()) {
            if (allowed.nextClearBit(references.start())
                    < references.end()) {
                return references.start();
            }
        }
        return -1;
    }

    private void allowGroup(
            String source,
            Pattern pattern,
            int group,
            BitSet allowed) {
        Matcher matcher = pattern.matcher(source);
        while (matcher.find()) {
            allowed.set(matcher.start(group), matcher.end(group));
        }
    }
}
