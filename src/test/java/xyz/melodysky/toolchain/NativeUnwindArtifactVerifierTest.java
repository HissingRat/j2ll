package xyz.melodysky.toolchain;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import xyz.melodysky.toolchain.symbols.NativeUnwindSectionInspection;

final class NativeUnwindArtifactVerifierTest {
    private final NativeUnwindArtifactVerifier verifier =
            new NativeUnwindArtifactVerifier();

    @Test
    void blocksNonEmptySectionsWhenFinalOmissionWasProven() {
        NativeLlvmUnwindTargetSummary summary = summary(
                TargetTriple.LINUX_X64,
                true);
        IOException failure = assertThrows(
                IOException.class,
                () -> verifier.verify(
                        summary,
                        new NativeUnwindSectionInspection(
                                TargetTriple.LINUX_X64,
                                Map.of(".eh_frame", 68L)),
                        Path.of("libexample.so")));
        assertTrue(failure.getMessage().contains(
                "NATIVE_UNWIND_OMISSION_AUDIT_FAILED"));
    }

    @Test
    void acceptsEmptyOmissionAndDoesNotRequireSectionsInRetentionMode() {
        assertDoesNotThrow(() -> verifier.verify(
                summary(TargetTriple.LINUX_X64, true),
                new NativeUnwindSectionInspection(
                        TargetTriple.LINUX_X64,
                        Map.of()),
                Path.of("libexample.so")));
        assertDoesNotThrow(() -> verifier.verify(
                summary(TargetTriple.LINUX_X64, false),
                new NativeUnwindSectionInspection(
                        TargetTriple.LINUX_X64,
                        Map.of()),
                Path.of("libexample.so")));
    }

    @Test
    void rejectsEvidenceForAnotherTarget() {
        IOException failure = assertThrows(
                IOException.class,
                () -> verifier.verify(
                        summary(TargetTriple.LINUX_X64, true),
                        new NativeUnwindSectionInspection(
                                TargetTriple.LINUX_ARM64,
                                Map.of()),
                        Path.of("libexample.so")));
        assertTrue(failure.getMessage().contains(
                "NATIVE_UNWIND_AUDIT_TARGET_MISMATCH"));
    }

    private NativeLlvmUnwindTargetSummary summary(
            TargetTriple target,
            boolean omissionExpected) {
        NativeUnwindRetentionDecision generatedC =
                new NativeUnwindRetentionDecision(
                        target,
                        !omissionExpected,
                        !omissionExpected,
                        omissionExpected
                                ? NativeUnwindRetentionReason.CONFIG_DISABLED
                                : NativeUnwindRetentionReason.CONFIG_RETAINED);
        return new NativeLlvmUnwindTargetSummary(
                generatedC,
                1,
                omissionExpected ? 1 : 0,
                omissionExpected ? 0 : 1,
                0,
                omissionExpected,
                !omissionExpected,
                generatedC.reason());
    }
}
