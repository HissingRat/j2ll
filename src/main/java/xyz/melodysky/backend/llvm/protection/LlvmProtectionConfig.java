package xyz.melodysky.backend.llvm.protection;

public record LlvmProtectionConfig(boolean enabled, long seed, CallIndirectionMode callIndirectionMode) {
    public LlvmProtectionConfig {
        java.util.Objects.requireNonNull(callIndirectionMode, "callIndirectionMode");
    }

    public static LlvmProtectionConfig enabled(long seed) {
        return new LlvmProtectionConfig(true, seed, CallIndirectionMode.TABLE);
    }

    public static LlvmProtectionConfig dispatcher(long seed) {
        return new LlvmProtectionConfig(true, seed, CallIndirectionMode.DISPATCHER);
    }

    public static LlvmProtectionConfig disabled(long seed) {
        return new LlvmProtectionConfig(false, seed, CallIndirectionMode.TABLE);
    }
}
