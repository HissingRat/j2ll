package xyz.melodysky.toolchain.nativetext;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Structural checks for bulk-recoverable generated-C metadata layouts.
 */
final class GeneratedNativeMetadataStructureAudit {
    static final String LEGACY_CENTRALIZED_RUNTIME_METADATA_TABLE =
            "LEGACY_CENTRALIZED_RUNTIME_METADATA_TABLE";
    static final String STRUCTURAL_SENSITIVE_TEXT_DIRECTORY =
            "STRUCTURAL_SENSITIVE_TEXT_DIRECTORY";
    static final String SINGLE_DECODER_BULK_TEXT_LIFETIME =
            "SINGLE_DECODER_BULK_TEXT_LIFETIME";
    static final String PERSISTENT_DECODED_SENSITIVE_TEXT =
            "PERSISTENT_DECODED_SENSITIVE_TEXT";
    static final String REUSABLE_NATIVE_TEXT_DECODER_FANOUT =
            "REUSABLE_NATIVE_TEXT_DECODER_FANOUT";
    static final String FIXED_NATIVE_TEXT_DECODER_SHAPE =
            "FIXED_NATIVE_TEXT_DECODER_SHAPE";
    static final String ADJACENT_NATIVE_TEXT_SEED_CIPHER =
            "ADJACENT_NATIVE_TEXT_SEED_CIPHER";
    static final String OPTIMIZER_FOLDABLE_NATIVE_TEXT =
            "OPTIMIZER_FOLDABLE_NATIVE_TEXT";
    static final String CROSS_FUNCTION_NATIVE_TEXT_TUPLE_REUSE =
            "CROSS_FUNCTION_NATIVE_TEXT_TUPLE_REUSE";

    private static final int BULK_DECODER_ARRAY_THRESHOLD = 8;
    private static final Pattern LEGACY_TABLE_NAME = Pattern.compile(
            "\\b(?:j2ll_(?:method|field|class|lambda)_table"
                    + "|j2ll_reflection_[A-Za-z0-9_]*_table)\\b");
    private static final Pattern ARRAY_INITIALIZER = Pattern.compile(
            "\\bstatic\\s+(?:const\\s+)?"
                    + "(?:struct\\s+)?[A-Za-z_][A-Za-z0-9_]*(?:\\s*\\*)?\\s+"
                    + "([A-Za-z_][A-Za-z0-9_]*)\\s*"
                    + "\\[[^]]*]\\s*=\\s*\\{");
    private static final Pattern NATIVE_TEXT_REFERENCE = Pattern.compile(
            "\\bj2ll_nt_[0-9a-f]{24}(?:_cipher)?\\b");
    private static final Pattern TEXT_POINTER_FIELD = Pattern.compile(
            "\\b(?:const\\s+)?char\\s*\\*\\s*[A-Za-z_][A-Za-z0-9_]*\\s*;");
    private static final Pattern TOKEN_FIELD = Pattern.compile(
            "\\b(?:u?int64_t|jlong|unsigned\\s+long(?:\\s+long)?)"
                    + "\\s+[A-Za-z_][A-Za-z0-9_]*token[A-Za-z0-9_]*\\s*;");
    private static final Pattern STRUCT_DEFINITION = Pattern.compile(
            "\\b(?:typedef\\s+)?struct(?:\\s+[A-Za-z_][A-Za-z0-9_]*)?"
                    + "\\s*\\{");
    private static final Pattern DECODER = Pattern.compile(
            "\\bstatic\\s+void\\s+"
                    + "(j2ll_gcf_(?:low_)?decode_[0-9a-f]+(?:_[0-9a-f]+)*)"
                    + "\\s*\\([^)]*\\)\\s*\\{");
    private static final Pattern TUPLE_USE = Pattern.compile(
            "\\bj2ll_nt_use_([0-9a-f]{24})\\s*\\(\\s*\\)");

    List<GeneratedNativeHardeningFinding> inspect(String structural) {
        ArrayList<GeneratedNativeHardeningFinding> findings =
                new ArrayList<>();
        Matcher legacy = LEGACY_TABLE_NAME.matcher(structural);
        if (legacy.find()) {
            findings.add(finding(
                    LEGACY_CENTRALIZED_RUNTIME_METADATA_TABLE,
                    structural,
                    legacy.start(),
                    "legacy centralized class/method/field/reflection/lambda metadata table is present"));
        }

        inspectArrayDirectories(structural, findings);
        inspectTokenStructs(structural, findings);
        inspectDecoders(structural, findings);
        inspectCodecStructure(structural, findings);
        inspectTupleUseScope(structural, findings);
        return findings;
    }

    private void inspectTupleUseScope(
            String structural,
            List<GeneratedNativeHardeningFinding> findings) {
        List<GeneratedCFragmentLexer.FunctionBody> functions;
        try {
            functions = new GeneratedCFragmentLexer()
                    .scan(structural)
                    .functionBodies();
        } catch (IllegalArgumentException ignored) {
            // Syntax/shape validation belongs to the generated-C compiler
            // boundary. This audit only adds a finding when ownership can be
            // proven from a structurally valid function layout.
            return;
        }
        java.util.HashMap<String, Integer> ownerByTuple =
                new java.util.HashMap<>();
        for (int functionIndex = 0;
                functionIndex < functions.size();
                functionIndex++) {
            GeneratedCFragmentLexer.FunctionBody function =
                    functions.get(functionIndex);
            Matcher uses = TUPLE_USE.matcher(structural);
            uses.region(function.start(), function.end());
            while (uses.find()) {
                Integer previous = ownerByTuple.putIfAbsent(
                        uses.group(1),
                        functionIndex);
                if (previous != null && previous != functionIndex) {
                    findings.add(finding(
                            CROSS_FUNCTION_NATIVE_TEXT_TUPLE_REUSE,
                            structural,
                            uses.start(),
                            "one activation-local native-text tuple is referenced from multiple C functions"));
                    return;
                }
            }
        }
    }

    private void inspectCodecStructure(
            String structural,
            List<GeneratedNativeHardeningFinding> findings) {
        NativeTextSourceMetrics metrics =
                new NativeTextSourceScanner().scan(structural);
        if (metrics.runtimeBoundCipherReadCount()
                < metrics.cipherArrayCount()) {
            findings.add(finding(
                    OPTIMIZER_FOLDABLE_NATIVE_TEXT,
                    structural,
                    metrics.firstMissingRuntimeBoundCipherOffset(),
                    "native-text ciphertext is read without a volatile runtime boundary, so an optimizing compiler can reconstitute plaintext"));
        }
        if (metrics.largestDecoderCipherFanout() >= 2) {
            findings.add(finding(
                    REUSABLE_NATIVE_TEXT_DECODER_FANOUT,
                    structural,
                    metrics.firstDecoderFanoutOffset(),
                    "one structurally identified native-text decoder reaches multiple ciphertext arrays"));
        }
        if (metrics.fixedDecoderShapeOccurrences() > 0) {
            findings.add(finding(
                    FIXED_NATIVE_TEXT_DECODER_SHAPE,
                    structural,
                    metrics.firstFixedDecoderShapeOffset(),
                    "the fixed SplitMix-shaped native-text decoder is present"));
        }
        if (metrics.adjacentSeedCipherOccurrences() > 0) {
            findings.add(finding(
                    ADJACENT_NATIVE_TEXT_SEED_CIPHER,
                    structural,
                    metrics.firstAdjacentSeedCipherOffset(),
                    "ciphertext use is colocated with an adjacent XOR seed-share pattern"));
        }
    }

    private void inspectArrayDirectories(
            String structural,
            List<GeneratedNativeHardeningFinding> findings) {
        Matcher matcher = ARRAY_INITIALIZER.matcher(structural);
        while (matcher.find()) {
            int opening = structural.indexOf('{', matcher.start());
            int closing = matchingBrace(structural, opening);
            if (closing < 0) {
                continue;
            }
            String body = structural.substring(opening, closing + 1);
            if (count(NATIVE_TEXT_REFERENCE, body) >= 2) {
                findings.add(finding(
                        STRUCTURAL_SENSITIVE_TEXT_DIRECTORY,
                        structural,
                        matcher.start(),
                        "one generated-C array directory references multiple independently encoded texts"));
                return;
            }
        }
    }

    private void inspectTokenStructs(
            String structural,
            List<GeneratedNativeHardeningFinding> findings) {
        Matcher matcher = STRUCT_DEFINITION.matcher(structural);
        while (matcher.find()) {
            int opening = structural.indexOf('{', matcher.start());
            int closing = matchingBrace(structural, opening);
            if (closing < 0) {
                continue;
            }
            String body = structural.substring(opening, closing + 1);
            if (TOKEN_FIELD.matcher(body).find()
                    && count(TEXT_POINTER_FIELD, body) >= 2) {
                findings.add(finding(
                        STRUCTURAL_SENSITIVE_TEXT_DIRECTORY,
                        structural,
                        matcher.start(),
                        "one token-indexed structure exposes multiple text-pointer fields"));
                return;
            }
        }
    }

    private void inspectDecoders(
            String structural,
            List<GeneratedNativeHardeningFinding> findings) {
        Matcher matcher = DECODER.matcher(structural);
        while (matcher.find()) {
            int opening = structural.indexOf('{', matcher.start());
            int closing = matchingBrace(structural, opening);
            if (closing < 0) {
                continue;
            }
            String body = structural.substring(opening, closing + 1);
            int decodedArrays = distinctCount(
                    NATIVE_TEXT_REFERENCE,
                    body);
            findings.add(finding(
                    PERSISTENT_DECODED_SENSITIVE_TEXT,
                    structural,
                    matcher.start(),
                    "generated-C decoder leaves plaintext in process-lifetime arrays"));
            if (decodedArrays >= BULK_DECODER_ARRAY_THRESHOLD) {
                findings.add(finding(
                        SINGLE_DECODER_BULK_TEXT_LIFETIME,
                        structural,
                        matcher.start(),
                        "one generated-C decoder covers a bulk set of text arrays"));
            }
        }
    }

    private int count(Pattern pattern, String value) {
        int count = 0;
        Matcher matcher = pattern.matcher(value);
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    private int distinctCount(Pattern pattern, String value) {
        Set<String> values = new HashSet<>();
        Matcher matcher = pattern.matcher(value);
        while (matcher.find()) {
            values.add(matcher.group());
        }
        return values.size();
    }

    private int matchingBrace(String source, int opening) {
        if (opening < 0) {
            return -1;
        }
        int depth = 0;
        for (int index = opening; index < source.length(); index++) {
            char ch = source.charAt(index);
            if (ch == '{') {
                depth++;
            } else if (ch == '}' && --depth == 0) {
                return index;
            }
        }
        return -1;
    }

    private GeneratedNativeHardeningFinding finding(
            String code,
            String source,
            int offset,
            String detail) {
        int line = 1;
        for (int index = 0; index < offset; index++) {
            if (source.charAt(index) == '\n') {
                line++;
            }
        }
        return new GeneratedNativeHardeningFinding(code, line, detail);
    }
}
