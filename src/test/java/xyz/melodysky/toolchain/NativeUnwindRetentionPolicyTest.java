package xyz.melodysky.toolchain;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class NativeUnwindRetentionPolicyTest {
    @Test
    void windowsAlwaysRetainsUnwindForSeh() {
        NativeUnwindRetentionDecision decision =
                new NativeUnwindRetentionPolicy(false, false)
                        .resolve(TargetTriple.WINDOWS_X64);

        assertFalse(decision.requested());
        assertTrue(decision.effective());
        assertTrue(decision.reason() == NativeUnwindRetentionReason.WINDOWS_SEH_REQUIRED);
    }

    @Test
    void debugForcesRetentionOnNonWindowsTargets() {
        NativeUnwindRetentionDecision decision =
                new NativeUnwindRetentionPolicy(false, true)
                        .resolve(TargetTriple.MACOS_ARM64);

        assertFalse(decision.requested());
        assertTrue(decision.effective());
        assertTrue(decision.reason() == NativeUnwindRetentionReason.DEBUG_MODE);
    }

    @Test
    void linuxAndMacosOtherwiseFollowRequestedValue() {
        NativeUnwindRetentionDecision disabled =
                new NativeUnwindRetentionPolicy(false, false)
                        .resolve(TargetTriple.LINUX_ARM64);
        NativeUnwindRetentionDecision retained =
                new NativeUnwindRetentionPolicy(true, false)
                        .resolve(TargetTriple.MACOS_X64);

        assertFalse(disabled.effective());
        assertTrue(disabled.reason() == NativeUnwindRetentionReason.CONFIG_DISABLED);
        assertTrue(retained.effective());
        assertTrue(retained.reason() == NativeUnwindRetentionReason.CONFIG_RETAINED);
    }
}
