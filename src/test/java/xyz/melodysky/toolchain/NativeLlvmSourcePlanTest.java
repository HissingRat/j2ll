package xyz.melodysky.toolchain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import xyz.melodysky.backend.llvm.model.LlvmNativeUnwindProof;

class NativeLlvmSourcePlanTest {
    @TempDir
    Path temp;

    @Test
    void disabledRetentionSelectsOnlyTheProvenOmissionVariant() {
        Path safeRetained = temp.resolve("safe.ll");
        Path safeOmission = temp.resolve("safe.no-unwind.ll");
        Path unknownRetained = temp.resolve("unknown.ll");
        NativeLlvmSourcePlan plan = new NativeLlvmSourcePlan(List.of(
                safeSource(safeRetained, safeOmission),
                NativeLlvmSource.unmodeled(unknownRetained)));
        NativeUnwindRetentionDecision linux =
                new NativeUnwindRetentionPolicy(false, false).resolve(TargetTriple.LINUX_X64);

        assertEquals(
                safeOmission.toAbsolutePath().normalize(),
                plan.select(safeRetained, linux));
        assertEquals(
                unknownRetained.toAbsolutePath().normalize(),
                plan.select(unknownRetained, linux));
    }

    @Test
    void configDebugAndWindowsOverridesRetainTheCanonicalVariant() {
        Path retained = temp.resolve("safe.ll");
        Path omission = temp.resolve("safe.no-unwind.ll");
        NativeLlvmSourcePlan plan =
                new NativeLlvmSourcePlan(List.of(safeSource(retained, omission)));

        for (NativeUnwindRetentionDecision decision : List.of(
                new NativeUnwindRetentionPolicy(true, false).resolve(TargetTriple.LINUX_X64),
                new NativeUnwindRetentionPolicy(false, true).resolve(TargetTriple.MACOS_ARM64),
                new NativeUnwindRetentionPolicy(false, false).resolve(TargetTriple.WINDOWS_X64))) {
            assertEquals(retained.toAbsolutePath().normalize(), plan.select(retained, decision));
        }
    }

    @Test
    void summaryAccountsForSafeUnknownAndOpaqueInputsWithoutOverclaimingOmission() {
        Path safeRetained = temp.resolve("safe.ll");
        Path safeOmission = temp.resolve("safe.no-unwind.ll");
        Path unknownRetained = temp.resolve("unknown.ll");
        NativeLlvmSourcePlan mixed = new NativeLlvmSourcePlan(List.of(
                safeSource(safeRetained, safeOmission),
                NativeLlvmSource.unmodeled(unknownRetained)));
        NativeUnwindRetentionDecision disabled =
                new NativeUnwindRetentionPolicy(false, false).resolve(TargetTriple.LINUX_X64);

        NativeLlvmUnwindTargetSummary mixedSummary = mixed.summarize(disabled, 0);

        assertEquals(2, mixedSummary.moduleCount());
        assertEquals(1, mixedSummary.omittedModuleCount());
        assertEquals(1, mixedSummary.retainedModuleCount());
        assertEquals(0, mixedSummary.unmodeledObjectInputCount());
        assertFalse(mixedSummary.finalOmissionExpected());
        assertTrue(mixedSummary.effectiveRetention());
        assertEquals(
                NativeUnwindRetentionReason.LLVM_MODULE_PROOF_RETAINED,
                mixedSummary.reason());

        NativeLlvmSourcePlan safeOnly =
                new NativeLlvmSourcePlan(List.of(safeSource(safeRetained, safeOmission)));
        NativeLlvmUnwindTargetSummary safeSummary = safeOnly.summarize(disabled, 0);
        assertEquals(1, safeSummary.omittedModuleCount());
        assertEquals(0, safeSummary.retainedModuleCount());
        assertTrue(safeSummary.finalOmissionExpected());
        assertFalse(safeSummary.effectiveRetention());
        assertEquals(NativeUnwindRetentionReason.CONFIG_DISABLED, safeSummary.reason());

        NativeLlvmUnwindTargetSummary opaqueSummary = safeOnly.summarize(disabled, 1);
        assertFalse(opaqueSummary.finalOmissionExpected());
        assertTrue(opaqueSummary.effectiveRetention());
        assertEquals(
                NativeUnwindRetentionReason.UNMODELED_OBJECT_INPUT_RETAINED,
                opaqueSummary.reason());
    }

    private NativeLlvmSource safeSource(Path retained, Path omission) {
        return new NativeLlvmSource(
                "pkg/Safe",
                retained,
                Optional.of(omission),
                true,
                LlvmNativeUnwindProof.PROVEN_ABSENT);
    }
}
