package xyz.melodysky.config;

public record IrProtectionConfig(
        boolean enabled,
        boolean controlFlowFlattening,
        boolean fakeBranches,
        boolean basicBlockSplitting,
        boolean constantEncryption,
        boolean stringEncryption,
        boolean methodInlining,
        boolean methodSplitting,
        boolean callIndirection,
        boolean fieldInternalization,
        boolean methodTableHiding,
        boolean blockNameObfuscation) {
    public IrProtectionConfig(
            boolean enabled,
            boolean controlFlowFlattening,
            boolean fakeBranches,
            boolean basicBlockSplitting,
            boolean constantEncryption,
            boolean stringEncryption,
            boolean methodInlining,
            boolean methodSplitting,
            boolean callIndirection,
            boolean methodTableHiding,
            boolean blockNameObfuscation) {
        this(
                enabled,
                controlFlowFlattening,
                fakeBranches,
                basicBlockSplitting,
                constantEncryption,
                stringEncryption,
                methodInlining,
                methodSplitting,
                callIndirection,
                false,
                methodTableHiding,
                blockNameObfuscation);
    }
}
