package xyz.melodysky.toolchain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import org.junit.jupiter.api.Test;

class NativeLibcTargetDecisionTest {
    private static final NativeLibcRequirementPlan LIBC_FREE =
            new NativeLibcRequirementPlan(false, Set.of());

    @Test
    void linuxAndWindowsCanOmitTheRuntimeDependency() {
        for (TargetTriple target : Set.of(
                TargetTriple.LINUX_X64,
                TargetTriple.LINUX_ARM64,
                TargetTriple.WINDOWS_X64,
                TargetTriple.WINDOWS_ARM64)) {
            NativeLibcTargetDecision decision =
                    NativeLibcTargetDecision.resolve(target, LIBC_FREE);
            assertFalse(decision.generatedSourceRequiresLibc());
            assertFalse(decision.effectiveDependency());
            assertEquals(
                    NativeLibcTargetDecision.Reason.GENERATED_SOURCE_LIBC_FREE,
                    decision.reason());
        }
    }

    @Test
    void macosRetainsItsMandatoryPlatformLibSystemLoadCommand() {
        NativeLibcTargetDecision decision = NativeLibcTargetDecision.resolve(
                TargetTriple.MACOS_ARM64,
                LIBC_FREE);

        assertFalse(decision.generatedSourceRequiresLibc());
        assertTrue(decision.effectiveDependency());
        assertEquals(
                NativeLibcTargetDecision.Reason.MACOS_PLATFORM_LIBSYSTEM_REQUIRED,
                decision.reason());
    }

    @Test
    void generatedLibcCallsRetainTheDependencyEverywhere() {
        NativeLibcRequirementPlan required = NativeLibcRequirementPlan.inspect("free(value);");

        NativeLibcTargetDecision decision = NativeLibcTargetDecision.resolve(
                TargetTriple.LINUX_X64,
                required);

        assertTrue(decision.generatedSourceRequiresLibc());
        assertTrue(decision.effectiveDependency());
        assertEquals(
                NativeLibcTargetDecision.Reason.GENERATED_SOURCE_REQUIRES_LIBC,
                decision.reason());
    }
}
