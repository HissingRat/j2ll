package xyz.melodysky.config;

import java.util.Objects;

public record ProtectionConfig(
        boolean enabled,
        String seed,
        IrProtectionConfig ir,
        LlvmProtectionConfig llvm,
        BinaryProtectionConfig binary) {
    public ProtectionConfig {
        Objects.requireNonNull(seed, "seed");
        Objects.requireNonNull(ir, "ir");
        Objects.requireNonNull(llvm, "llvm");
        Objects.requireNonNull(binary, "binary");
    }
}
