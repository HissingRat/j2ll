package xyz.melodysky.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static xyz.melodysky.testsupport.NativeFieldInternalizationFixtures.plan;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import xyz.melodysky.analysis.field.FieldId;
import xyz.melodysky.analysis.field.FieldInternalizationReason;
import xyz.melodysky.analysis.field.FieldInternalizationStatus;
import xyz.melodysky.analysis.field.NativeFieldConstant;
import xyz.melodysky.analysis.field.NativeFieldInternalizationDecision;
import xyz.melodysky.analysis.field.NativeFieldInternalizationPlan;
import xyz.melodysky.analysis.field.NativeFieldInternalizationStorage;

class FieldInternalizationConstantReportTest {
    @Test
    void reportsStorageWithoutSerializingConstantOrNativeSlot() {
        FieldId field = new FieldId(
                "fixture/Constants",
                "SECRET_TEXT",
                "Ljava/lang/String;");
        NativeFieldInternalizationPlan plan = plan(
                List.of(new NativeFieldInternalizationDecision(
                        field,
                        FieldInternalizationStatus.INTERNALIZED,
                        NativeFieldInternalizationStorage.COMPILE_TIME_CONSTANT,
                        Optional.empty(),
                        NativeFieldConstant.from(
                                field.descriptor(),
                                "distinctive-field-secret"),
                        List.of(),
                        List.of(FieldInternalizationReason.FIELD_CONSTANT_INTERNALIZATION_ELIGIBLE))));

        String json = new FieldInternalizationReportWriter().json(
                plan,
                true,
                true);
        JsonObject decision = JsonParser.parseString(json)
                .getAsJsonObject()
                .getAsJsonArray("decisions")
                .get(0)
                .getAsJsonObject();

        assertTrue(json.contains(
                "\"constantStoragePolicy\": \"ssaFoldedNoRuntimeStorage\""));
        assertEquals(
                "COMPILE_TIME_CONSTANT",
                decision.get("internalizationStorage").getAsString());
        assertEquals(
                "ssaFoldedNoRuntimeStorage",
                decision.get("storageLocation").getAsString());
        assertTrue(decision.get("nativeSlotId").isJsonNull());
        assertTrue(decision.get("referenceSidecarIndex").isJsonNull());
        assertTrue(decision.get("removedFromOutputClass").getAsBoolean());
        assertFalse(json.contains(field.fieldKey()));
        assertFalse(json.contains("distinctive-field-secret"));
    }
}
