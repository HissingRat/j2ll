package xyz.melodysky.report;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import java.util.Comparator;
import java.util.List;
import xyz.melodysky.diagnostic.Diagnostic;
import xyz.melodysky.diagnostic.DiagnosticHints;
import xyz.melodysky.diagnostic.DiagnosticLocation;
import xyz.melodysky.pipeline.MethodEligibility;

public final class ReportJsonWriter {
    private static final Gson GSON = new GsonBuilder()
            .disableHtmlEscaping()
            .serializeNulls()
            .setPrettyPrinting()
            .create();

    public String diagnosticsJson(List<Diagnostic> diagnostics) {
        JsonObject root = new JsonObject();
        root.addProperty("schemaVersion", 1);
        root.addProperty("reportVersion", 1);
        JsonArray entries = new JsonArray();
        diagnostics.stream().sorted().forEach(diagnostic -> entries.add(diagnosticJson(diagnostic)));
        root.add("diagnostics", entries);
        return GSON.toJson(root) + "\n";
    }

    public String loweringJson(
            List<LoweringReportMethod> requestedMethods,
            List<MethodEligibility> ineligible,
            List<MethodEligibility> excluded) {
        JsonObject root = new JsonObject();
        root.addProperty("schemaVersion", 1);
        root.addProperty("reportVersion", 1);
        JsonArray requestedArray = new JsonArray();
        requestedMethods.stream()
                .sorted(Comparator
                        .comparing(LoweringReportMethod::owner)
                        .thenComparing(LoweringReportMethod::name)
                        .thenComparing(LoweringReportMethod::descriptor)
                        .thenComparing(LoweringReportMethod::methodId))
                .forEach(method -> requestedArray.add(loweringMethodJson(method)));
        root.add("requestedMethods", requestedArray);
        root.add("ineligible", eligibilityArray(ineligible));
        root.add("excluded", eligibilityArray(excluded));
        return GSON.toJson(root) + "\n";
    }

    private JsonObject diagnosticJson(Diagnostic diagnostic) {
        DiagnosticLocation location = diagnostic.location();
        JsonObject object = new JsonObject();
        object.addProperty("severity", diagnostic.severity().wireName());
        object.addProperty("code", diagnostic.code().value());
        object.addProperty("stage", diagnostic.stage().name());
        nullableString(object, "class", location.className());
        nullableString(object, "method", location.methodName());
        nullableString(object, "descriptor", location.descriptor());
        nullableNumber(object, "instructionOffset", location.instructionOffset());
        nullableString(object, "artifactId", location.artifactId());
        object.addProperty("message", diagnostic.message());
        object.addProperty("hint", DiagnosticHints.hint(diagnostic));
        nullableString(object, "decision", diagnostic.decision());
        return object;
    }

    private JsonObject loweringMethodJson(LoweringReportMethod method) {
        JsonObject object = new JsonObject();
        object.addProperty("class", method.owner());
        object.addProperty("method", method.name());
        object.addProperty("descriptor", method.descriptor());
        object.addProperty("methodId", method.methodId());
        object.addProperty("status", method.status().wireName());
        if (method.rewriteStrategy() == null) {
            object.add("rewriteStrategy", JsonNull.INSTANCE);
        } else {
            object.addProperty("rewriteStrategy", method.rewriteStrategy().wireName());
        }
        object.addProperty(
                "retentionMode",
                method.retentionMode().wireName());
        object.addProperty(
                "javaMethodPresent",
                method.javaMethodPresent());
        object.addProperty(
                "registrationPresent",
                method.registrationPresent());
        object.add("accessFlags", stringArray(method.accessFlags()));
        object.add("compilerFlags", stringArray(method.compilerFlags()));
        nullableString(object, "nativeSymbol", method.nativeSymbol());
        nullableString(object, "registrationOwner", method.registrationOwner());
        nullableString(object, "nativeImplementationPath", method.nativeImplementationPath());
        if (method.coalescedInto() != null) {
            object.addProperty("coalescedInto", method.coalescedInto());
        }
        JsonArray helperBackedSites = new JsonArray();
        for (HelperBackedSiteReport site : method.helperBackedSites()) {
            JsonObject siteJson = new JsonObject();
            siteJson.addProperty("helperKind", site.helperKind());
            siteJson.addProperty(
                    "helperIdentityHash",
                    site.helperIdentityHash());
            siteJson.addProperty("reasonCode", site.reasonCode());
            helperBackedSites.add(siteJson);
        }
        object.add("helperBackedSites", helperBackedSites);
        nullableString(object, "reasonCode", method.reasonCode());
        nullableString(object, "reason", method.reason());
        return object;
    }

    private JsonArray eligibilityArray(List<MethodEligibility> eligibilities) {
        JsonArray array = new JsonArray();
        eligibilities.stream()
                .sorted(Comparator
                        .comparing(MethodEligibility::owner)
                        .thenComparing(MethodEligibility::name)
                        .thenComparing(MethodEligibility::descriptor))
                .forEach(eligibility -> {
                    JsonObject object = new JsonObject();
                    object.addProperty("selector", eligibility.selector());
                    object.addProperty("class", eligibility.owner());
                    object.addProperty("method", eligibility.name());
                    object.addProperty("descriptor", eligibility.descriptor());
                    object.addProperty("status", eligibility.status().wireName());
                    object.addProperty("reasonCode", eligibility.reasonCode());
                    object.addProperty("reason", eligibility.reason());
                    array.add(object);
                });
        return array;
    }

    private JsonArray stringArray(List<String> values) {
        JsonArray array = new JsonArray();
        for (String value : values) {
            array.add(value);
        }
        return array;
    }

    private void nullableString(JsonObject object, String field, String value) {
        if (value == null) {
            object.add(field, JsonNull.INSTANCE);
        } else {
            object.addProperty(field, value);
        }
    }

    private void nullableNumber(JsonObject object, String field, Integer value) {
        if (value == null) {
            object.add(field, JsonNull.INSTANCE);
        } else {
            object.addProperty(field, value);
        }
    }
}
