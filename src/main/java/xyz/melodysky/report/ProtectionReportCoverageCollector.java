package xyz.melodysky.report;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.TreeMap;
import xyz.melodysky.protection.audit.HashOnlyEvidence;
import xyz.melodysky.protection.audit.ProtectionApplicability;
import xyz.melodysky.protection.audit.ProtectionPassCoverageFact;

/**
 * Collects producer-supplied protection coverage for the stable protection
 * report.
 *
 * <p>Compatibility report rows that do not yet persist applicability remain
 * explicit {@link ProtectionApplicability#UNKNOWN} facts. Program/LLVM
 * protection producers supply their own per-method or module-subject facts.
 * In particular, this adapter never derives applicability from
 * {@code status=SKIPPED}.
 */
final class ProtectionReportCoverageCollector {
    private static final String METHOD_SUBJECT_DOMAIN =
            "protection-report-method-subject";

    List<ProtectionPassCoverageFact> collect(
            List<ProtectionPassReport> reports) {
        Objects.requireNonNull(reports, "reports");
        TreeMap<String, ProtectionPassCoverageFact> facts = new TreeMap<>();
        for (ProtectionPassReport report : reports) {
            Objects.requireNonNull(report, "protection pass report");
            for (ProtectionPassCoverageFact fact : report.coverageFacts()) {
                if (!fact.layer().equals(report.layer())
                        || !fact.passName().equals(report.passName())) {
                    throw new IllegalArgumentException(
                            "protection coverage fact does not match its pass report");
                }
                add(facts, fact);
            }
            for (String methodKey : report.affectedMethods()) {
                String subjectHash = methodSubjectHash(methodKey);
                String factKey = factKey(
                        report.layer(),
                        report.passName(),
                        subjectHash);
                if (facts.containsKey(factKey)) {
                    continue;
                }
                add(facts, new ProtectionPassCoverageFact(
                        report.layer(),
                        report.passName(),
                        subjectHash,
                        !report.reasonCode().equals("PROTECTION_PASS_DISABLED"),
                        ProtectionApplicability.UNKNOWN,
                        false,
                        report.status(),
                        report.reasonCode()));
            }
        }
        return List.copyOf(new ArrayList<>(facts.values()));
    }

    static String methodSubjectHash(String methodKey) {
        return HashOnlyEvidence.sha256(METHOD_SUBJECT_DOMAIN, methodKey);
    }

    private void add(
            TreeMap<String, ProtectionPassCoverageFact> facts,
            ProtectionPassCoverageFact fact) {
        ProtectionPassCoverageFact existing =
                facts.putIfAbsent(fact.factKey(), fact);
        if (existing != null && !existing.equals(fact)) {
            throw new IllegalArgumentException(
                    "conflicting protection coverage fact: " + fact.factKey());
        }
    }

    private String factKey(
            String layer,
            String passName,
            String subjectHash) {
        return layer + "\0" + passName + "\0" + subjectHash;
    }
}
