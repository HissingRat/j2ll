package xyz.melodysky.backend.llvm.protection;

public record LlvmProtectionConfig(boolean enabled, long seed) {
    public static LlvmProtectionConfig enabled(long seed) {
        return new LlvmProtectionConfig(true, seed);
    }

    public static LlvmProtectionConfig disabled(long seed) {
        return new LlvmProtectionConfig(false, seed);
    }
}
