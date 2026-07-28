package xyz.melodysky.toolchain.nativetext;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Verifies that final native-text ciphertext arrays use a per-site affine
 * physical cursor rather than a direct logical-index read.
 */
final class GeneratedNativeAffineStorageAudit {
    static final String INVALID_AFFINE_CIPHERTEXT_STORAGE =
            "INVALID_AFFINE_CIPHERTEXT_STORAGE";
    static final String EVIDENCE_AFFINE_CIPHERTEXT_STORAGE =
            "AFFINE_CIPHERTEXT_STORAGE";

    private static final String MARKER =
            "J2LL_NATIVE_TEXT_AFFINE_STORAGE";
    private static final Pattern CIPHER = Pattern.compile(
            "\\bstatic\\s+(const\\s+)?unsigned\\s+char\\s+"
                    + "(j2ll_nt_([0-9a-f]{24})_cipher)\\s*"
                    + "\\[[^]]*]\\s*=\\s*\\{");
    private static final Pattern BYTE =
            Pattern.compile("(?i)\\b0x[0-9a-f]{2}\\b");
    private static final Pattern VOLATILE_CIPHER_READ = Pattern.compile(
            "\\(\\(\\s*const\\s+volatile\\s+"
                    + "unsigned\\s+char\\s*\\*\\s*\\)"
                    + "\\s*\\(\\s*"
                    + "(j2ll_nt_[0-9a-f]{24}_cipher)"
                    + "\\s*\\)\\s*\\)"
                    + "\\[\\s*"
                    + "([A-Za-z_][A-Za-z0-9_]*)"
                    + "\\s*]");
    private static final int SITE_WINDOW = 32 * 1024;
    private final NativeTextCipherReferenceAudit referenceAudit =
            new NativeTextCipherReferenceAudit();

    Inspection inspect(String source) {
        Matcher ciphers = CIPHER.matcher(source);
        Map<String, List<String>> readIndexes =
                readIndexes(source);
        boolean contractActive = source.contains(MARKER);
        boolean finalGeneratedSource = source.contains("JNI_OnLoad");
        int cipherCount = 0;
        GeneratedNativeHardeningFinding firstFinding = null;
        while (ciphers.find()) {
            cipherCount++;
            if (!contractActive) {
                if (finalGeneratedSource
                        && firstFinding == null) {
                    firstFinding = finding(
                            source,
                            ciphers.start(),
                            "final generated native C has no affine ciphertext-storage contract marker");
                }
                continue;
            }
            int opening = source.indexOf('{', ciphers.start());
            int closing = source.indexOf("};", opening);
            int length = closing < 0
                    ? 0
                    : countBytes(source, opening, closing);
            String cipher = ciphers.group(2);
            int unexpectedReference =
                    referenceAudit.firstUnexpectedReference(
                            source,
                            cipher,
                            ciphers.start(2),
                            ciphers.end(2),
                            ciphers.group(1) == null);
            if (unexpectedReference >= 0
                    && firstFinding == null) {
                firstFinding = finding(
                        source,
                        unexpectedReference,
                        "native-text ciphertext has an unclassified direct or aliased reference");
            }
            if (!validSite(
                    source,
                    cipher,
                    ciphers.group(3),
                    length,
                    readIndexes.getOrDefault(
                            cipher,
                            List.of()))
                    && firstFinding == null) {
                firstFinding = finding(
                        source,
                        ciphers.start(),
                        "native-text ciphertext is not consumed through a valid per-site affine storage cursor");
            }
        }
        boolean evidence = cipherCount > 0
                && contractActive
                && firstFinding == null;
        return new Inspection(firstFinding, evidence);
    }

    private boolean validSite(
            String source,
            String cipher,
            String token,
            int length,
            List<String> readIndexes) {
        if (length <= 0) {
            return false;
        }
        String storageIndex = "j2ll_nt_s_" + token;
        String quotedStorage = Pattern.quote(storageIndex);
        String quotedCipher = Pattern.quote(cipher);
        int siteStart = source.indexOf(
                "size_t " + storageIndex);
        if (siteStart < 0) {
            return false;
        }
        int siteEnd = Math.min(
                source.length(),
                siteStart + SITE_WINDOW);
        Matcher initialization = Pattern.compile(
                        "\\bsize_t\\s+"
                                + quotedStorage
                                + "\\s*=\\s*\\(size_t\\)\\s*"
                                + "UINT64_C\\((\\d+)\\)\\s*;")
                .matcher(source);
        initialization.region(siteStart, siteEnd);
        if (!initialization.find()
                || !onlyAffineCipherReads(
                        readIndexes,
                        storageIndex)) {
            return false;
        }

        String lengthExpression =
                "sizeof\\(\\s*" + quotedCipher + "\\s*\\)";
        Matcher forward = Pattern.compile(
                        quotedStorage
                                + "\\s*\\+=\\s*UINT64_C\\((\\d+)\\)\\s*;"
                                + "\\s*"
                                + quotedStorage
                                + "\\s*-=\\s*"
                                + quotedStorage
                                + "\\s*>=\\s*"
                                + lengthExpression
                                + "\\s*\\?\\s*"
                                + lengthExpression
                                + "\\s*:\\s*0u\\s*;")
                .matcher(source);
        forward.region(siteStart, siteEnd);
        Matcher reverse = Pattern.compile(
                        quotedStorage
                                + "\\s*\\+=\\s*"
                                + quotedStorage
                                + "\\s*<\\s*UINT64_C\\((\\d+)\\)"
                                + "\\s*\\?\\s*"
                                + lengthExpression
                                + "\\s*:\\s*0u\\s*;"
                                + "\\s*"
                                + quotedStorage
                                + "\\s*-=\\s*UINT64_C\\((\\d+)\\)\\s*;")
                .matcher(source);
        reverse.region(siteStart, siteEnd);

        int initial = parseInt(initialization.group(1));
        if (forward.find()) {
            return validParameters(
                    length,
                    initial,
                    parseInt(forward.group(1)),
                    false);
        }
        if (reverse.find()
                && reverse.group(1).equals(reverse.group(2))) {
            return validParameters(
                    length,
                    initial,
                    parseInt(reverse.group(1)),
                    true);
        }
        return false;
    }

    private boolean onlyAffineCipherReads(
            List<String> readIndexes,
            String storageIndex) {
        if (readIndexes.isEmpty()) {
            return false;
        }
        for (String readIndex : readIndexes) {
            if (!readIndex.equals(storageIndex)) {
                return false;
            }
        }
        return true;
    }

    private Map<String, List<String>> readIndexes(String source) {
        HashMap<String, List<String>> indexes = new HashMap<>();
        Matcher reads = VOLATILE_CIPHER_READ.matcher(source);
        while (reads.find()) {
            indexes.computeIfAbsent(
                            reads.group(1),
                            ignored -> new ArrayList<>())
                    .add(reads.group(2));
        }
        return indexes;
    }

    private boolean validParameters(
            int length,
            int initial,
            int stride,
            boolean reverse) {
        if (initial < 0 || initial >= length) {
            return false;
        }
        if (length == 1) {
            return initial == 0 && stride == 1;
        }
        if (stride <= 0
                || stride >= length
                || !NativeTextStoragePermutation.areCoprime(
                        stride,
                        length)) {
            return false;
        }
        int offset = reverse
                ? (int) Math.floorMod(
                        initial - (long) (length - 1) * stride,
                        (long) length)
                : initial;
        return offset != 0 || stride != 1;
    }

    private int countBytes(
            String source,
            int opening,
            int closing) {
        int count = 0;
        Matcher bytes = BYTE.matcher(
                source.substring(opening + 1, closing));
        while (bytes.find()) {
            count++;
        }
        return count;
    }

    private int parseInt(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            return -1;
        }
    }

    private GeneratedNativeHardeningFinding finding(
            String source,
            int offset,
            String detail) {
        int line = 1;
        for (int index = 0; index < offset; index++) {
            if (source.charAt(index) == '\n') {
                line++;
            }
        }
        return new GeneratedNativeHardeningFinding(
                INVALID_AFFINE_CIPHERTEXT_STORAGE,
                line,
                detail);
    }

    record Inspection(
            GeneratedNativeHardeningFinding finding,
            boolean evidence) {}
}
