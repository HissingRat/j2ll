package xyz.melodysky.toolchain;

import java.util.Objects;

public record ManagedZigBootstrapEvent(
        String code,
        String message,
        String archiveName,
        String archiveSha256,
        String checksumStatus,
        String signatureStatus,
        String source) {
    public ManagedZigBootstrapEvent(String code, String message) {
        this(code, message, null, null, null, null, null);
    }

    public ManagedZigBootstrapEvent {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(message, "message");
    }
}
