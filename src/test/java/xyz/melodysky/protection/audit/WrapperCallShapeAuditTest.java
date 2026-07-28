package xyz.melodysky.protection.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class WrapperCallShapeAuditTest {
    private final WrapperCallShapeAudit audit = new WrapperCallShapeAudit();

    @Test
    void measuresFinalBinaryMappingReuseFromExplicitGhidraEvidence() {
        String firstBinding = hash("binding", "pkg/Foo#first!()V");
        String secondBinding = hash("binding", "pkg/Foo#second!(I)I");
        String thirdBinding = hash("binding", "pkg/Foo#third!()V");
        WrapperCallShapeMetric first = audit.summarize(List.of(
                evidence(
                        firstBinding,
                        WrapperCallShape.DIRECT_SINGLE_CALLEE,
                        "pcode:first:v1",
                        WrapperEvidenceKind.GHIDRA_HEADLESS_PCODE),
                evidence(
                        secondBinding,
                        WrapperCallShape.INDIRECT_SLOT,
                        "slot:second:v1",
                        WrapperEvidenceKind.GHIDRA_HEADLESS_PCODE),
                unresolved(
                        thirdBinding,
                        WrapperEvidenceKind.GHIDRA_HEADLESS_PCODE)));
        WrapperCallShapeMetric second = audit.summarize(List.of(
                evidence(
                        firstBinding,
                        WrapperCallShape.DIRECT_SINGLE_CALLEE,
                        "pcode:first:v1",
                        WrapperEvidenceKind.GHIDRA_HEADLESS_PCODE),
                evidence(
                        secondBinding,
                        WrapperCallShape.INDIRECT_DISPATCH,
                        "dispatch:second:v2",
                        WrapperEvidenceKind.GHIDRA_HEADLESS_PCODE),
                unresolved(
                        thirdBinding,
                        WrapperEvidenceKind.GHIDRA_HEADLESS_PCODE)));

        WrapperMappingReuseMetric reuse = audit.compare(first, second);
        String json = new WrapperCallShapeReportWriter().diffJson(reuse);

        assertTrue(first.finalBinaryEvidence());
        assertEquals(3, first.wrapperCount());
        assertEquals(1, first.directSingleCalleeCount());
        assertEquals(1, first.indirectWrapperCount());
        assertEquals(1, first.unresolvedWrapperCount());
        assertTrue(reuse.finalBinaryEvidence());
        assertEquals(3, reuse.commonBindingCount());
        assertEquals(1, reuse.reusableMappingCount());
        assertEquals(3333, reuse.reuseRateBasisPoints());
        assertEquals(List.of(firstBinding), reuse.reusableBindingHashes());
        assertEquals(List.of(secondBinding), reuse.shapeChangedBindingHashes());
        assertEquals(List.of(thirdBinding), reuse.unresolvedBindingHashes());
        assertEquals(
                WrapperCallShapeAudit.FINAL_BINARY_REUSE_MEASURED,
                reuse.reasonCode());
        assertTrue(json.contains("\"reuseRateBasisPoints\": 3333"));
        assertFalse(json.contains("pkg/Foo"));
        assertFalse(json.contains("pcode:first:v1"));
    }

    @Test
    void generatedPlanEvidenceIsMeasuredWithoutPretendingToBeFinalBinary() {
        String binding = hash("binding", "pkg/Foo#run!()V");
        WrapperCallShapeMetric first = audit.summarize(List.of(evidence(
                binding,
                WrapperCallShape.INDIRECT_SLOT,
                "plan-slot-a",
                WrapperEvidenceKind.GENERATED_NATIVE_PLAN)));
        WrapperCallShapeMetric second = audit.summarize(List.of(evidence(
                binding,
                WrapperCallShape.INDIRECT_SLOT,
                "plan-slot-b",
                WrapperEvidenceKind.GENERATED_NATIVE_PLAN)));

        WrapperMappingReuseMetric reuse = audit.compare(first, second);
        String snapshotJson = new WrapperCallShapeReportWriter().json(first);

        assertFalse(first.finalBinaryEvidence());
        assertFalse(reuse.finalBinaryEvidence());
        assertEquals(0, reuse.reusableMappingCount());
        assertEquals(1, reuse.resolutionChangedCount());
        assertEquals(
                WrapperCallShapeAudit.STRUCTURAL_REUSE_MEASURED,
                reuse.reasonCode());
        assertTrue(snapshotJson.contains(
                "\"evidenceKind\": \"generatedNativePlan\""));
        assertTrue(snapshotJson.contains("\"finalBinaryEvidence\": false"));
    }

    @Test
    void rejectsDuplicateOrSelfContradictoryWrapperEvidence() {
        String binding = hash("binding", "pkg/Foo#run!()V");
        WrapperCallEvidence evidence = evidence(
                binding,
                WrapperCallShape.DIRECT_SINGLE_CALLEE,
                "callee",
                WrapperEvidenceKind.BINARY_CONTROL_FLOW);

        assertThrows(
                IllegalArgumentException.class,
                () -> audit.summarize(List.of(evidence, evidence)));
        assertThrows(
                IllegalArgumentException.class,
                () -> new WrapperCallEvidence(
                        binding,
                        WrapperCallShape.UNRESOLVED,
                        hash("resolution", "must-not-exist"),
                        WrapperEvidenceKind.GHIDRA_HEADLESS_PCODE));
    }

    @Test
    void consumesMachineReadableGhidraEvidenceWithoutTextDisassemblyParsing() {
        WrapperCallEvidence expected = evidence(
                hash("binding", "pkg/Foo#run!()V"),
                WrapperCallShape.INDIRECT_SLOT,
                "normalized-pcode-callgraph-v1",
                WrapperEvidenceKind.GHIDRA_HEADLESS_PCODE);
        WrapperCallShapeMetric metric = audit.summarize(List.of(expected));
        String emitted = new WrapperCallShapeReportWriter().json(metric);

        List<WrapperCallEvidence> parsed =
                new WrapperCallEvidenceJsonReader().parse(emitted);

        assertEquals(List.of(expected), parsed);
        assertTrue(audit.summarize(parsed).finalBinaryEvidence());
        assertThrows(
                IllegalArgumentException.class,
                () -> new WrapperCallEvidenceJsonReader().parse("""
                        {"wrappers":[
                          {
                            "bindingIdentityHash":"%s",
                            "shape":"guessedFromRegex",
                            "resolutionFingerprintHash":null,
                            "evidenceKind":"ghidraHeadlessPcode"
                          }
                        ]}
                        """.formatted(expected.bindingIdentityHash())));
    }

    private WrapperCallEvidence evidence(
            String bindingHash,
            WrapperCallShape shape,
            String resolution,
            WrapperEvidenceKind kind) {
        return new WrapperCallEvidence(
                bindingHash,
                shape,
                hash("resolution", resolution),
                kind);
    }

    private WrapperCallEvidence unresolved(
            String bindingHash,
            WrapperEvidenceKind kind) {
        return new WrapperCallEvidence(
                bindingHash,
                WrapperCallShape.UNRESOLVED,
                null,
                kind);
    }

    private String hash(String domain, String value) {
        return HashOnlyEvidence.sha256(domain, value);
    }
}
