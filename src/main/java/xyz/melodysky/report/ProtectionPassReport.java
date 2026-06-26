package xyz.melodysky.report;

import java.util.List;
import java.util.Objects;

public record ProtectionPassReport(
        String passName,
        String layer,
        String status,
        String reasonCode,
        List<String> affectedMethods,
        List<String> affectedSymbols,
        String seed) {
    public ProtectionPassReport {
        Objects.requireNonNull(passName, "passName");
        Objects.requireNonNull(layer, "layer");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(reasonCode, "reasonCode");
        affectedMethods = affectedMethods.stream().filter(Objects::nonNull).sorted().distinct().toList();
        affectedSymbols = affectedSymbols.stream().filter(Objects::nonNull).sorted().distinct().toList();
        Objects.requireNonNull(seed, "seed");
    }
}
