package xyz.melodysky.ir.pass.protection;

public record ProtectionConfig(
        boolean enabled,
        long seed,
        boolean controlFlowFlattening,
        boolean stringEncryption,
        boolean constantEncryption,
        boolean basicBlockSplitting,
        boolean fakeBranches,
        boolean blockNameObfuscation) {
    public static ProtectionConfig disabled(long seed) {
        return new ProtectionConfig(false, seed, false, false, false, false, false, false);
    }

    public static ProtectionConfig enabled(long seed) {
        return new ProtectionConfig(true, seed, true, true, true, true, true, true);
    }

    public static ProtectionConfig fromResolved(xyz.melodysky.config.ProtectionConfig protection, long seed) {
        if (!protection.enabled() || !protection.ir().enabled()) {
            return disabled(seed);
        }
        return new ProtectionConfig(
                true,
                seed,
                protection.ir().controlFlowFlattening(),
                protection.ir().stringEncryption(),
                protection.ir().constantEncryption(),
                protection.ir().basicBlockSplitting(),
                protection.ir().fakeBranches(),
                protection.ir().blockNameObfuscation());
    }
}
