package xyz.melodysky.config;

public record BinaryProtectionConfig(
        boolean enabled,
        boolean hideInternalSymbols,
        boolean strip,
        boolean removePdb,
        boolean symbolAudit) {
}
