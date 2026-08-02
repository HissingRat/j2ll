package xyz.melodysky.report;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import xyz.melodysky.analysis.field.NativeFieldInternalizationDecision;
import xyz.melodysky.analysis.field.NativeFieldInternalizationPlan;
import xyz.melodysky.analysis.field.NativeFieldStorageKind;
import xyz.melodysky.analysis.hierarchy.AnalysisWorld;
import xyz.melodysky.analysis.world.WholeProgramAnalysisScope;
import xyz.melodysky.toolchain.NativeImplementationPlan;

public final class FieldInternalizationReportWriter {
    private static final Gson GSON = new GsonBuilder()
            .disableHtmlEscaping()
            .serializeNulls()
            .setPrettyPrinting()
            .create();

    public String json(
            NativeFieldInternalizationPlan plan,
            boolean enabled,
            boolean finalArtifactWritten) {
        return json(plan, enabled, finalArtifactWritten, new NativeImplementationPlan(java.util.List.of()));
    }

    public String json(
            NativeFieldInternalizationPlan plan,
            boolean enabled,
            boolean finalArtifactWritten,
            NativeImplementationPlan implementationPlan) {
        return json(
                plan,
                enabled,
                finalArtifactWritten,
                implementationPlan,
                null,
                WholeProgramAnalysisScope.NOT_REQUIRED,
                false,
                false);
    }

    public String json(
            NativeFieldInternalizationPlan plan,
            boolean enabled,
            boolean finalArtifactWritten,
            NativeImplementationPlan implementationPlan,
            AnalysisWorld configuredWorldModel,
            WholeProgramAnalysisScope analysisScope,
            boolean classPathAnalyzed,
            boolean analysisExecuted) {
        JsonObject root = new JsonObject();
        root.addProperty("schemaVersion", 1);
        root.addProperty("reportVersion", 2);
        root.addProperty("enabled", enabled);
        root.add(
                "worldAnalysis",
                worldAnalysis(
                        configuredWorldModel,
                        analysisScope,
                        classPathAnalyzed,
                        analysisExecuted));
        root.addProperty("storagePolicy", "descriptorAwareHybrid");
        root.addProperty("primitiveStoragePolicy", "perDefiningJclassWeakIdentityAtomicBits");
        root.addProperty("referenceStoragePolicy", "jvmClassValueObjectArray");
        root.addProperty("constantStoragePolicy", "ssaFoldedNoRuntimeStorage");
        root.addProperty("atomicPolicy", "relaxedAtomicPrimitiveBits");
        root.addProperty(
                "cachePolicy",
                "jvmClassValuePerDefiningClass+lazyPerNativeFunctionActivationLocalRef");
        root.addProperty("globalReferencePolicy", "noStrongNativeGlobalRefs");
        root.addProperty(
                "lifecyclePolicy",
                "primitiveJweakLazyCleanup+referenceClassValueLifecycle");
        root.addProperty("unloadAware", true);
        JsonArray decisions = new JsonArray();
        plan.decisions().forEach(decision ->
                decisions.add(decisionJson(
                        plan,
                        decision,
                        finalArtifactWritten,
                        implementationPlan)));
        root.add("decisions", decisions);
        return GSON.toJson(root) + "\n";
    }

    private JsonObject worldAnalysis(
            AnalysisWorld configuredWorldModel,
            WholeProgramAnalysisScope analysisScope,
            boolean classPathAnalyzed,
            boolean analysisExecuted) {
        JsonObject world = new JsonObject();
        world.addProperty("requiredWorldModel", AnalysisWorld.CLOSED_WORLD.name());
        world.addProperty(
                "configuredWorldModel",
                configuredWorldModel == null ? null : configuredWorldModel.name());
        world.addProperty("analysisExecuted", analysisExecuted);
        world.addProperty(
                "scope",
                !analysisExecuted
                        ? "NOT_RUN"
                        : switch (analysisScope) {
                            case DECLARED_CLOSED_WORLD -> "INPUT_JAR_AND_CONFIGURED_CLASSPATH";
                            case CURRENT_JAR_ONLY_USER_APPROVED -> "CURRENT_JAR_ONLY";
                            case NOT_REQUIRED, UNAVAILABLE -> "NOT_RUN";
                        });
        world.addProperty("authorization", switch (analysisScope) {
            case DECLARED_CLOSED_WORLD -> "CONFIG_SATISFIED";
            case CURRENT_JAR_ONLY_USER_APPROVED -> "USER_CONFIRMED";
            case NOT_REQUIRED -> "NOT_REQUIRED";
            case UNAVAILABLE -> "MISSING";
        });
        world.addProperty("classPathAnalyzed", classPathAnalyzed);
        world.addProperty(
                "externalObserverPolicy",
                !analysisExecuted
                        ? "NOT_APPLICABLE"
                        : switch (analysisScope) {
                            case DECLARED_CLOSED_WORLD -> "USER_DECLARED_ABSENT";
                            case CURRENT_JAR_ONLY_USER_APPROVED -> "OUT_OF_SCOPE_USER_ACCEPTED";
                            case NOT_REQUIRED -> "NOT_APPLICABLE";
                            case UNAVAILABLE -> "NOT_COVERED";
                        });
        return world;
    }

    private JsonObject decisionJson(
            NativeFieldInternalizationPlan plan,
            NativeFieldInternalizationDecision decision,
            boolean finalArtifactWritten,
            NativeImplementationPlan implementationPlan) {
        JsonObject object = new JsonObject();
        object.addProperty("fieldIdHash", sha256(decision.field().fieldKey()));
        object.addProperty("status", decision.status().name());
        object.addProperty("internalizationStorage", decision.storage().name());
        NativeFieldStorageKind.fromDescriptor(decision.field().descriptor())
                .ifPresentOrElse(
                        kind -> object.addProperty("storageKind", kind.name()),
                        () -> object.addProperty("storageKind", "UNSUPPORTED"));
        object.addProperty(
                "storageLocation",
                decision.constantFolded()
                        ? "ssaFoldedNoRuntimeStorage"
                        : decision.nativeStored()
                                ? NativeFieldStorageKind.fromDescriptor(decision.field().descriptor())
                                        .filter(NativeFieldStorageKind::reference)
                                        .map(ignored -> "jvmClassValueSidecar")
                                        .orElse("nativeAtomicBits")
                                : null);
        object.addProperty(
                "referenceSidecarIndex",
                decision.nativeStored()
                                && NativeFieldStorageKind.fromDescriptor(decision.field().descriptor())
                                        .filter(NativeFieldStorageKind::reference)
                                        .isPresent()
                        ? plan.referenceIndex(decision)
                        : null);
        object.addProperty(
                "nativeSlotId",
                decision.nativeSlotId().orElse(null));
        JsonArray accessMethods = new JsonArray();
        decision.accesses().stream()
                .map(access -> access.methodKey())
                .sorted()
                .distinct()
                .forEach(accessMethods::add);
        object.add("accessMethods", accessMethods);
        JsonArray finalPaths = new JsonArray();
        decision.accesses().stream()
                .map(access -> implementationPlan.implementationFor(access.methodKey())
                        .map(implementation -> implementation.path().name())
                        .orElse("UNKNOWN"))
                .distinct()
                .sorted()
                .forEach(finalPaths::add);
        object.add("finalImplementationPaths", finalPaths);
        object.addProperty(
                "removedFromOutputClass",
                decision.internalized() && finalArtifactWritten);
        JsonArray reasons = new JsonArray();
        decision.reasons().forEach(reason -> reasons.add(reason.name()));
        object.add("reasonCodes", reasons);
        return object;
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
