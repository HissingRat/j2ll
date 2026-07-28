package xyz.melodysky.report;

import java.util.List;
import java.util.Objects;
import xyz.melodysky.protection.audit.ProtectionPassCoverageFact;

public record ProtectionPassReport(
        String passName,
        String layer,
        String status,
        String reasonCode,
        List<String> affectedMethods,
        List<String> affectedSymbols,
        String seed,
        List<SensitivePlaintextFact> sensitivePlaintextFacts,
        List<ProtectionPassCoverageFact> coverageFacts) {
    public ProtectionPassReport(
            String passName,
            String layer,
            String status,
            String reasonCode,
            List<String> affectedMethods,
            List<String> affectedSymbols,
            String seed) {
        this(
                passName,
                layer,
                status,
                reasonCode,
                affectedMethods,
                affectedSymbols,
                seed,
                List.of(),
                List.of());
    }

    public ProtectionPassReport(
            String passName,
            String layer,
            String status,
            String reasonCode,
            List<String> affectedMethods,
            List<String> affectedSymbols,
            String seed,
            List<SensitivePlaintextFact> sensitivePlaintextFacts) {
        this(
                passName,
                layer,
                status,
                reasonCode,
                affectedMethods,
                affectedSymbols,
                seed,
                sensitivePlaintextFacts,
                List.of());
    }

    public ProtectionPassReport {
        Objects.requireNonNull(passName, "passName");
        Objects.requireNonNull(layer, "layer");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(reasonCode, "reasonCode");
        affectedMethods = affectedMethods.stream().filter(Objects::nonNull).sorted().distinct().toList();
        affectedSymbols = affectedSymbols.stream().filter(Objects::nonNull).sorted().distinct().toList();
        Objects.requireNonNull(seed, "seed");
        sensitivePlaintextFacts = sensitivePlaintextFacts.stream()
                .filter(Objects::nonNull)
                .sorted(java.util.Comparator
                        .comparing(SensitivePlaintextFact::literalHash)
                        .thenComparing(SensitivePlaintextFact::sourceMethod)
                        .thenComparing(SensitivePlaintextFact::passName)
                        .thenComparing(SensitivePlaintextFact::pathKind)
                        .thenComparing(SensitivePlaintextFact::gateMode)
                        .thenComparing(SensitivePlaintextFact::promotionReason))
                .toList();
        coverageFacts = coverageFacts.stream()
                .filter(Objects::nonNull)
                .sorted()
                .distinct()
                .toList();
    }
}
