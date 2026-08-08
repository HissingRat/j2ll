package xyz.melodysky.toolchain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

final class NativeMachineOutlinerPolicyTest {
    @Test
    void linuxAndMacosGeneratedCUseTheBoundedLlvmMachineOutliner() {
        for (TargetTriple target : List.of(
                TargetTriple.LINUX_X64,
                TargetTriple.LINUX_ARM64,
                TargetTriple.MACOS_X64,
                TargetTriple.MACOS_ARM64)) {
            NativeMachineOutlinerPolicy policy =
                    NativeMachineOutlinerPolicy.forTarget(target);
            assertTrue(policy.enabled(), target.toString());
            assertEquals(16, policy.minimumBenefitThreshold());
            assertEquals(
                    List.of(
                            "-mllvm",
                            "-enable-machine-outliner=always",
                            "-mllvm",
                            "-outliner-benefit-threshold=16"),
                    policy.cFlags());
            assertEquals("MACHINE_OUTLINER_ELF_MACHO_ENABLED", policy.reasonCode());
        }
    }

    @Test
    void windowsKeepsTheOutlinerOffBecauseOfSehEmission() {
        for (TargetTriple target :
                List.of(TargetTriple.WINDOWS_X64, TargetTriple.WINDOWS_ARM64)) {
            NativeMachineOutlinerPolicy policy =
                    NativeMachineOutlinerPolicy.forTarget(target);
            assertFalse(policy.enabled(), target.toString());
            assertEquals(0, policy.minimumBenefitThreshold());
            assertTrue(policy.cFlags().isEmpty());
            assertEquals(
                    "MACHINE_OUTLINER_WINDOWS_SEH_UNSUPPORTED",
                    policy.reasonCode());
        }
    }
}
