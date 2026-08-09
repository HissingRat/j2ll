package xyz.melodysky.toolchain.nativetext;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Static hardening audit for generated native C.
 *
 * <p>The scanner reports stable codes without copying recovered plaintext into
 * findings. It is intentionally independent from pipeline failure policy.</p>
 */
public final class GeneratedNativeHardeningAudit {
    public static final String LEGACY_GLOBAL_METADATA_DIRECTORY =
            "LEGACY_GLOBAL_METADATA_DIRECTORY";
    public static final String LEGACY_DECODE_ALL_ROUTINE =
            "LEGACY_DECODE_ALL_ROUTINE";
    public static final String EXPORTED_AGGREGATE_REGISTRATION =
            "EXPORTED_AGGREGATE_REGISTRATION";
    public static final String FALLBACK_BYTECODE_CARRIER =
            "FALLBACK_BYTECODE_CARRIER";
    public static final String CLASSFILE_MAGIC_CARRIER =
            "CLASSFILE_MAGIC_CARRIER";
    public static final String PLAINTEXT_REGISTRATION_TABLE =
            "PLAINTEXT_REGISTRATION_TABLE";
    public static final String PLAINTEXT_BUSINESS_STRING_TABLE =
            "PLAINTEXT_BUSINESS_STRING_TABLE";
    public static final String CENTRALIZED_BUSINESS_STRING_TABLE =
            "CENTRALIZED_BUSINESS_STRING_TABLE";
    public static final String CENTRALIZED_BUSINESS_STRING_DISPATCHER =
            "CENTRALIZED_BUSINESS_STRING_DISPATCHER";
    public static final String COLOCATED_KEY_CIPHER_TABLE =
            "COLOCATED_KEY_CIPHER_TABLE";
    public static final String LEGACY_CENTRALIZED_RUNTIME_METADATA_TABLE =
            GeneratedNativeMetadataStructureAudit
                    .LEGACY_CENTRALIZED_RUNTIME_METADATA_TABLE;
    public static final String STRUCTURAL_SENSITIVE_TEXT_DIRECTORY =
            GeneratedNativeMetadataStructureAudit
                    .STRUCTURAL_SENSITIVE_TEXT_DIRECTORY;
    public static final String SINGLE_DECODER_BULK_TEXT_LIFETIME =
            GeneratedNativeMetadataStructureAudit
                    .SINGLE_DECODER_BULK_TEXT_LIFETIME;
    public static final String PERSISTENT_DECODED_SENSITIVE_TEXT =
            GeneratedNativeMetadataStructureAudit
                    .PERSISTENT_DECODED_SENSITIVE_TEXT;
    public static final String REUSABLE_NATIVE_TEXT_DECODER_FANOUT =
            GeneratedNativeMetadataStructureAudit
                    .REUSABLE_NATIVE_TEXT_DECODER_FANOUT;
    public static final String FIXED_NATIVE_TEXT_DECODER_SHAPE =
            GeneratedNativeMetadataStructureAudit
                    .FIXED_NATIVE_TEXT_DECODER_SHAPE;
    public static final String ADJACENT_NATIVE_TEXT_SEED_CIPHER =
            GeneratedNativeMetadataStructureAudit
                    .ADJACENT_NATIVE_TEXT_SEED_CIPHER;
    public static final String OPTIMIZER_FOLDABLE_NATIVE_TEXT =
            GeneratedNativeMetadataStructureAudit
                    .OPTIMIZER_FOLDABLE_NATIVE_TEXT;
    public static final String STABLE_REGISTRATION_DIAGNOSTIC =
            GeneratedNativeRegistrationAnchorAudit
                    .STABLE_REGISTRATION_DIAGNOSTIC;
    public static final String INVALID_AFFINE_CIPHERTEXT_STORAGE =
            GeneratedNativeAffineStorageAudit
                    .INVALID_AFFINE_CIPHERTEXT_STORAGE;
    public static final String CROSS_FUNCTION_NATIVE_TEXT_TUPLE_REUSE =
            GeneratedNativeMetadataStructureAudit
                    .CROSS_FUNCTION_NATIVE_TEXT_TUPLE_REUSE;

    public static final String EVIDENCE_CALL_LOCAL_TEXT_SCRATCH =
            "CALL_LOCAL_TEXT_SCRATCH";
    public static final String EVIDENCE_CALL_LOCAL_TEXT_CLEANUP =
            "CALL_LOCAL_TEXT_EXIT_CLEANUP";
    public static final String EVIDENCE_LOW_SENSITIVITY_LAZY_ONCE =
            "LOW_SENSITIVITY_RUNTIME_TEXT_LAZY_ONCE";
    /**
     * Compatibility name retained for focused callers; generic lazy decoding
     * is no longer accepted as positive hardening evidence.
     */
    @Deprecated
    public static final String EVIDENCE_SCOPE_LOCAL_DECODER =
            "SCOPE_LOCAL_TEXT_DECODER";
    /**
     * Compatibility name retained for focused callers; lazy-once is only
     * evidence when explicitly classified as low-sensitivity runtime text.
     */
    @Deprecated
    public static final String EVIDENCE_THREAD_SAFE_ONCE =
            "THREAD_SAFE_TEXT_DECODE_ONCE";
    public static final String EVIDENCE_SCRATCH_ZEROIZER =
            "NATIVE_TEXT_ZEROIZER_PRESENT";
    public static final String EVIDENCE_ONLY_JNI_ONLOAD_EXPORTED =
            "ONLY_JNI_ONLOAD_EXPORTED";
    public static final String EVIDENCE_INTERNAL_AGGREGATE_REGISTRATION =
            "AGGREGATE_REGISTRATION_INTERNAL";
    public static final String EVIDENCE_SITE_BOUND_TEXT_CODEC =
            "SITE_BOUND_NATIVE_TEXT_CODEC";
    public static final String EVIDENCE_NATIVE_TEXT_CODEC_DIVERSITY =
            "NATIVE_TEXT_CODEC_FAMILY_DIVERSITY";
    public static final String EVIDENCE_RUNTIME_BOUND_CIPHER_READ =
            "RUNTIME_BOUND_NATIVE_TEXT_CIPHER_READ";
    public static final String EVIDENCE_AFFINE_CIPHERTEXT_STORAGE =
            GeneratedNativeAffineStorageAudit
                    .EVIDENCE_AFFINE_CIPHERTEXT_STORAGE;

    private static final Pattern EXPORTED_FUNCTION = Pattern.compile(
            "\\bJNIEXPORT\\b(?:(?![;{}]).)*?\\b([A-Za-z_][A-Za-z0-9_]*)\\s*\\(",
            Pattern.DOTALL);
    private static final Pattern REGISTRATION_TABLE = Pattern.compile(
            "\\bJNINativeMethod\\s+[A-Za-z_][A-Za-z0-9_]*\\s*"
                    + "\\[[^]]*]\\s*=\\s*\\{");
    private static final Pattern PLAINTEXT_BUSINESS_TABLE = Pattern.compile(
            "\\bj2ll_string_constant_table\\s*\\[\\s*]\\s*=\\s*\\{");
    private static final Pattern CENTRAL_BUSINESS_DISPATCHER = Pattern.compile(
            "\\bjobject\\s+j2ll_rt_string_constant\\s*\\("
                    + "\\s*JNIEnv\\s*\\*\\s*[A-Za-z_][A-Za-z0-9_]*\\s*,"
                    + "\\s*(?:u?int64_t|jlong)\\s+[A-Za-z_][A-Za-z0-9_]*\\s*\\)");
    private static final Pattern INTERNAL_AGGREGATE = Pattern.compile(
            "\\bstatic\\s+jint\\s+j2ll_register(?:_[0-9a-f]+)?\\s*\\(");
    private static final Pattern CLASS_MAGIC_TOKEN = Pattern.compile(
            "(?i)\\bCAFEBABE\\b");
    private static final Pattern NAMED_CLASSFILE_BYTE_ARRAY = Pattern.compile(
            "(?is)\\b(?:static\\s+)?(?:const\\s+)?"
                    + "(?:unsigned\\s+char|uint8_t|jbyte)\\s+"
                    + "[A-Za-z_][A-Za-z0-9_]*"
                    + "(?:class|bytecode|blob)[A-Za-z0-9_]*\\s*"
                    + "\\[[^]]*]\\s*=\\s*\\{"
                    + "(?:(?!}).){0,512}?"
                    + "0xCA\\s*,\\s*0xFE\\s*,\\s*0xBA\\s*,\\s*0xBE");

    public GeneratedNativeHardeningAuditResult audit(String source) {
        return audit(source, GeneratedNativeHardeningProgressListener.none());
    }

    public GeneratedNativeHardeningAuditResult audit(
            String source,
            GeneratedNativeHardeningProgressListener progressListener) {
        if (source == null) {
            throw new NullPointerException("source");
        }
        if (progressListener == null) {
            throw new NullPointerException("progressListener");
        }
        String commentFree = maskComments(source, false);
        String structural = maskComments(source, true);
        GeneratedNativeAffineStorageAudit affineStorageAudit =
                new GeneratedNativeAffineStorageAudit();
        int affineCipherCount = affineStorageAudit.cipherCount(structural);
        long totalAuditUnits = (long) affineCipherCount + 2L;
        progressListener.progress(0L, totalAuditUnits, "source structure");
        LinkedHashMap<String, GeneratedNativeHardeningFinding> findings =
                new LinkedHashMap<>();
        LinkedHashSet<String> evidence = new LinkedHashSet<>();
        for (GeneratedNativeHardeningFinding finding
                : new GeneratedNativeMetadataStructureAudit()
                        .inspect(structural)) {
            findings.putIfAbsent(finding.code(), finding);
        }
        new GeneratedNativeRegistrationAnchorAudit()
                .inspect(source)
                .ifPresent(finding ->
                        findings.putIfAbsent(finding.code(), finding));
        progressListener.progress(1L, totalAuditUnits, "source structure");
        GeneratedNativeAffineStorageAudit.Inspection affineStorage =
                affineStorageAudit.inspect(
                        structural,
                        completed -> progressListener.progress(
                                1L + completed,
                                totalAuditUnits,
                                "ciphertext array"));
        if (affineStorage.finding() != null) {
            findings.putIfAbsent(
                    affineStorage.finding().code(),
                    affineStorage.finding());
        }
        if (affineStorage.evidence()) {
            evidence.add(EVIDENCE_AFFINE_CIPHERTEXT_STORAGE);
        }

        findIdentifier(
                structural,
                "j2ll_encoded_metadata_strings",
                LEGACY_GLOBAL_METADATA_DIRECTORY,
                "legacy process-wide metadata directory is present",
                findings);
        findIdentifier(
                structural,
                "j2ll_decode_metadata_strings",
                LEGACY_DECODE_ALL_ROUTINE,
                "legacy decode-all metadata routine is present",
                findings);

        Set<String> exports = exportedFunctions(structural);
        boolean aggregateExported = exports.stream()
                .anyMatch(symbol -> symbol.equals("j2ll_register")
                        || symbol.startsWith("j2ll_register_"));
        if (aggregateExported) {
            add(
                    findings,
                    EXPORTED_AGGREGATE_REGISTRATION,
                    lineOf(structural, structural.indexOf("j2ll_register")),
                    "aggregate registration root is dynamically exported");
        }
        if (exports.equals(Set.of("JNI_OnLoad"))) {
            evidence.add(EVIDENCE_ONLY_JNI_ONLOAD_EXPORTED);
        }
        Matcher internalAggregate = INTERNAL_AGGREGATE.matcher(structural);
        if (internalAggregate.find() && !aggregateExported) {
            evidence.add(EVIDENCE_INTERNAL_AGGREGATE_REGISTRATION);
        }

        findAnyIdentifier(
                structural,
                List.of(
                        "nativeEmbeddedClassBlob",
                        "defineHiddenFallback",
                        "j2ll_fallback_blob",
                        "j2ll_fallback_class",
                        "fallback_blob"),
                FALLBACK_BYTECODE_CARRIER,
                "fallback or embedded-class bytecode carrier is present",
                findings);
        Matcher classMagicToken = CLASS_MAGIC_TOKEN.matcher(structural);
        Matcher namedClassfileBytes =
                NAMED_CLASSFILE_BYTE_ARRAY.matcher(structural);
        int classMagicOffset = classMagicToken.find()
                ? classMagicToken.start()
                : namedClassfileBytes.find()
                        ? namedClassfileBytes.start()
                        : -1;
        if (classMagicOffset >= 0) {
            add(
                    findings,
                    CLASSFILE_MAGIC_CARRIER,
                    lineOf(structural, classMagicOffset),
                    "classfile magic is embedded in generated native C");
        }

        findPlaintextTable(
                commentFree,
                structural,
                REGISTRATION_TABLE,
                PLAINTEXT_REGISTRATION_TABLE,
                "registration table contains plaintext name or descriptor literals",
                findings);
        findPlaintextTable(
                commentFree,
                structural,
                PLAINTEXT_BUSINESS_TABLE,
                PLAINTEXT_BUSINESS_STRING_TABLE,
                "business string table contains plaintext literals",
                findings);
        findAnyIdentifier(
                structural,
                List.of(
                        "j2ll_encrypted_string_constant_table",
                        "j2ll_encrypted_string_constant_entry"),
                CENTRALIZED_BUSINESS_STRING_TABLE,
                "centralized business-string metadata table is present",
                findings);
        Matcher centralizedDispatcher =
                CENTRAL_BUSINESS_DISPATCHER.matcher(structural);
        if (centralizedDispatcher.find()) {
            add(
                    findings,
                    CENTRALIZED_BUSINESS_STRING_DISPATCHER,
                    lineOf(structural, centralizedDispatcher.start()),
                    "centralized business-string token dispatcher is present");
        }
        boolean keyArrays = containsIdentifierPrefix(structural, "j2ll_str_key_");
        boolean cipherArrays =
                containsIdentifierPrefix(structural, "j2ll_str_cipher_");
        if (keyArrays && cipherArrays) {
            int offset = Math.min(
                    structural.indexOf("j2ll_str_key_"),
                    structural.indexOf("j2ll_str_cipher_"));
            add(
                    findings,
                    COLOCATED_KEY_CIPHER_TABLE,
                    lineOf(structural, offset),
                    "business string key and ciphertext arrays are colocated");
        }

        boolean lowSensitivityDecoder =
                containsIdentifierPrefix(structural, "j2ll_gcf_low_decode_");
        if (lowSensitivityDecoder
                && structural.contains("j2ll_gcf_low_once_")
                && structural.contains("atomic_compare_exchange_strong_explicit")
                && structural.contains("atomic_store_explicit")) {
            evidence.add(EVIDENCE_LOW_SENSITIVITY_LAZY_ONCE);
        }
        NativeTextSourceMetrics nativeTextMetrics =
                new NativeTextSourceScanner().scan(structural);
        if (structural.contains(
                        "__attribute__((cleanup("
                                + NativeScratchZeroizerSource
                                        .CLEANUP_FUNCTION_NAME
                                + ")")
                && (containsIdentifierPrefix(structural, "j2ll_nt_scratch_")
                        || containsIdentifierPrefix(
                                structural,
                                "j2ll_nt_local_"))
                && nativeTextMetrics.siteBoundCodecCount() > 0) {
            evidence.add(EVIDENCE_CALL_LOCAL_TEXT_SCRATCH);
            evidence.add(EVIDENCE_CALL_LOCAL_TEXT_CLEANUP);
        }
        if (nativeTextMetrics.siteBoundCodecCount() > 0
                && nativeTextMetrics.largestDecoderCipherFanout() <= 1) {
            evidence.add(EVIDENCE_SITE_BOUND_TEXT_CODEC);
        }
        if (nativeTextMetrics.cipherArrayCount() > 0
                && nativeTextMetrics.runtimeBoundCipherReadCount()
                        == nativeTextMetrics.cipherArrayCount()) {
            evidence.add(EVIDENCE_RUNTIME_BOUND_CIPHER_READ);
        }
        if (nativeTextMetrics.codecFamilyCount() >= 2) {
            evidence.add(EVIDENCE_NATIVE_TEXT_CODEC_DIVERSITY);
        }
        if (containsIdentifier(structural, "j2ll_native_text_zero")) {
            evidence.add(EVIDENCE_SCRATCH_ZEROIZER);
        }

        progressListener.progress(totalAuditUnits, totalAuditUnits, "done");

        return new GeneratedNativeHardeningAuditResult(
                new ArrayList<>(findings.values()),
                new ArrayList<>(evidence));
    }

    private void findPlaintextTable(
            String commentFree,
            String structural,
            Pattern declaration,
            String code,
            String detail,
            Map<String, GeneratedNativeHardeningFinding> findings) {
        Matcher matcher = declaration.matcher(structural);
        while (matcher.find()) {
            int openingBrace = structural.indexOf('{', matcher.start());
            int closingBrace = matchingBrace(commentFree, openingBrace);
            if (closingBrace > openingBrace
                    && hasStringLiteral(
                            commentFree.substring(openingBrace, closingBrace + 1))) {
                add(
                        findings,
                        code,
                        lineOf(structural, matcher.start()),
                        detail);
                return;
            }
        }
    }

    private int matchingBrace(String source, int openingBrace) {
        if (openingBrace < 0) {
            return -1;
        }
        int depth = 0;
        boolean string = false;
        boolean character = false;
        for (int index = openingBrace; index < source.length(); index++) {
            char ch = source.charAt(index);
            if ((string || character) && ch == '\\') {
                index++;
                continue;
            }
            if (!character && ch == '"') {
                string = !string;
                continue;
            }
            if (!string && ch == '\'') {
                character = !character;
                continue;
            }
            if (string || character) {
                continue;
            }
            if (ch == '{') {
                depth++;
            } else if (ch == '}' && --depth == 0) {
                return index;
            }
        }
        return -1;
    }

    private boolean hasStringLiteral(String source) {
        boolean character = false;
        for (int index = 0; index < source.length(); index++) {
            char ch = source.charAt(index);
            if (character && ch == '\\') {
                index++;
                continue;
            }
            if (ch == '\'') {
                character = !character;
            } else if (!character && ch == '"') {
                return true;
            }
        }
        return false;
    }

    private Set<String> exportedFunctions(String structural) {
        java.util.TreeSet<String> exports = new java.util.TreeSet<>();
        Matcher matcher = EXPORTED_FUNCTION.matcher(structural);
        while (matcher.find()) {
            exports.add(matcher.group(1));
        }
        return Set.copyOf(exports);
    }

    private void findIdentifier(
            String structural,
            String identifier,
            String code,
            String detail,
            Map<String, GeneratedNativeHardeningFinding> findings) {
        Matcher matcher = Pattern.compile(
                        "\\b" + Pattern.quote(identifier) + "\\b")
                .matcher(structural);
        if (matcher.find()) {
            add(findings, code, lineOf(structural, matcher.start()), detail);
        }
    }

    private void findAnyIdentifier(
            String structural,
            List<String> identifiers,
            String code,
            String detail,
            Map<String, GeneratedNativeHardeningFinding> findings) {
        int first = Integer.MAX_VALUE;
        for (String identifier : identifiers) {
            Matcher matcher = Pattern.compile(
                            "\\b" + Pattern.quote(identifier) + "\\b")
                    .matcher(structural);
            if (matcher.find()) {
                first = Math.min(first, matcher.start());
            }
        }
        if (first != Integer.MAX_VALUE) {
            add(findings, code, lineOf(structural, first), detail);
        }
    }

    private boolean containsIdentifier(String structural, String identifier) {
        return Pattern.compile("\\b" + Pattern.quote(identifier) + "\\b")
                .matcher(structural)
                .find();
    }

    private boolean containsIdentifierPrefix(
            String structural,
            String prefix) {
        return Pattern.compile("\\b" + Pattern.quote(prefix) + "[A-Za-z0-9_]*\\b")
                .matcher(structural)
                .find();
    }

    private void add(
            Map<String, GeneratedNativeHardeningFinding> findings,
            String code,
            int line,
            String detail) {
        findings.putIfAbsent(
                code,
                new GeneratedNativeHardeningFinding(code, line, detail));
    }

    private int lineOf(String source, int offset) {
        if (offset < 0) {
            return 1;
        }
        int line = 1;
        for (int index = 0; index < offset; index++) {
            if (source.charAt(index) == '\n') {
                line++;
            }
        }
        return line;
    }

    private String maskComments(String source, boolean maskLiterals) {
        StringBuilder masked = new StringBuilder(source);
        Mode mode = Mode.CODE;
        for (int index = 0; index < source.length(); index++) {
            char ch = source.charAt(index);
            char next = index + 1 < source.length()
                    ? source.charAt(index + 1)
                    : '\0';
            switch (mode) {
                case CODE -> {
                    if (ch == '/' && next == '/') {
                        blank(masked, index);
                        blank(masked, ++index);
                        mode = Mode.LINE_COMMENT;
                    } else if (ch == '/' && next == '*') {
                        blank(masked, index);
                        blank(masked, ++index);
                        mode = Mode.BLOCK_COMMENT;
                    } else if (ch == '"') {
                        if (maskLiterals) {
                            blank(masked, index);
                        }
                        mode = Mode.STRING;
                    } else if (ch == '\'') {
                        if (maskLiterals) {
                            blank(masked, index);
                        }
                        mode = Mode.CHARACTER;
                    }
                }
                case LINE_COMMENT -> {
                    if (ch == '\n') {
                        mode = Mode.CODE;
                    } else {
                        blank(masked, index);
                    }
                }
                case BLOCK_COMMENT -> {
                    if (ch == '*' && next == '/') {
                        blank(masked, index);
                        blank(masked, ++index);
                        mode = Mode.CODE;
                    } else if (ch != '\n' && ch != '\r') {
                        blank(masked, index);
                    }
                }
                case STRING, CHARACTER -> {
                    boolean string = mode == Mode.STRING;
                    if (maskLiterals && ch != '\n' && ch != '\r') {
                        blank(masked, index);
                    }
                    if (ch == '\\' && index + 1 < source.length()) {
                        if (maskLiterals) {
                            blank(masked, ++index);
                        } else {
                            index++;
                        }
                    } else if ((string && ch == '"')
                            || (!string && ch == '\'')) {
                        mode = Mode.CODE;
                    }
                }
            }
        }
        return masked.toString();
    }

    private void blank(StringBuilder source, int index) {
        if (source.charAt(index) != '\n' && source.charAt(index) != '\r') {
            source.setCharAt(index, ' ');
        }
    }

    private enum Mode {
        CODE,
        LINE_COMMENT,
        BLOCK_COMMENT,
        STRING,
        CHARACTER
    }
}
