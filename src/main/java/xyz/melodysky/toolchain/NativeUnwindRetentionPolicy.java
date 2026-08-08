package xyz.melodysky.toolchain;

import java.util.Objects;

/** Resolves the requested unwind setting against invocation and target ABI requirements. */
public record NativeUnwindRetentionPolicy(boolean requested, boolean debugMode) {
    public static NativeUnwindRetentionPolicy retaining() {
        return new NativeUnwindRetentionPolicy(true, false);
    }

    public NativeUnwindRetentionDecision resolve(TargetTriple target) {
        Objects.requireNonNull(target, "target");
        if (target.isWindows()) {
            return new NativeUnwindRetentionDecision(
                    target,
                    requested,
                    true,
                    NativeUnwindRetentionReason.WINDOWS_SEH_REQUIRED);
        }
        if (debugMode) {
            return new NativeUnwindRetentionDecision(
                    target,
                    requested,
                    true,
                    NativeUnwindRetentionReason.DEBUG_MODE);
        }
        return new NativeUnwindRetentionDecision(
                target,
                requested,
                requested,
                requested
                        ? NativeUnwindRetentionReason.CONFIG_RETAINED
                        : NativeUnwindRetentionReason.CONFIG_DISABLED);
    }
}
