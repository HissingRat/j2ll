package xyz.melodysky.toolchain.nativetext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

final class GeneratedNativeHardeningAuditTest {
    private final GeneratedNativeHardeningAudit audit =
            new GeneratedNativeHardeningAudit();

    @Test
    void reportsStableCodesForLegacyBulkRecoverySurfaces() {
        String vulnerable = """
                // j2ll_gcf_decode_comment_must_not_count
                typedef struct {
                    unsigned char* data;
                    size_t length;
                    uint64_t key;
                } j2ll_encoded_metadata_string;
                static j2ll_encoded_metadata_string j2ll_encoded_metadata_strings[] = {
                    { metadata, sizeof(metadata), 1u }
                };
                static void j2ll_decode_metadata_strings(void) {
                    for (size_t i = 0; i < sizeof(j2ll_encoded_metadata_strings)
                            / sizeof(j2ll_encoded_metadata_strings[0]); i++) {}
                }
                static const unsigned char nativeEmbeddedClassBlob[] = {
                    0xCA, 0xFE, 0xBA, 0xBE
                };
                static JNINativeMethod natives[] = {
                    { "secretMethod", "()V", (void*)native_method }
                };
                static const j2ll_string_constant_entry j2ll_string_constant_table[] = {
                    { 1, "business-secret" }
                };
                static const unsigned char j2ll_str_key_0[] = { 1 };
                static const unsigned char j2ll_str_cipher_0[] = { 2 };
                static const j2ll_encrypted_string_constant_entry
                        j2ll_encrypted_string_constant_table[] = {
                    { 1, j2ll_str_key_0, 1, j2ll_str_cipher_0, 1 }
                };
                jobject j2ll_rt_string_constant(JNIEnv* env, int64_t token) {
                    return token == 1 ? first : second;
                }
                JNIEXPORT jint JNICALL j2ll_register(JavaVM* vm) {
                    return JNI_VERSION_1_8;
                }
                JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
                    return j2ll_register(vm);
                }
                """;

        GeneratedNativeHardeningAuditResult first = audit.audit(vulnerable);
        GeneratedNativeHardeningAuditResult repeated = audit.audit(vulnerable);

        assertFalse(first.passed());
        assertEquals(first, repeated);
        assertEquals(
                List.of(
                        GeneratedNativeHardeningAudit
                                .CENTRALIZED_BUSINESS_STRING_DISPATCHER,
                        GeneratedNativeHardeningAudit.CENTRALIZED_BUSINESS_STRING_TABLE,
                        GeneratedNativeHardeningAudit.CLASSFILE_MAGIC_CARRIER,
                        GeneratedNativeHardeningAudit.COLOCATED_KEY_CIPHER_TABLE,
                        GeneratedNativeHardeningAudit.EXPORTED_AGGREGATE_REGISTRATION,
                        GeneratedNativeHardeningAudit.FALLBACK_BYTECODE_CARRIER,
                        GeneratedNativeHardeningAudit.LEGACY_DECODE_ALL_ROUTINE,
                        GeneratedNativeHardeningAudit.LEGACY_GLOBAL_METADATA_DIRECTORY,
                        GeneratedNativeHardeningAudit.PLAINTEXT_BUSINESS_STRING_TABLE,
                        GeneratedNativeHardeningAudit.PLAINTEXT_REGISTRATION_TABLE),
                first.findings().stream()
                        .map(GeneratedNativeHardeningFinding::code)
                        .toList());
        first.findings().forEach(finding -> {
            assertFalse(finding.detail().contains("secretMethod"));
            assertFalse(finding.detail().contains("business-secret"));
        });
    }

    @Test
    void rejectsStablePlaintextRegistrationFailureAnchors() {
        String vulnerable = """
                static void rollback_failure(JNIEnv* env) {
                    (*env)->FatalError(
                            env,
                            "native owner registration rollback failed");
                }
                """;

        GeneratedNativeHardeningAuditResult result = audit.audit(vulnerable);

        assertFalse(result.passed());
        assertEquals(
                List.of(GeneratedNativeHardeningAudit
                        .STABLE_REGISTRATION_DIAGNOSTIC),
                result.findings().stream()
                        .map(GeneratedNativeHardeningFinding::code)
                        .toList());
        assertFalse(result.findings().get(0).detail().contains(
                "native owner registration rollback failed"));
    }

    @Test
    void rejectsArbitraryAdjacentFatalErrorStringLiterals() {
        String vulnerable = """
                static void future_failure(JNIEnv* env) {
                    (*env)->FatalError(
                            env,
                            "a newly worded "
                            "registration failure");
                }
                """;

        GeneratedNativeHardeningAuditResult result = audit.audit(vulnerable);

        assertFalse(result.passed());
        assertEquals(
                List.of(GeneratedNativeHardeningAudit
                        .STABLE_REGISTRATION_DIAGNOSTIC),
                result.findings().stream()
                        .map(GeneratedNativeHardeningFinding::code)
                        .toList());
    }

    @Test
    void commentsDoNotBecomeCallsAndDecodedFatalErrorVariablesRemainAllowed() {
        String source = """
                // FatalError(env, "comment-only anchor")
                /*
                 * (*env)->FatalError(env, "another comment-only anchor");
                 */
                static void decoded_failure(
                        JNIEnv* env,
                        char* decoded_message) {
                    (*env)->FatalError(env, decoded_message);
                }
                """;

        GeneratedNativeHardeningAuditResult result = audit.audit(source);

        assertTrue(result.passed(), result.findings().toString());
    }

    @Test
    void acceptsScopedFragmentAndRecordsPositiveEvidence() {
        String fragment = """
                static void native_method(JNIEnv* env, jobject self) {
                    jclass owner = (*env)->FindClass(env, "secret/Owner");
                    (void)owner;
                    (void)self;
                }
                static jint j2ll_register(JavaVM* vm) {
                    return vm == NULL ? JNI_ERR : JNI_VERSION_1_8;
                }
                JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
                    (void)reserved;
                    return j2ll_register(vm);
                }
                """;
        String hardened = new GeneratedCFragmentTextObfuscator().obfuscate(
                NativeTextBuildKey.fromUtf8("fixed-build"),
                "registration:owner",
                fragment)
                + new NativeTextCEmitter().runtimeSource();

        GeneratedNativeHardeningAuditResult result = audit.audit(hardened);

        assertTrue(result.passed(), result.findings().toString());
        assertEquals(
                List.of(
                        GeneratedNativeHardeningAudit
                                .EVIDENCE_AFFINE_CIPHERTEXT_STORAGE,
                        GeneratedNativeHardeningAudit
                                .EVIDENCE_INTERNAL_AGGREGATE_REGISTRATION,
                        GeneratedNativeHardeningAudit
                                .EVIDENCE_CALL_LOCAL_TEXT_CLEANUP,
                        GeneratedNativeHardeningAudit
                                .EVIDENCE_CALL_LOCAL_TEXT_SCRATCH,
                        GeneratedNativeHardeningAudit.EVIDENCE_SCRATCH_ZEROIZER,
                        GeneratedNativeHardeningAudit
                                .EVIDENCE_ONLY_JNI_ONLOAD_EXPORTED,
                        GeneratedNativeHardeningAudit
                                .EVIDENCE_RUNTIME_BOUND_CIPHER_READ,
                        GeneratedNativeHardeningAudit
                                .EVIDENCE_SITE_BOUND_TEXT_CODEC),
                result.evidence());
    }

    @Test
    void rejectsDirectAndIdentityCiphertextStorageWhenAffineContractIsActive() {
        String direct = """
                #define J2LL_NATIVE_TEXT_AFFINE_STORAGE 1
                static const unsigned char j2ll_nt_aaaaaaaaaaaaaaaaaaaaaaaa_cipher[] = {
                    0x01, 0x02,
                };
                static void decode_direct(unsigned char* output) {
                    for (size_t j2ll_nt_p_aaaaaaaaaaaaaaaaaaaaaaaa = 0u;
                            j2ll_nt_p_aaaaaaaaaaaaaaaaaaaaaaaa < 2u;
                            j2ll_nt_p_aaaaaaaaaaaaaaaaaaaaaaaa++) {
                        output[j2ll_nt_p_aaaaaaaaaaaaaaaaaaaaaaaa] =
                                ((const volatile unsigned char*)(
                                        j2ll_nt_aaaaaaaaaaaaaaaaaaaaaaaa_cipher))
                                [j2ll_nt_p_aaaaaaaaaaaaaaaaaaaaaaaa];
                    }
                }
                """;
        String identity = """
                #define J2LL_NATIVE_TEXT_AFFINE_STORAGE 1
                static const unsigned char j2ll_nt_bbbbbbbbbbbbbbbbbbbbbbbb_cipher[] = {
                    0x01, 0x02,
                };
                static void decode_identity(unsigned char* output) {
                    size_t j2ll_nt_s_bbbbbbbbbbbbbbbbbbbbbbbb =
                            (size_t)UINT64_C(0);
                    for (size_t j2ll_nt_p_bbbbbbbbbbbbbbbbbbbbbbbb = 0u;
                            j2ll_nt_p_bbbbbbbbbbbbbbbbbbbbbbbb < 2u;
                            j2ll_nt_p_bbbbbbbbbbbbbbbbbbbbbbbb++) {
                        output[j2ll_nt_p_bbbbbbbbbbbbbbbbbbbbbbbb] =
                                ((const volatile unsigned char*)(
                                        j2ll_nt_bbbbbbbbbbbbbbbbbbbbbbbb_cipher))
                                [j2ll_nt_s_bbbbbbbbbbbbbbbbbbbbbbbb];
                        j2ll_nt_s_bbbbbbbbbbbbbbbbbbbbbbbb += UINT64_C(1);
                        j2ll_nt_s_bbbbbbbbbbbbbbbbbbbbbbbb -=
                                j2ll_nt_s_bbbbbbbbbbbbbbbbbbbbbbbb
                                        >= sizeof(j2ll_nt_bbbbbbbbbbbbbbbbbbbbbbbb_cipher)
                                ? sizeof(j2ll_nt_bbbbbbbbbbbbbbbbbbbbbbbb_cipher)
                                : 0u;
                    }
                }
                """;
        String missingContract = direct.replace(
                        "#define J2LL_NATIVE_TEXT_AFFINE_STORAGE 1\n",
                        "")
                + """
                  JNIEXPORT jint JNICALL JNI_OnLoad(
                          JavaVM* vm, void* reserved) {
                      return JNI_VERSION_1_8;
                  }
                  """;

        for (String source : List.of(
                direct,
                identity,
                missingContract)) {
            GeneratedNativeHardeningAuditResult result =
                    audit.audit(source);
            assertEquals(
                    List.of(GeneratedNativeHardeningAudit
                            .INVALID_AFFINE_CIPHERTEXT_STORAGE),
                    result.findings().stream()
                            .map(GeneratedNativeHardeningFinding::code)
                            .toList());
            assertFalse(result.evidence().contains(
                    GeneratedNativeHardeningAudit
                            .EVIDENCE_AFFINE_CIPHERTEXT_STORAGE));
        }
    }

    @Test
    void rejectsLegacyNamesAndRenamedTokenTextDirectories() {
        String vulnerable = """
                typedef struct {
                    uint64_t opaque_token;
                    const char* first_text;
                    const char* second_text;
                } opaque_entry;
                static const opaque_entry opaque_values[] = {
                    { UINT64_C(1), (char*)j2ll_nt_aaaaaaaaaaaaaaaaaaaaaaaa,
                      (char*)j2ll_nt_bbbbbbbbbbbbbbbbbbbbbbbb }
                };
                static const int j2ll_method_table[] = { 1 };
                static const int j2ll_field_table[] = { 1 };
                static const int j2ll_reflection_owner_table[] = { 1 };
                static const int j2ll_lambda_table[] = { 1 };
                """;

        GeneratedNativeHardeningAuditResult result = audit.audit(vulnerable);

        assertFalse(result.passed());
        assertEquals(
                List.of(
                        GeneratedNativeHardeningAudit
                                .LEGACY_CENTRALIZED_RUNTIME_METADATA_TABLE,
                        GeneratedNativeHardeningAudit
                                .STRUCTURAL_SENSITIVE_TEXT_DIRECTORY),
                result.findings().stream()
                        .map(GeneratedNativeHardeningFinding::code)
                        .toList());
    }

    @Test
    void rejectsCentralizedLambdaMetadataTableByItself() {
        GeneratedNativeHardeningAuditResult result = audit.audit("""
                typedef struct {
                    uint64_t token;
                    const char* owner;
                    const char* descriptor;
                } lambda_entry;
                static const lambda_entry j2ll_lambda_table[] = {
                    { UINT64_C(1), "pkg/Owner", "()Ljava/lang/Runnable;" }
                };
                """);

        assertEquals(
                List.of(GeneratedNativeHardeningAudit
                        .LEGACY_CENTRALIZED_RUNTIME_METADATA_TABLE),
                result.findings().stream()
                        .map(GeneratedNativeHardeningFinding::code)
                        .toList());
    }

    @Test
    void genericPersistentDecoderIsFindingAndLazyRuntimeTextIsExplicit() {
        String generic = """
                static void j2ll_gcf_decode_aaaaaaaaaaaaaaaaaaaaaaaa(void) {
                    for (size_t index = 0;
                         index < sizeof(j2ll_nt_aaaaaaaaaaaaaaaaaaaaaaaa);
                         index++) {}
                }
                """;
        GeneratedNativeHardeningAuditResult genericResult =
                audit.audit(generic);
        assertEquals(
                List.of(GeneratedNativeHardeningAudit
                        .PERSISTENT_DECODED_SENSITIVE_TEXT),
                genericResult.findings().stream()
                        .map(GeneratedNativeHardeningFinding::code)
                        .toList());
        assertFalse(genericResult.evidence().contains(
                GeneratedNativeHardeningAudit.EVIDENCE_SCOPE_LOCAL_DECODER));
        assertFalse(genericResult.evidence().contains(
                GeneratedNativeHardeningAudit.EVIDENCE_THREAD_SAFE_ONCE));

        String low = new GeneratedCFragmentTextObfuscator().obfuscate(
                NativeTextBuildKey.fromUtf8("fixed-build"),
                "runtime:error",
                "const char* message(void) { return \"ordinary warning\"; }\n",
                GeneratedCTextPolicy.lowSensitivityRuntimeError());
        GeneratedNativeHardeningAuditResult lowResult = audit.audit(low);
        assertTrue(lowResult.passed(), lowResult.findings().toString());
        assertEquals(
                List.of(
                        GeneratedNativeHardeningAudit
                                .EVIDENCE_LOW_SENSITIVITY_LAZY_ONCE,
                        GeneratedNativeHardeningAudit
                                .EVIDENCE_SCRATCH_ZEROIZER,
                        GeneratedNativeHardeningAudit
                                .EVIDENCE_RUNTIME_BOUND_CIPHER_READ,
                        GeneratedNativeHardeningAudit
                                .EVIDENCE_SITE_BOUND_TEXT_CODEC),
                lowResult.evidence());
    }

    @Test
    void rejectsSiteCodecWithoutRuntimeCipherReadBoundary() {
        NativeTextEncoding encoding = new NativeTextEncoder().encode(
                NativeTextBuildKey.fromUtf8("foldable-native-text"),
                NativeTextPurpose.RUNTIME_ERROR,
                "foldable:error",
                "sensitive runtime text");
        String source = new NativeTextCEmitter()
                .ciphertextDeclaration(encoding)
                + "static void use_text(unsigned char* output) {\n"
                + new NativeTextCEmitter()
                        .decodeInto(encoding, "output", "    ")
                        .replace(
                                "((const volatile unsigned char*)("
                                        + encoding.symbol()
                                        + "_cipher))",
                                encoding.symbol() + "_cipher")
                + "}\n";

        GeneratedNativeHardeningAuditResult result = audit.audit(source);

        assertEquals(
                List.of(GeneratedNativeHardeningAudit
                        .OPTIMIZER_FOLDABLE_NATIVE_TEXT),
                result.findings().stream()
                        .map(GeneratedNativeHardeningFinding::code)
                        .toList());
    }

    @Test
    void rejectsMixedVolatileAndDirectCipherReads() {
        NativeTextEncoding encoding = new NativeTextEncoder().encode(
                NativeTextBuildKey.fromUtf8("mixed-native-text-read"),
                NativeTextPurpose.RUNTIME_ERROR,
                "mixed:error",
                "sensitive runtime text");
        NativeTextCEmitter emitter = new NativeTextCEmitter();
        String source = emitter.ciphertextDeclaration(encoding)
                + "static void use_text(unsigned char* output, size_t index) {\n"
                + emitter.decodeInto(encoding, "output", "    ")
                + "    output[0] ^= "
                + encoding.symbol()
                + "_cipher[index];\n"
                + "}\n";

        GeneratedNativeHardeningAuditResult result = audit.audit(source);

        assertEquals(
                List.of(GeneratedNativeHardeningAudit
                        .OPTIMIZER_FOLDABLE_NATIVE_TEXT),
                result.findings().stream()
                        .map(GeneratedNativeHardeningFinding::code)
                        .toList());
    }

    @Test
    void rejectsPointerArithmeticAndAliasCipherReads() {
        NativeTextEncoding encoding = new NativeTextEncoder().encode(
                NativeTextBuildKey.fromUtf8("aliased-native-text-read"),
                NativeTextPurpose.RUNTIME_ERROR,
                "aliased:error",
                "sensitive runtime text");
        NativeTextCEmitter emitter = new NativeTextCEmitter();
        String cipher = encoding.symbol() + "_cipher";
        String valid = emitter.runtimeSource()
                + emitter.ciphertextDeclaration(encoding)
                + "static void use_text(unsigned char* output, size_t index) {\n"
                + emitter.decodeInto(encoding, "output", "    ");
        for (String extra : List.of(
                "    output[0] ^= *((const unsigned char*)"
                        + cipher
                        + " + index);\n",
                "    const unsigned char* alias = "
                        + cipher
                        + ";\n    output[0] ^= alias[index];\n")) {
            GeneratedNativeHardeningAuditResult result =
                    audit.audit(valid + extra + "}\n");

            assertEquals(
                    List.of(GeneratedNativeHardeningAudit
                            .INVALID_AFFINE_CIPHERTEXT_STORAGE),
                    result.findings().stream()
                            .map(GeneratedNativeHardeningFinding::code)
                            .toList());
        }
    }

    @Test
    void rejectsRenamedFixedDecoderAndAdjacentSeedShares() {
        String vulnerable = """
                static const unsigned char first_cipher[] = { 1u, 2u };
                static const unsigned char second_cipher[] = { 3u, 4u };
                static void opaque_transform(
                        const unsigned char* source,
                        unsigned char* target,
                        size_t length,
                        uint64_t seed) {
                    for (size_t i = 0u; i < length; i++) {
                        uint64_t value = seed
                                + UINT64_C(0x9e3779b97f4a7c15) * (i + 1u);
                        value = (value ^ (value >> 30u))
                                * UINT64_C(0xbf58476d1ce4e5b9);
                        value = (value ^ (value >> 27u))
                                * UINT64_C(0x94d049bb133111eb);
                        target[i] = (unsigned char)(source[i] ^ value);
                    }
                }
                static void recover(void) {
                    unsigned char first[sizeof(first_cipher)];
                    unsigned char second[sizeof(second_cipher)];
                    uint64_t seed = UINT64_C(0x1111111111111111)
                            ^ UINT64_C(0x2222222222222222);
                    opaque_transform(
                            first_cipher,
                            first,
                            sizeof(first_cipher),
                            seed);
                    opaque_transform(
                            second_cipher,
                            second,
                            sizeof(second_cipher),
                            seed);
                }
                """;

        GeneratedNativeHardeningAuditResult result = audit.audit(vulnerable);

        assertEquals(
                List.of(
                        GeneratedNativeHardeningAudit
                                .ADJACENT_NATIVE_TEXT_SEED_CIPHER,
                        GeneratedNativeHardeningAudit
                                .FIXED_NATIVE_TEXT_DECODER_SHAPE,
                        GeneratedNativeHardeningAudit
                                .REUSABLE_NATIVE_TEXT_DECODER_FANOUT),
                result.findings().stream()
                        .map(GeneratedNativeHardeningFinding::code)
                        .toList());
    }

    @Test
    void rejectsOneDecoderCoveringBulkTextArrays() {
        StringBuilder source = new StringBuilder(
                "static void j2ll_gcf_low_decode_aaaaaaaaaaaaaaaaaaaaaaaa(void) {\n");
        for (int index = 0; index < 8; index++) {
            source.append("  for (size_t i = 0; i < sizeof(j2ll_nt_")
                    .append(String.format("%024x", index))
                    .append("); i++) {}\n");
        }
        source.append("}\n");

        GeneratedNativeHardeningAuditResult result =
                audit.audit(source.toString());

        assertEquals(
                List.of(GeneratedNativeHardeningAudit
                        .SINGLE_DECODER_BULK_TEXT_LIFETIME),
                result.findings().stream()
                        .map(GeneratedNativeHardeningFinding::code)
                        .toList());
    }

    @Test
    void ordinaryJniConstructionAndNecessaryLiteralsAreNotBulkTableFindings() {
        String ordinary = """
                static const unsigned char j2ll_nt_random_cipher[] = {
                    0xCA, 0xFE, 0xBA, 0xBE
                };
                static jint j2ll_register(JavaVM* vm) {
                    JNIEnv* env = NULL;
                    JNINativeMethod methods[1];
                    methods[0].name = decoded_name;
                    methods[0].signature = decoded_descriptor;
                    methods[0].fnPtr = (void*)native_method;
                    jclass owner = (*env)->FindClass(env, "required/Owner");
                    return (*env)->RegisterNatives(env, owner, methods, 1);
                }
                JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
                    (void)reserved;
                    return j2ll_register(vm);
                }
                """;

        GeneratedNativeHardeningAuditResult result = audit.audit(ordinary);

        assertTrue(result.passed(), result.findings().toString());
        assertEquals(
                List.of(
                        GeneratedNativeHardeningAudit
                                .EVIDENCE_INTERNAL_AGGREGATE_REGISTRATION,
                        GeneratedNativeHardeningAudit
                                .EVIDENCE_ONLY_JNI_ONLOAD_EXPORTED),
                result.evidence());
    }
}
