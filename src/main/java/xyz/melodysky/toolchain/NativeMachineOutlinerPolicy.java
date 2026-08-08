package xyz.melodysky.toolchain;

import java.util.List;
import java.util.Objects;

/** Target-specific generated-C machine-outliner policy. */
public record NativeMachineOutlinerPolicy(
        boolean enabled,
        int minimumBenefitThreshold,
        List<String> cFlags,
        String reasonCode) {
    private static final int MINIMUM_BENEFIT_THRESHOLD = 16;
    private static final List<String> LLVM_FLAGS =
            List.of(
                    "-mllvm",
                    "-enable-machine-outliner=always",
                    "-mllvm",
                    "-outliner-benefit-threshold=" + MINIMUM_BENEFIT_THRESHOLD);

    public NativeMachineOutlinerPolicy {
        cFlags = List.copyOf(Objects.requireNonNull(cFlags, "cFlags"));
        Objects.requireNonNull(reasonCode, "reasonCode");
        if (enabled != !cFlags.isEmpty()) {
            throw new IllegalArgumentException(
                    "machine-outliner state and C flags must agree");
        }
        if (minimumBenefitThreshold != (enabled ? MINIMUM_BENEFIT_THRESHOLD : 0)) {
            throw new IllegalArgumentException(
                    "machine-outliner threshold must match its enabled state");
        }
    }

    static NativeMachineOutlinerPolicy forTarget(TargetTriple target) {
        Objects.requireNonNull(target, "target");
        if (target.isWindows()) {
            return new NativeMachineOutlinerPolicy(
                    false,
                    0,
                    List.of(),
                    "MACHINE_OUTLINER_WINDOWS_SEH_UNSUPPORTED");
        }
        return new NativeMachineOutlinerPolicy(
                true,
                MINIMUM_BENEFIT_THRESHOLD,
                LLVM_FLAGS,
                "MACHINE_OUTLINER_ELF_MACHO_ENABLED");
    }
}
