package xyz.melodysky.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static xyz.melodysky.testsupport.NativeFieldInternalizationFixtures.nativeStored;
import static xyz.melodysky.testsupport.NativeFieldInternalizationFixtures.plan;

import com.google.gson.JsonParser;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import xyz.melodysky.analysis.field.FieldId;
import xyz.melodysky.analysis.field.NativeFieldInternalizationDecision;
import xyz.melodysky.analysis.field.NativeFieldInternalizationPlan;
import xyz.melodysky.analysis.hierarchy.AnalysisWorld;
import xyz.melodysky.analysis.world.WholeProgramAnalysisScope;
import xyz.melodysky.toolchain.NativeImplementationPlan;

class FieldInternalizationReportWriterTest {
    @Test
    void reportsHybridStorageAndExactCachePolicyWithoutRawFieldIdentity() {
        FieldId primitive = new FieldId("pkg/State", "distinctivePrimitiveState", "F");
        FieldId reference = new FieldId(
                "pkg/State",
                "distinctiveReferenceState",
                "Ljava/lang/Object;");
        NativeFieldInternalizationPlan plan = plan(List.of(
                approved(primitive, "j2ll_nfs_primitive"),
                approved(reference, "j2ll_nfs_reference")));

        String json = new FieldInternalizationReportWriter().json(
                plan,
                true,
                true);
        var root = JsonParser.parseString(json).getAsJsonObject();

        assertEquals("descriptorAwareHybrid", root.get("storagePolicy").getAsString());
        assertEquals(
                "jvmClassValueObjectArray",
                root.get("referenceStoragePolicy").getAsString());
        assertEquals(
                "jvmClassValuePerDefiningClass+lazyPerNativeFunctionActivationLocalRef",
                root.get("cachePolicy").getAsString());
        assertEquals(
                "noStrongNativeGlobalRefs",
                root.get("globalReferencePolicy").getAsString());
        assertTrue(root.get("unloadAware").getAsBoolean());
        var decisions = root.getAsJsonArray("decisions");
        assertTrue(decisions.asList().stream().anyMatch(element ->
                element.getAsJsonObject().get("storageKind").getAsString().equals("FLOAT")));
        var referenceDecision = decisions.asList().stream()
                .map(element -> element.getAsJsonObject())
                .filter(element -> element.get("storageKind").getAsString().equals("REFERENCE"))
                .findFirst()
                .orElseThrow();
        assertEquals(
                "jvmClassValueSidecar",
                referenceDecision.get("storageLocation").getAsString());
        assertEquals(0, referenceDecision.get("referenceSidecarIndex").getAsInt());
        assertFalse(json.contains(primitive.fieldKey()));
        assertFalse(json.contains(reference.fieldKey()));
        assertFalse(json.contains("distinctivePrimitiveState"));
        assertFalse(json.contains("distinctiveReferenceState"));
    }

    @Test
    void reportsCurrentJarOnlyAuthorizationWithoutClaimingClosedWorld() {
        String json = new FieldInternalizationReportWriter().json(
                NativeFieldInternalizationPlan.empty(),
                true,
                true,
                new NativeImplementationPlan(List.of()),
                AnalysisWorld.PARTIAL_WORLD,
                WholeProgramAnalysisScope.CURRENT_JAR_ONLY_USER_APPROVED,
                false,
                true);
        var root = JsonParser.parseString(json).getAsJsonObject();
        var world = root.getAsJsonObject("worldAnalysis");

        assertEquals(2, root.get("reportVersion").getAsInt());
        assertEquals("CLOSED_WORLD", world.get("requiredWorldModel").getAsString());
        assertEquals("PARTIAL_WORLD", world.get("configuredWorldModel").getAsString());
        assertEquals("CURRENT_JAR_ONLY", world.get("scope").getAsString());
        assertEquals("USER_CONFIRMED", world.get("authorization").getAsString());
        assertFalse(world.get("classPathAnalyzed").getAsBoolean());
        assertTrue(world.get("analysisExecuted").getAsBoolean());
        assertEquals(
                "OUT_OF_SCOPE_USER_ACCEPTED",
                world.get("externalObserverPolicy").getAsString());
    }

    @Test
    void reportConsumesExplicitDiversifiedReferenceIndicesFromPlan() {
        FieldId first =
                new FieldId("pkg/State", "alpha", "Ljava/lang/Object;");
        FieldId second =
                new FieldId("pkg/State", "omega", "[Ljava/lang/String;");
        NativeFieldInternalizationPlan plan = new NativeFieldInternalizationPlan(
                List.of(
                        approved(first, "j2ll_nfs_first"),
                        approved(second, "j2ll_nfs_second")),
                Map.of(first.owner(), Map.of(first, 1, second, 0)));

        var decisions = JsonParser.parseString(
                        new FieldInternalizationReportWriter().json(plan, true, true))
                .getAsJsonObject()
                .getAsJsonArray("decisions");
        var firstReport = decisions.asList().stream()
                .map(element -> element.getAsJsonObject())
                .filter(element -> element
                        .get("nativeSlotId")
                        .getAsString()
                        .equals("j2ll_nfs_first"))
                .findFirst()
                .orElseThrow();
        var secondReport = decisions.asList().stream()
                .map(element -> element.getAsJsonObject())
                .filter(element -> element
                        .get("nativeSlotId")
                        .getAsString()
                        .equals("j2ll_nfs_second"))
                .findFirst()
                .orElseThrow();

        assertEquals(1, firstReport.get("referenceSidecarIndex").getAsInt());
        assertEquals(0, secondReport.get("referenceSidecarIndex").getAsInt());
    }

    private NativeFieldInternalizationDecision approved(FieldId field, String slot) {
        return nativeStored(field, slot, List.of());
    }
}
