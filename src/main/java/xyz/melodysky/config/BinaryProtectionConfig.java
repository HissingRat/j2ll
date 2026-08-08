package xyz.melodysky.config;

public record BinaryProtectionConfig(
        boolean enabled,
        boolean hideInternalSymbols,
        boolean strip,
        boolean removePdb,
        boolean symbolAudit,
        boolean retainUnwindInfo) {
    public BinaryProtectionConfig(
            boolean enabled,
            boolean hideInternalSymbols,
            boolean strip,
            boolean removePdb,
            boolean symbolAudit) {
        this(enabled, hideInternalSymbols, strip, removePdb, symbolAudit, true);
    }
}
