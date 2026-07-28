package xyz.melodysky.protection.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class ProtectionPassCoverageAuditTest {
    private final ProtectionPassCoverageAggregator aggregator =
            new ProtectionPassCoverageAggregator();
    private final ProtectionCoverageDiffAudit diffAudit =
            new ProtectionCoverageDiffAudit();

    @Test
    void aggregatesRequestedApplicableAffectedStatusAndReasonExactly() {
        String first = subject("pkg/Foo#first!()V");
        String second = subject("pkg/Foo#second!()V");
        String third = subject("pkg/Foo#third!()V");
        ProtectionCoverageSnapshot snapshot = aggregator.aggregate(List.of(
                fact(
                        "IR",
                        "CONTROL_FLOW_FLATTENING",
                        first,
                        true,
                        ProtectionApplicability.APPLICABLE,
                        true,
                        "RAN",
                        "CONTROL_FLOW_FLATTENING"),
                fact(
                        "IR",
                        "CONTROL_FLOW_FLATTENING",
                        second,
                        true,
                        ProtectionApplicability.NOT_APPLICABLE,
                        false,
                        "SKIPPED",
                        "PROTECTION_MONITOR_SENSITIVE_SKIP"),
                fact(
                        "IR",
                        "CONTROL_FLOW_FLATTENING",
                        third,
                        true,
                        ProtectionApplicability.UNKNOWN,
                        false,
                        "SKIPPED",
                        "APPLICABILITY_NOT_RECORDED"),
                fact(
                        "LLVM",
                        "LLVM_GLOBAL_LAYOUT",
                        first,
                        false,
                        ProtectionApplicability.UNKNOWN,
                        false,
                        "SKIPPED",
                        "PROTECTION_PASS_DISABLED")));
        String json = new ProtectionCoverageReportWriter().json(snapshot);

        assertEquals(4, snapshot.evaluatedFacts());
        assertEquals(3, snapshot.requestedFacts());
        assertEquals(1, snapshot.applicableFacts());
        assertEquals(1, snapshot.notApplicableFacts());
        assertEquals(2, snapshot.unknownApplicabilityFacts());
        assertEquals(1, snapshot.affectedFacts());
        assertEquals(10_000, snapshot.affectedRateBasisPoints());
        assertEquals(2, snapshot.passes().size());
        ProtectionPassCoverageRow cff = snapshot.passes().stream()
                .filter(row -> row.passName().equals(
                        "CONTROL_FLOW_FLATTENING"))
                .findFirst()
                .orElseThrow();
        assertEquals(3, cff.evaluatedSubjects());
        assertEquals(1, cff.affectedSubjects());
        assertEquals(2, cff.statusCounts().get("SKIPPED"));
        assertEquals(
                1,
                cff.reasonCounts().get(
                        "PROTECTION_MONITOR_SENSITIVE_SKIP"));
        assertTrue(json.contains("\"applicability\": \"unknown\""));
        assertTrue(json.contains("\"affectedRateBasisPoints\": 10000"));
        assertFalse(json.contains("pkg/Foo"));
    }

    @Test
    void dualBuildDiffReportsCoverageAndReasonChangesPerPass() {
        String first = subject("pkg/Foo#first!()V");
        String second = subject("pkg/Foo#second!()V");
        ProtectionCoverageSnapshot left = aggregator.aggregate(List.of(
                fact(
                        "IR",
                        "FAKE_BRANCHES",
                        first,
                        true,
                        ProtectionApplicability.APPLICABLE,
                        true,
                        "RAN",
                        "FAKE_BRANCHES"),
                fact(
                        "IR",
                        "FAKE_BRANCHES",
                        second,
                        true,
                        ProtectionApplicability.NOT_APPLICABLE,
                        false,
                        "SKIPPED",
                        "PROTECTION_MONITOR_SENSITIVE_SKIP")));
        ProtectionCoverageSnapshot right = aggregator.aggregate(List.of(
                fact(
                        "IR",
                        "FAKE_BRANCHES",
                        first,
                        true,
                        ProtectionApplicability.NOT_APPLICABLE,
                        false,
                        "SKIPPED",
                        "PROTECTION_HANDLER_SENSITIVE_SKIP"),
                fact(
                        "IR",
                        "FAKE_BRANCHES",
                        second,
                        true,
                        ProtectionApplicability.NOT_APPLICABLE,
                        false,
                        "SKIPPED",
                        "PROTECTION_MONITOR_SENSITIVE_SKIP")));

        ProtectionCoverageDiffMetric diff = diffAudit.compare(left, right);
        String json = new ProtectionCoverageReportWriter().diffJson(diff);

        assertEquals(2, diff.commonFactCount());
        assertEquals(1, diff.applicabilityChangedCount());
        assertEquals(1, diff.affectedChangedCount());
        assertEquals(1, diff.statusChangedCount());
        assertEquals(1, diff.reasonChangedCount());
        assertEquals(-1, diff.affectedDelta());
        assertEquals(
                ProtectionCoverageDiffAudit.COVERAGE_CHANGED,
                diff.reasonCode());
        assertEquals(1, diff.passes().size());
        assertEquals(-1, diff.passes().get(0).affectedDelta());
        assertTrue(json.contains("\"affectedDelta\": -1"));
        assertTrue(json.contains(
                "\"reasonCode\": \"PROTECTION_COVERAGE_CHANGED\""));
    }

    @Test
    void unknownApplicabilityIsNotInferredFromSkippedStatus() {
        ProtectionCoverageSnapshot snapshot = aggregator.aggregate(List.of(
                fact(
                        "LLVM",
                        "LLVM_OPAQUE_PREDICATES",
                        subject("module:one"),
                        true,
                        ProtectionApplicability.UNKNOWN,
                        false,
                        "SKIPPED",
                        "LLVM_OPAQUE_PREDICATES_NO_CANDIDATE")));

        assertEquals(0, snapshot.applicableFacts());
        assertEquals(0, snapshot.notApplicableFacts());
        assertEquals(1, snapshot.unknownApplicabilityFacts());
    }

    @Test
    void rejectsDuplicateFactsAndImpossibleAffectedClaim() {
        ProtectionPassCoverageFact fact = fact(
                "IR",
                "BASIC_BLOCK_SPLITTING",
                subject("pkg/Foo#run!()V"),
                true,
                ProtectionApplicability.APPLICABLE,
                true,
                "RAN",
                "BASIC_BLOCK_SPLITTING");

        assertThrows(
                IllegalArgumentException.class,
                () -> aggregator.aggregate(List.of(fact, fact)));
        assertThrows(
                IllegalArgumentException.class,
                () -> fact(
                        "IR",
                        "BASIC_BLOCK_SPLITTING",
                        subject("pkg/Foo#bad!()V"),
                        true,
                        ProtectionApplicability.UNKNOWN,
                        true,
                        "RAN",
                        "BASIC_BLOCK_SPLITTING"));
    }

    private ProtectionPassCoverageFact fact(
            String layer,
            String passName,
            String subject,
            boolean requested,
            ProtectionApplicability applicability,
            boolean affected,
            String status,
            String reasonCode) {
        return new ProtectionPassCoverageFact(
                layer,
                passName,
                subject,
                requested,
                applicability,
                affected,
                status,
                reasonCode);
    }

    private String subject(String logicalIdentity) {
        return HashOnlyEvidence.sha256(
                "protection-subject",
                logicalIdentity);
    }
}
