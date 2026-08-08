package xyz.melodysky.toolchain;

import java.util.Objects;

/** Target-effective libc dependency derived from the generated-source plan and platform ABI. */
public record NativeLibcTargetDecision(
        boolean generatedSourceRequiresLibc,
        boolean effectiveDependency,
        Reason reason) {
    public NativeLibcTargetDecision {
        Objects.requireNonNull(reason, "reason");
    }

    static NativeLibcTargetDecision resolve(
            TargetTriple target,
            NativeLibcRequirementPlan requirement) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(requirement, "requirement");
        if (requirement.required()) {
            return new NativeLibcTargetDecision(
                    true,
                    true,
                    Reason.GENERATED_SOURCE_REQUIRES_LIBC);
        }
        if (target.zigOsTag().equals("macos")) {
            return new NativeLibcTargetDecision(
                    false,
                    true,
                    Reason.MACOS_PLATFORM_LIBSYSTEM_REQUIRED);
        }
        return new NativeLibcTargetDecision(
                false,
                false,
                Reason.GENERATED_SOURCE_LIBC_FREE);
    }

    public enum Reason {
        GENERATED_SOURCE_REQUIRES_LIBC,
        GENERATED_SOURCE_LIBC_FREE,
        MACOS_PLATFORM_LIBSYSTEM_REQUIRED
    }
}
