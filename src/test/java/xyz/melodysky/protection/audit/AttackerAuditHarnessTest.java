package xyz.melodysky.protection.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import xyz.melodysky.toolchain.TargetTriple;
import xyz.melodysky.toolchain.nativetext.GeneratedNativeHardeningAudit;
import xyz.melodysky.toolchain.nativetext.NativeTextBuildKey;
import xyz.melodysky.toolchain.nativetext.NativeTextCEmitter;
import xyz.melodysky.toolchain.nativetext.NativeTextEncoder;
import xyz.melodysky.toolchain.nativetext.NativeTextEncoding;
import xyz.melodysky.toolchain.nativetext.NativeTextPurpose;

class AttackerAuditHarnessTest {
    @TempDir
    Path temp;

    @Test
    void reportsStableMachineReadableRecoverySurfaceMetricsWithoutPlaintext()
            throws Exception {
        String secret = "business-secret";
        Path nativeLibrary = temp.resolve("vulnerable.dll");
        Files.write(
                nativeLibrary,
                concat(
                        "visible-native-string\0"
                                .getBytes(StandardCharsets.US_ASCII),
                        new byte[] {
                            (byte) 0xca,
                            (byte) 0xfe,
                            (byte) 0xba,
                            (byte) 0xbe,
                            0,
                            0,
                            0,
                            61,
                            0,
                            2
                        },
                        ("\0nativeEmbeddedClassBlob\0"
                                        + "j2ll_encoded_metadata_strings\0"
                                        + "j2ll_decode_metadata_strings\0"
                                        + secret
                                        + "\0")
                                .getBytes(StandardCharsets.UTF_8)));
        Path generatedC = temp.resolve("vulnerable.c");
        Files.writeString(generatedC, """
                static unsigned char nativeEmbeddedClassBlob[] = {
                    0xCA, 0xFE, 0xBA, 0xBE
                };
                static void* j2ll_encoded_metadata_strings;
                static void j2ll_decode_metadata_strings(void) {}
                static JNINativeMethod methods[] = {
                    { "business-secret", "()V", (void*)native_method }
                };
                JNIEXPORT jint JNICALL j2ll_register(JavaVM* vm) {
                    return JNI_VERSION_1_8;
                }
                JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
                    return j2ll_register(vm);
                }
                """);
        AttackerAuditHarness harness = new AttackerAuditHarness(
                (target, library) -> List.of(
                        "j2ll_register",
                        "JNI_OnLoad"));
        AttackerAuditRequest request = new AttackerAuditRequest(
                TargetTriple.WINDOWS_X64,
                nativeLibrary,
                generatedC,
                List.of(secret));

        AttackerAuditMetrics first = harness.audit(request);
        AttackerAuditMetrics repeated = harness.audit(request);
        String json = new AttackerAuditReportWriter().json(first);

        assertEquals(first, repeated);
        assertFalse(first.passed());
        assertEquals(Files.size(nativeLibrary), first.nativeSizeBytes());
        assertEquals(Files.size(generatedC), first.generatedCSizeBytes());
        assertTrue(first.fallbackCarrierOccurrences() >= 2);
        assertTrue(first.classMagicOccurrences() >= 2);
        assertTrue(first.legacyGlobalMetadataOccurrences() >= 2);
        assertTrue(first.legacyDecodeAllOccurrences() >= 2);
        assertTrue(first.nativePrintableStringCount() >= 4);
        assertTrue(first.generatedCStringLiteralCount() >= 2);
        assertEquals(2, first.sensitivePlaintextOccurrences());
        assertEquals(List.of("j2ll_register"), first.unexpectedExports());
        assertTrue(first.generatedCHardeningFindings().contains(
                GeneratedNativeHardeningAudit.FALLBACK_BYTECODE_CARRIER));
        assertEquals(json, new AttackerAuditReportWriter().json(repeated));
        assertTrue(json.contains("\"schemaVersion\": 1"));
        assertTrue(json.contains("\"nativeSizeBytes\""));
        assertTrue(json.contains("\"generatedCSizeBytes\""));
        assertTrue(json.contains("\"literalHash\""));
        assertFalse(json.contains(secret));
    }

    @Test
    void acceptsHardenedSourceAndExactDynamicExportAllowlist()
            throws Exception {
        Path nativeLibrary = temp.resolve("hardened.so");
        Files.write(
                nativeLibrary,
                "ordinary-printable\0binary-data\0"
                        .getBytes(StandardCharsets.US_ASCII));
        Path generatedC = temp.resolve("hardened.c");
        Files.writeString(generatedC, """
                // Historical names in comments must not count:
                // nativeEmbeddedClassBlob j2ll_decode_metadata_strings
                static jint j2ll_register_a1b2(JavaVM* vm) {
                    return vm == NULL ? JNI_ERR : JNI_VERSION_1_8;
                }
                JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
                    (void)reserved;
                    return j2ll_register_a1b2(vm);
                }
                """);
        AttackerAuditHarness harness = new AttackerAuditHarness(
                (target, library) -> List.of("JNI_OnLoad"));

        AttackerAuditMetrics metrics = harness.audit(new AttackerAuditRequest(
                TargetTriple.LINUX_X64,
                nativeLibrary,
                generatedC,
                List.of("absent-sensitive-value")));

        assertTrue(metrics.passed(), metrics.toString());
        assertEquals(0, metrics.fallbackCarrierOccurrences());
        assertEquals(0, metrics.classMagicOccurrences());
        assertEquals(0, metrics.legacyGlobalMetadataOccurrences());
        assertEquals(0, metrics.legacyDecodeAllOccurrences());
        assertEquals(0, metrics.sensitivePlaintextOccurrences());
        assertEquals(List.of("JNI_OnLoad"), metrics.dynamicExports());
        assertEquals(List.of(), metrics.unexpectedExports());
        assertEquals(List.of(), metrics.missingExports());
        assertTrue(metrics.generatedCHardeningEvidence().contains(
                GeneratedNativeHardeningAudit
                        .EVIDENCE_INTERNAL_AGGREGATE_REGISTRATION));
    }

    @Test
    void randomCipherMagicWithoutPlausibleClassHeaderIsNotAClassCarrier()
            throws Exception {
        Path nativeLibrary = temp.resolve("cipher.bin");
        Files.write(nativeLibrary, new byte[] {
            (byte) 0xca,
            (byte) 0xfe,
            (byte) 0xba,
            (byte) 0xbe,
            0x12,
            0x34,
            0x12,
            0x34,
            0,
            0
        });
        Path generatedC = temp.resolve("minimal.c");
        Files.writeString(generatedC, """
                JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
                    return JNI_VERSION_1_8;
                }
                """);
        AttackerAuditHarness harness = new AttackerAuditHarness(
                (target, library) -> List.of("JNI_OnLoad"));

        AttackerAuditMetrics metrics = harness.audit(new AttackerAuditRequest(
                TargetTriple.WINDOWS_X64,
                nativeLibrary,
                generatedC,
                List.of()));

        assertEquals(0, metrics.classMagicOccurrences());
        assertTrue(metrics.passed(), metrics.toString());
    }

    @Test
    void reportsSiteBoundCodecDiversityWithoutInventingDecoderFanout()
            throws Exception {
        Path nativeLibrary = temp.resolve("codec-surface.so");
        Files.write(nativeLibrary, new byte[] {1, 2, 3, 4});
        NativeTextBuildKey buildKey =
                NativeTextBuildKey.fromUtf8("audit-codec-build");
        NativeTextEncoder encoder = new NativeTextEncoder();
        NativeTextCEmitter emitter = new NativeTextCEmitter();
        StringBuilder source = new StringBuilder(emitter.runtimeSource());
        int siteCount = 24;
        for (int index = 0; index < siteCount; index++) {
            NativeTextEncoding encoding = encoder.encode(
                    buildKey,
                    NativeTextPurpose.RUNTIME_METHOD_NAME,
                    "audit-site-" + index,
                    "value-" + index);
            String scratch = "scratch_" + index;
            source.append(emitter.ciphertextDeclaration(encoding))
                    .append("static void use_")
                    .append(index)
                    .append("(void) {\n    ")
                    .append(emitter.scratchDeclarationAndDecode(
                                    encoding,
                                    scratch)
                            .replace("\n", "\n    "))
                    .append(emitter.scratchCleanup(encoding, scratch))
                    .append("}\n");
        }
        source.append("""
                JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
                    (void)vm;
                    (void)reserved;
                    return JNI_VERSION_1_8;
                }
                """);
        Path generatedC = temp.resolve("codec-surface.c");
        Files.writeString(generatedC, source);
        AttackerAuditHarness harness = new AttackerAuditHarness(
                (target, library) -> List.of("JNI_OnLoad"));

        AttackerAuditMetrics metrics = harness.audit(new AttackerAuditRequest(
                TargetTriple.LINUX_X64,
                nativeLibrary,
                generatedC,
                List.of()));
        String json = new AttackerAuditReportWriter().json(metrics);

        assertTrue(metrics.passed(), metrics.toString());
        assertEquals(siteCount, metrics.generatedNativeTextCipherArrayCount());
        assertEquals(siteCount, metrics.generatedNativeTextSiteCodecCount());
        assertTrue(metrics.generatedNativeTextCodecFamilyCount() >= 2);
        assertEquals(0, metrics.generatedNativeTextDecoderCount());
        assertEquals(0, metrics.generatedNativeTextLargestDecoderFanout());
        assertEquals(0, metrics.generatedNativeTextFixedShapeOccurrences());
        assertEquals(
                0,
                metrics.generatedNativeTextAdjacentSeedCipherOccurrences());
        assertTrue(json.contains(
                "\"generatedNativeTextCodecFamilyCount\""));
        assertTrue(json.contains(
                "\"generatedNativeTextLargestDecoderFanout\""));
    }

    private byte[] concat(byte[]... parts) {
        int length = java.util.Arrays.stream(parts)
                .mapToInt(part -> part.length)
                .sum();
        byte[] joined = new byte[length];
        int offset = 0;
        for (byte[] part : parts) {
            System.arraycopy(part, 0, joined, offset, part.length);
            offset += part.length;
        }
        return joined;
    }
}
