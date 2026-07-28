package xyz.melodysky.toolchain.nativetext;

import java.util.List;
import java.util.Objects;

/** Stable generated-C findings and positive hardening evidence. */
public record GeneratedNativeHardeningAuditResult(
        List<GeneratedNativeHardeningFinding> findings,
        List<String> evidence) {
    public GeneratedNativeHardeningAuditResult {
        findings = findings.stream()
                .filter(Objects::nonNull)
                .sorted()
                .distinct()
                .toList();
        evidence = evidence.stream()
                .filter(Objects::nonNull)
                .filter(value -> !value.isBlank())
                .sorted()
                .distinct()
                .toList();
    }

    public boolean passed() {
        return findings.isEmpty();
    }
}
