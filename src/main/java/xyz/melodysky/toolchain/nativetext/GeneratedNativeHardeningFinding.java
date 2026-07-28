package xyz.melodysky.toolchain.nativetext;

import java.util.Objects;

/** One stable, plaintext-free generated-native hardening finding. */
public record GeneratedNativeHardeningFinding(
        String code,
        int line,
        String detail) implements Comparable<GeneratedNativeHardeningFinding> {
    public GeneratedNativeHardeningFinding {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(detail, "detail");
        if (code.isBlank() || line < 1 || detail.isBlank()) {
            throw new IllegalArgumentException(
                    "native hardening finding requires code, positive line and detail");
        }
    }

    @Override
    public int compareTo(GeneratedNativeHardeningFinding other) {
        int byCode = code.compareTo(other.code);
        if (byCode != 0) {
            return byCode;
        }
        int byLine = Integer.compare(line, other.line);
        return byLine != 0 ? byLine : detail.compareTo(other.detail);
    }
}
