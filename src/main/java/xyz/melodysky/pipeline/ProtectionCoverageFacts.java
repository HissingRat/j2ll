package xyz.melodysky.pipeline;

import java.util.Collection;
import java.util.List;
import xyz.melodysky.protection.audit.HashOnlyEvidence;
import xyz.melodysky.protection.audit.ProtectionApplicability;
import xyz.melodysky.protection.audit.ProtectionPassCoverageFact;

/**
 * Small producer-side factory for stable, hash-only protection coverage.
 */
final class ProtectionCoverageFacts {
    private static final String METHOD_SUBJECT_DOMAIN =
            "protection-report-method-subject";

    private ProtectionCoverageFacts() {
    }

    static ProtectionPassCoverageFact method(
            String layer,
            String passName,
            String methodKey,
            boolean requested,
            ProtectionApplicability applicability,
            boolean affected,
            String status,
            String reasonCode) {
        return new ProtectionPassCoverageFact(
                layer,
                passName,
                HashOnlyEvidence.sha256(
                        METHOD_SUBJECT_DOMAIN,
                        methodKey),
                requested,
                applicability,
                affected,
                status,
                reasonCode);
    }

    static ProtectionPassCoverageFact subject(
            String layer,
            String passName,
            String subjectDomain,
            String subjectIdentity,
            boolean requested,
            ProtectionApplicability applicability,
            boolean affected,
            String status,
            String reasonCode) {
        return new ProtectionPassCoverageFact(
                layer,
                passName,
                HashOnlyEvidence.sha256(
                        subjectDomain,
                        subjectIdentity),
                requested,
                applicability,
                affected,
                status,
                reasonCode);
    }

    static List<ProtectionPassCoverageFact> uniformMethods(
            String layer,
            String passName,
            Collection<String> methodKeys,
            boolean requested,
            ProtectionApplicability applicability,
            boolean affected,
            String status,
            String reasonCode) {
        return methodKeys.stream()
                .distinct()
                .sorted()
                .map(methodKey -> method(
                        layer,
                        passName,
                        methodKey,
                        requested,
                        applicability,
                        affected,
                        status,
                        reasonCode))
                .toList();
    }
}
