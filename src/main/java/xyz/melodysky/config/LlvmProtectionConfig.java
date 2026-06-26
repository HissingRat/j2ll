package xyz.melodysky.config;

import java.util.Objects;

public record LlvmProtectionConfig(
        boolean enabled,
        PassConfig nameObfuscation,
        PassConfig opaquePredicates,
        PassConfig blockLayoutPerturbation,
        PassConfig indirectCalls,
        PassConfig globalLayout,
        VisibilityHardeningConfig visibilityHardening) {
    public LlvmProtectionConfig {
        Objects.requireNonNull(nameObfuscation, "nameObfuscation");
        Objects.requireNonNull(opaquePredicates, "opaquePredicates");
        Objects.requireNonNull(blockLayoutPerturbation, "blockLayoutPertation");
        Objects.requireNonNull(indirectCalls, "indirectCalls");
        Objects.requireNonNull(globalLayout, "globalLayout");
        Objects.requireNonNull(visibilityHardening, "visibilityHardening");
    }
}
