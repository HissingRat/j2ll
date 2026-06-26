package xyz.melodysky.config;

import java.util.Objects;

public record IrProtectionConfig(
        boolean enabled,
        PassConfig controlFlowFlattening,
        PassConfig fakeBranches,
        PassConfig basicBlockSplitting,
        PassConfig constantEncryption,
        StringEncryptionConfig stringEncryption,
        PassConfig methodInlining,
        PassConfig methodSplitting,
        PassConfig callIndirection,
        PassConfig methodTableHiding) {
    public IrProtectionConfig {
        Objects.requireNonNull(controlFlowFlattening, "controlFlowFlattening");
        Objects.requireNonNull(fakeBranches, "fakeBranches");
        Objects.requireNonNull(basicBlockSplitting, "basicBlockSplitting");
        Objects.requireNonNull(constantEncryption, "constantEncryption");
        Objects.requireNonNull(stringEncryption, "stringEncryption");
        Objects.requireNonNull(methodInlining, "methodInlining");
        Objects.requireNonNull(methodSplitting, "methodSplitting");
        Objects.requireNonNull(callIndirection, "callIndirection");
        Objects.requireNonNull(methodTableHiding, "methodTableHiding");
    }
}
