package xyz.melodysky.report;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import xyz.melodysky.config.SignaturePolicy;
import xyz.melodysky.packaging.MethodRewriteDecision;
import xyz.melodysky.packaging.MethodRewriteStrategy;
import xyz.melodysky.packaging.NativeEmbeddedFallbackBlob;
import xyz.melodysky.packaging.NativeRegistrationEntry;
import xyz.melodysky.toolchain.NativeBuildPlan;
import xyz.melodysky.toolchain.NativeBuildTargetPreflight;
import xyz.melodysky.toolchain.ZigNativeBuildResult;

public final class PackagingReportWriter {
    private static final Gson GSON = new GsonBuilder()
            .disableHtmlEscaping()
            .serializeNulls()
            .setPrettyPrinting()
            .create();

    public String packagingJson(
            Path outputJar,
            SignaturePolicy signaturePolicy,
            List<String> generatedLoaders,
            List<MethodRewriteDecision> rewrittenMethods,
            List<EmbeddedLibraryReport> embeddedLibraries,
            List<NativeRegistrationEntry> registeredNativeMethods,
            List<String> exportedSymbols,
            ZigNativeBuildResult zigBuildResult,
            List<NativeEmbeddedFallbackBlob> fallbackBlobs) {
        return packagingJson(
                outputJar,
                signaturePolicy,
                generatedLoaders,
                rewrittenMethods,
                embeddedLibraries,
                registeredNativeMethods,
                exportedSymbols,
                zigBuildResult,
                new NativeBuildPlan(List.of()),
                fallbackBlobs);
    }

    public String packagingJson(
            Path outputJar,
            SignaturePolicy signaturePolicy,
            List<String> generatedLoaders,
            List<MethodRewriteDecision> rewrittenMethods,
            List<EmbeddedLibraryReport> embeddedLibraries,
            List<NativeRegistrationEntry> registeredNativeMethods,
            List<String> exportedSymbols,
            ZigNativeBuildResult zigBuildResult,
            NativeBuildPlan nativeBuildPlan,
            List<NativeEmbeddedFallbackBlob> fallbackBlobs) {
        JsonObject root = new JsonObject();
        root.addProperty("schemaVersion", 1);
        root.addProperty("outputJar", outputJar.toString().replace('\\', '/'));
        root.addProperty("manifestPolicy", "preserved");
        root.addProperty("signaturePolicy", signaturePolicy.wireName());
        root.add("generatedLoaders", stringArray(generatedLoaders));
        root.add("rewrittenClasses", rewrittenClasses(rewrittenMethods));
        root.add("embeddedLibraries", embeddedLibraries(embeddedLibraries));
        root.add("zigToolchain", zigToolchain(zigBuildResult, nativeBuildPlan));
        root.add("registeredNativeMethods", registeredNativeMethods(registeredNativeMethods));
        root.add("registrationGroups", registrationGroups(registeredNativeMethods));
        root.add("exportedSymbols", stringArray(exportedSymbols));
        root.add("fallbackBlobs", fallbackBlobs(fallbackBlobs));
        return GSON.toJson(root) + "\n";
    }

    private JsonObject zigToolchain(ZigNativeBuildResult result, NativeBuildPlan nativeBuildPlan) {
        JsonObject object = new JsonObject();
        if (result == null) {
            object.addProperty("managed", true);
            object.add("version", com.google.gson.JsonNull.INSTANCE);
            object.add("executable", com.google.gson.JsonNull.INSTANCE);
            object.add("buildZig", com.google.gson.JsonNull.INSTANCE);
            object.add("verificationPolicy", com.google.gson.JsonNull.INSTANCE);
            object.add("selectedTargets", targetNameArray(nativeBuildPlan.targetPreflights()));
            object.add("buildableTargets", targetNameArray(nativeBuildPlan.buildableTargetPreflights()));
            object.add("skippedTargets", targetPreflightArray(nativeBuildPlan.skippedTargetPreflights()));
            object.add("failedTargets", new JsonArray());
            return object;
        }
        object.addProperty("managed", true);
        object.addProperty("version", result.zig().version());
        object.addProperty("executable", result.zig().executable().toString().replace('\\', '/'));
        object.addProperty("buildZig", result.buildZigPath().toString().replace('\\', '/'));
        object.addProperty("manifest", result.manifestPath().toString().replace('\\', '/'));
        object.addProperty("verificationPolicy", result.zig().verificationPolicy());
        object.add("buildCommand", stringArray(result.invocation().command()));
        object.add("selectedTargets", targetNameArray(nativeBuildPlan.targetPreflights()));
        object.add("buildableTargets", targetNameArray(nativeBuildPlan.buildableTargetPreflights()));
        object.add("skippedTargets", targetPreflightArray(nativeBuildPlan.skippedTargetPreflights()));
        object.add("failedTargets", new JsonArray());
        return object;
    }

    private JsonArray targetNameArray(List<NativeBuildTargetPreflight> targets) {
        JsonArray array = new JsonArray();
        targets.stream()
                .sorted(Comparator.comparing(preflight -> preflight.target().directoryName()))
                .map(preflight -> preflight.target().directoryName())
                .forEach(array::add);
        return array;
    }

    private JsonArray targetPreflightArray(List<NativeBuildTargetPreflight> preflights) {
        JsonArray array = new JsonArray();
        preflights.stream()
                .sorted(Comparator.comparing(preflight -> preflight.target().directoryName()))
                .forEach(preflight -> {
                    JsonObject object = new JsonObject();
                    object.addProperty("target", preflight.target().directoryName());
                    object.addProperty("zigTarget", preflight.zigTarget());
                    object.addProperty("output", preflight.outputPath().toString().replace('\\', '/'));
                    object.addProperty("status", preflight.status());
                    object.addProperty("currentHost", preflight.currentHost());
                    object.addProperty("buildable", preflight.buildable());
                    object.addProperty("reasonCode", preflight.reasonCode());
                    object.addProperty("reason", preflight.reason());
                    object.addProperty("requiredCapability", preflight.requiredCapability());
                    object.addProperty("platformSdkRequirement", preflight.platformSdkRequirement());
                    array.add(object);
                });
        return array;
    }

    private JsonArray rewrittenClasses(List<MethodRewriteDecision> rewrittenMethods) {
        Map<String, List<MethodRewriteDecision>> byClass = new LinkedHashMap<>();
        rewrittenMethods.stream()
                .filter(decision -> decision.strategy() != MethodRewriteStrategy.NOT_APPLICABLE)
                .sorted(Comparator
                        .comparing((MethodRewriteDecision decision) -> decision.method().owner())
                        .thenComparing(decision -> decision.method().name())
                        .thenComparing(decision -> decision.method().descriptor()))
                .forEach(decision -> byClass.computeIfAbsent(decision.method().owner(), ignored -> new java.util.ArrayList<>())
                        .add(decision));

        JsonArray classes = new JsonArray();
        for (Map.Entry<String, List<MethodRewriteDecision>> entry : byClass.entrySet()) {
            JsonObject classJson = new JsonObject();
            classJson.addProperty("class", entry.getKey());
            JsonArray methods = new JsonArray();
            for (MethodRewriteDecision decision : entry.getValue()) {
                JsonObject method = new JsonObject();
                method.addProperty("method", decision.method().name());
                method.addProperty("descriptor", decision.method().descriptor());
                method.addProperty("rewriteStrategy", decision.strategy().wireName());
                method.addProperty("registrationOwner", decision.registrationOwner());
                methods.add(method);
            }
            classJson.add("methods", methods);
            classes.add(classJson);
        }
        return classes;
    }

    private JsonArray embeddedLibraries(List<EmbeddedLibraryReport> embeddedLibraries) {
        JsonArray array = new JsonArray();
        embeddedLibraries.stream()
                .sorted(Comparator
                        .comparing(EmbeddedLibraryReport::target)
                        .thenComparing(EmbeddedLibraryReport::jarPath))
                .forEach(library -> {
                    JsonObject object = new JsonObject();
                    object.addProperty("target", library.target());
                    object.addProperty("jarPath", library.jarPath());
                    object.addProperty("sha256", library.sha256());
                    array.add(object);
                });
        return array;
    }

    private JsonArray registeredNativeMethods(List<NativeRegistrationEntry> registeredNativeMethods) {
        JsonArray array = new JsonArray();
        registeredNativeMethods.stream().sorted().forEach(entry -> {
            JsonObject object = new JsonObject();
            object.addProperty("registrationOwner", entry.registrationOwner());
            object.addProperty("method", entry.methodName());
            object.addProperty("descriptor", entry.descriptor());
            object.addProperty("nativeSymbol", entry.nativeSymbol());
            array.add(object);
        });
        return array;
    }

    private JsonArray registrationGroups(List<NativeRegistrationEntry> registeredNativeMethods) {
        Map<String, List<NativeRegistrationEntry>> byOwner = new LinkedHashMap<>();
        registeredNativeMethods.stream()
                .sorted()
                .forEach(entry -> byOwner.computeIfAbsent(entry.registrationOwner(), ignored -> new java.util.ArrayList<>())
                        .add(entry));
        JsonArray groups = new JsonArray();
        for (Map.Entry<String, List<NativeRegistrationEntry>> entry : byOwner.entrySet()) {
            JsonObject group = new JsonObject();
            group.addProperty("registrationOwner", entry.getKey());
            JsonArray methods = new JsonArray();
            for (NativeRegistrationEntry nativeMethod : entry.getValue()) {
                JsonObject method = new JsonObject();
                method.addProperty("method", nativeMethod.methodName());
                method.addProperty("descriptor", nativeMethod.descriptor());
                method.addProperty("nativeSymbol", nativeMethod.nativeSymbol());
                methods.add(method);
            }
            group.add("methods", methods);
            groups.add(group);
        }
        return groups;
    }

    private JsonArray fallbackBlobs(List<NativeEmbeddedFallbackBlob> fallbackBlobs) {
        JsonArray array = new JsonArray();
        fallbackBlobs.stream()
                .sorted(Comparator
                        .comparing(NativeEmbeddedFallbackBlob::originalMethodId)
                        .thenComparing(NativeEmbeddedFallbackBlob::helperClassName))
                .forEach(blob -> {
                    JsonObject object = new JsonObject();
                    object.addProperty("originalMethodId", blob.originalMethodId());
                    object.addProperty("originalMethodKey", blob.originalMethodKey());
                    object.addProperty("helperClassName", blob.helperClassName());
                    object.addProperty("sha256", blob.sha256());
                    object.addProperty("originalSha256", blob.originalSha256());
                    object.addProperty("encodedSha256", blob.encodedSha256());
                    object.addProperty("encodingVersion", blob.encodingVersion());
                    object.addProperty("originalSize", blob.originalSize());
                    object.addProperty("encodedSize", blob.encodedSize());
                    object.addProperty("compressionAlgorithm", blob.compressionAlgorithm());
                    object.addProperty("encryptionAlgorithm", blob.encryptionAlgorithm());
                    object.addProperty("requiredJavaVersion", blob.requiredJavaVersion());
                    object.addProperty("storageTarget", blob.storageTarget());
                    object.addProperty("definitionMechanism", blob.definitionMechanism());
                    object.addProperty("definitionMechanismReasonCode",
                            definitionMechanismReasonCode(blob.definitionMechanism()));
                    object.addProperty("classloaderReusePolicy", blob.classloaderReusePolicy());
                    array.add(object);
                });
        return array;
    }

    private String definitionMechanismReasonCode(String definitionMechanism) {
        if (definitionMechanism.toLowerCase(java.util.Locale.ROOT).contains("hidden")) {
            return "FALLBACK_HIDDEN_CLASS";
        }
        return "FALLBACK_DEFINE_CLASS";
    }

    private JsonArray stringArray(List<String> values) {
        JsonArray array = new JsonArray();
        values.stream().sorted().forEach(array::add);
        return array;
    }
}
