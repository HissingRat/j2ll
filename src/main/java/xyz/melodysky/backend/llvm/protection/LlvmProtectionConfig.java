package xyz.melodysky.backend.llvm.protection;

import java.util.Objects;

public record LlvmProtectionConfig(
        boolean enabled,
        long seed,
        boolean nameObfuscation,
        boolean opaquePredicates,
        boolean blockLayoutPerturbation,
        boolean indirectCalls,
        boolean globalLayout,
        CallIndirectionMode callIndirectionMode) {
    public LlvmProtectionConfig {
        Objects.requireNonNull(callIndirectionMode, "callIndirectionMode");
    }

    public static LlvmProtectionConfig enabled(long seed) {
        return new LlvmProtectionConfig(true, seed, true, true, true, true, true, CallIndirectionMode.TABLE);
    }

    public static LlvmProtectionConfig dispatcher(long seed) {
        return new LlvmProtectionConfig(
                true,
                seed,
                true,
                true,
                true,
                true,
                true,
                CallIndirectionMode.DISPATCHER);
    }

    public static LlvmProtectionConfig selected(
            long seed,
            boolean nameObfuscation,
            boolean opaquePredicates,
            boolean blockLayoutPerturbation,
            boolean indirectCalls,
            boolean globalLayout) {
        boolean anyEnabled = nameObfuscation
                || opaquePredicates
                || blockLayoutPerturbation
                || indirectCalls
                || globalLayout;
        return new LlvmProtectionConfig(
                anyEnabled,
                seed,
                nameObfuscation,
                opaquePredicates,
                blockLayoutPerturbation,
                indirectCalls,
                globalLayout,
                CallIndirectionMode.TABLE);
    }

    public static LlvmProtectionConfig disabled(long seed) {
        return new LlvmProtectionConfig(
                false,
                seed,
                false,
                false,
                false,
                false,
                false,
                CallIndirectionMode.TABLE);
    }
}
