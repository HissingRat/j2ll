package xyz.melodysky.toolchain.nativetext;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class NativeTextCEmitterTest {
    @Test
    void emitsIndependentCiphertextAndCallSiteLocalScratchWithoutDirectory() {
        NativeTextBuildKey key = NativeTextBuildKey.fromUtf8("fixed-build-key");
        NativeTextEncoder encoder = new NativeTextEncoder();
        NativeTextEncoding owner = encoder.encode(
                key,
                NativeTextPurpose.REGISTRATION_OWNER,
                "owner:secret/Owner",
                "secret/Owner");
        NativeTextEncoding method = encoder.encode(
                key,
                NativeTextPurpose.REGISTRATION_METHOD_NAME,
                "method:secret/Owner#sensitiveMethod!()V",
                "sensitiveMethod");
        NativeTextCEmitter emitter = new NativeTextCEmitter();

        String source = emitter.runtimeSource()
                + emitter.ciphertextDeclaration(owner)
                + emitter.ciphertextDeclaration(method)
                + "static void use_text(void) {\n"
                + emitter.scratchDeclarationAndDecode(owner, "owner_text")
                + emitter.scratchDeclarationAndDecode(method, "method_text")
                + emitter.scratchCleanup(method, "method_text")
                + emitter.scratchCleanup(owner, "owner_text")
                + "}\n";

        assertFalse(source.contains("secret/Owner"));
        assertFalse(source.contains("sensitiveMethod"));
        assertFalse(source.contains("j2ll_encoded_metadata_strings"));
        assertFalse(source.contains("j2ll_decode_metadata_strings"));
        assertFalse(source.contains("typedef struct"));
        assertFalse(source.contains("_table[]"));
        assertTrue(source.contains("#ifndef J2LL_NATIVE_TEXT_RUNTIME_DEFINED"));
        assertTrue(source.contains("#define J2LL_NATIVE_TEXT_RUNTIME_DEFINED 1"));
        assertTrue(source.contains("#endif"));
        assertTrue(source.contains("char owner_text[sizeof(" + owner.symbol() + "_cipher)];"));
        assertTrue(source.contains("char method_text[sizeof(" + method.symbol() + "_cipher)];"));
        assertFalse(source.contains("j2ll_native_text_decode("));
        assertFalse(source.contains("j2ll_native_text_stream("));
        assertFalse(source.contains("0x9e3779b97f4a7c15"));
        assertTrue(source.contains("j2ll_nt_word_"));
        assertTrue(source.contains(
                "((const volatile unsigned char*)("
                        + owner.symbol()
                        + "_cipher))"));
        assertTrue(source.contains(
                "((const volatile unsigned char*)("
                        + method.symbol()
                        + "_cipher))"));
        assertEquals(
                1,
                occurrences(
                        source,
                        "((const volatile unsigned char*)("
                                + owner.symbol()
                                + "_cipher))"));
        assertEquals(
                1,
                occurrences(
                        source,
                        "((const volatile unsigned char*)("
                                + method.symbol()
                                + "_cipher))"));
        assertTrue(source.contains("j2ll_native_text_zero(method_text, sizeof(method_text));"));
        assertTrue(source.contains("j2ll_native_text_zero(owner_text, sizeof(owner_text));"));
        NativeTextSourceMetrics metrics =
                new NativeTextSourceScanner().scan(source);
        assertTrue(metrics.runtimeBoundCipherReadCount()
                == metrics.cipherArrayCount());
        assertTrue(metrics.siteBoundCodecCount() >= 2);
        assertTrue(metrics.largestDecoderCipherFanout() <= 1);
        assertTrue(metrics.fixedDecoderShapeOccurrences() == 0);
        assertTrue(metrics.adjacentSeedCipherOccurrences() == 0);
        GeneratedNativeHardeningAuditResult audit =
                new GeneratedNativeHardeningAudit()
                        .audit(source);
        assertTrue(audit.passed(), audit.findings().toString());
        assertTrue(audit.evidence().contains(
                GeneratedNativeHardeningAudit
                        .EVIDENCE_AFFINE_CIPHERTEXT_STORAGE));
    }

    @Test
    void rejectsUntrustedScratchIdentifiers() {
        NativeTextEncoding encoding = new NativeTextEncoder().encode(
                NativeTextBuildKey.fromUtf8("fixed-build-key"),
                NativeTextPurpose.RUNTIME_ERROR,
                "error:test",
                "test error");

        assertThrows(
                IllegalArgumentException.class,
                () -> new NativeTextCEmitter()
                        .scratchDeclarationAndDecode(encoding, "scratch); injected();"));
    }

    @Test
    void directDecodeApisRejectCiphertextDestinationAlias() {
        NativeTextEncoding encoding = new NativeTextEncoder().encode(
                NativeTextBuildKey.fromUtf8("affine-alias-build"),
                NativeTextPurpose.RUNTIME_ERROR,
                "affine-alias-use",
                "alias boundary");
        NativeTextCEmitter emitter = new NativeTextCEmitter();
        String cipher = encoding.symbol() + "_cipher";

        assertThrows(
                IllegalArgumentException.class,
                () -> emitter.decodeInto(encoding, cipher, ""));
        assertThrows(
                IllegalArgumentException.class,
                () -> emitter.decodeIntoOffset(encoding, cipher, 0, ""));
    }

    private int occurrences(String value, String needle) {
        int count = 0;
        int offset = 0;
        while ((offset = value.indexOf(needle, offset)) >= 0) {
            count++;
            offset += needle.length();
        }
        return count;
    }
}
