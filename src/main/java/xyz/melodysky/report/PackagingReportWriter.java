package xyz.melodysky.report;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import xyz.melodysky.config.SignaturePolicy;
import xyz.melodysky.packaging.JarPreservationReport;
import xyz.melodysky.packaging.MethodRewriteDecision;
import xyz.melodysky.packaging.MethodRewriteStrategy;
import xyz.melodysky.packaging.MethodTableHidingPlan;
import xyz.melodysky.packaging.NativeRegistrationEntry;
import xyz.melodysky.packaging.SignatureActionReport;
import xyz.melodysky.toolchain.NativeBuildPlan;
import xyz.melodysky.toolchain.NativeBuildTargetPreflight;
import xyz.melodysky.toolchain.ManagedZigBootstrapEvent;
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
            ZigNativeBuildResult zigBuildResult) {
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
                JarPreservationReport.empty(),
                SignatureActionReport.none(false));
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
            NativeBuildPlan nativeBuildPlan) {
        return packagingJson(
                outputJar,
                signaturePolicy,
                generatedLoaders,
                rewrittenMethods,
                embeddedLibraries,
                registeredNativeMethods,
                exportedSymbols,
                zigBuildResult,
                nativeBuildPlan,
                JarPreservationReport.empty(),
                SignatureActionReport.none(false));
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
            JarPreservationReport preservationReport,
            SignatureActionReport signatureActionReport) {
        return packagingJson(
                outputJar,
                signaturePolicy,
                generatedLoaders,
                rewrittenMethods,
                embeddedLibraries,
                registeredNativeMethods,
                MethodTableHidingPlan.disabled(),
                exportedSymbols,
                zigBuildResult,
                nativeBuildPlan,
                preservationReport,
                signatureActionReport);
    }

    public String packagingJson(
            Path outputJar,
            SignaturePolicy signaturePolicy,
            List<String> generatedLoaders,
            List<MethodRewriteDecision> rewrittenMethods,
            List<EmbeddedLibraryReport> embeddedLibraries,
            List<NativeRegistrationEntry> registeredNativeMethods,
            MethodTableHidingPlan methodTableHidingPlan,
            List<String> exportedSymbols,
            ZigNativeBuildResult zigBuildResult,
            NativeBuildPlan nativeBuildPlan,
            JarPreservationReport preservationReport,
            SignatureActionReport signatureActionReport) {
        JsonObject root = new JsonObject();
        root.addProperty("schemaVersion", 1);
        root.addProperty("reportVersion", 1);
        root.addProperty("outputJar", outputJar.toString().replace('\\', '/'));
        root.addProperty("manifestPolicy", "preserved");
        root.addProperty("signaturePolicy", signaturePolicy.wireName());
        root.add("preservationSummary", preservationSummary(preservationReport));
        root.add("signatureAction", signatureAction(signatureActionReport));
        root.add("generatedLoaders", stringArray(generatedLoaders));
        root.add("rewrittenClasses", rewrittenClasses(rewrittenMethods));
        root.add("embeddedLibraries", embeddedLibraries(embeddedLibraries));
        root.add("zigToolchain", zigToolchain(zigBuildResult, nativeBuildPlan, embeddedLibraries));
        root.add("registeredNativeMethods", registeredNativeMethods(registeredNativeMethods));
        root.add("registrationGroups", registrationGroups(registeredNativeMethods));
        root.add("methodTableHiding", methodTableHiding(methodTableHidingPlan));
        root.add("exportedSymbols", stringArray(exportedSymbols));
        return GSON.toJson(root) + "\n";
    }

    private JsonObject preservationSummary(JarPreservationReport report) {
        JsonObject object = new JsonObject();
        object.addProperty("manifestPreserved", report.manifestPreserved());
        object.addProperty("serviceEntriesPreserved", report.serviceEntriesPreserved());
        object.addProperty("moduleInfoPreserved", report.moduleInfoPreserved());
        object.addProperty("multiRelease", report.multiRelease());
        object.addProperty("versionedEntriesPreserved", report.versionedEntriesPreserved());
        object.addProperty("versionedClassPolicy", report.versionedClassPolicy());
        return object;
    }

    private JsonObject signatureAction(SignatureActionReport report) {
        JsonObject object = new JsonObject();
        object.addProperty("action", report.action());
        object.addProperty("signedInput", report.signedInput());
        object.add("removedEntries", stringArray(report.removedEntries()));
        object.addProperty("reasonCode", report.reasonCode());
        object.addProperty("reason", report.reason());
        return object;
    }

    private JsonObject zigToolchain(
            ZigNativeBuildResult result,
            NativeBuildPlan nativeBuildPlan,
            List<EmbeddedLibraryReport> embeddedLibraries) {
        JsonObject object = new JsonObject();
        if (result == null) {
            object.addProperty("managed", true);
            object.add("version", com.google.gson.JsonNull.INSTANCE);
            object.add("executable", com.google.gson.JsonNull.INSTANCE);
            object.add("buildZig", com.google.gson.JsonNull.INSTANCE);
            object.add("verificationPolicy", com.google.gson.JsonNull.INSTANCE);
            object.add("selectedTargets", targetNameArray(nativeBuildPlan.targetPreflights()));
            object.add("requiredTargets", targetNameArray(nativeBuildPlan.targetPreflights()));
            object.add("buildableTargets", targetNameArray(nativeBuildPlan.buildableTargetPreflights()));
            object.add("skippedTargets", targetPreflightArray(nativeBuildPlan.skippedTargetPreflights()));
            object.add("failedTargets", targetPreflightArray(nativeBuildPlan.failedTargetPreflights()));
            object.add("targetArtifacts", targetArtifactArray(nativeBuildPlan, null, embeddedLibraries));
            object.add("bootstrapEvents", new JsonArray());
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
        object.add("requiredTargets", targetNameArray(nativeBuildPlan.targetPreflights()));
        object.add("buildableTargets", targetNameArray(nativeBuildPlan.buildableTargetPreflights()));
        object.add("skippedTargets", targetPreflightArray(nativeBuildPlan.skippedTargetPreflights()));
        object.add("failedTargets", targetPreflightArray(nativeBuildPlan.failedTargetPreflights()));
        object.add("targetArtifacts", targetArtifactArray(nativeBuildPlan, result, embeddedLibraries));
        object.add("bootstrapEvents", bootstrapEvents(result.zig().bootstrapEvents()));
        return object;
    }

    private JsonArray bootstrapEvents(List<ManagedZigBootstrapEvent> events) {
        JsonArray array = new JsonArray();
        events.stream()
                .sorted(Comparator.comparing(ManagedZigBootstrapEvent::code)
                        .thenComparing(ManagedZigBootstrapEvent::message))
                .forEach(event -> {
                    JsonObject object = new JsonObject();
                    object.addProperty("code", event.code());
                    object.addProperty("message", event.message());
                    nullableString(object, "archiveName", event.archiveName());
                    nullableString(object, "archiveSha256", event.archiveSha256());
                    nullableString(object, "checksumStatus", event.checksumStatus());
                    nullableString(object, "signatureStatus", event.signatureStatus());
                    nullableString(object, "source", event.source());
                    array.add(object);
                });
        return array;
    }

    private void nullableString(JsonObject object, String field, String value) {
        if (value == null) {
            object.add(field, com.google.gson.JsonNull.INSTANCE);
        } else {
            object.addProperty(field, value);
        }
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
                    object.addProperty("required", preflight.required());
                    object.addProperty("buildable", preflight.buildable());
                    object.addProperty("reasonCode", preflight.reasonCode());
                    object.addProperty("reason", preflight.reason());
                    object.addProperty("requiredCapability", preflight.requiredCapability());
                    object.addProperty("platformSdkRequirement", preflight.platformSdkRequirement());
                    object.addProperty("failureKind", preflight.failureKind());
                    object.addProperty("buildLogTail", preflight.buildLogTail());
                    array.add(object);
                });
        return array;
    }

    private JsonArray targetArtifactArray(
            NativeBuildPlan nativeBuildPlan,
            ZigNativeBuildResult result,
            List<EmbeddedLibraryReport> embeddedLibraries) {
        JsonArray array = new JsonArray();
        Map<String, String> embeddedJarPaths = embeddedLibraries.stream()
                .collect(java.util.stream.Collectors.toMap(
                        EmbeddedLibraryReport::target,
                        EmbeddedLibraryReport::jarPath,
                        (left, right) -> left,
                        java.util.TreeMap::new));
        nativeBuildPlan.targetPreflights().stream()
                .sorted(Comparator.comparing(preflight -> preflight.target().directoryName()))
                .forEach(preflight -> {
                    java.util.Optional<xyz.melodysky.toolchain.NativeLibraryArtifact> artifact = result == null
                            ? java.util.Optional.empty()
                            : result.artifactFor(preflight.target());
                    JsonObject object = new JsonObject();
                    object.addProperty("target", preflight.target().directoryName());
                    object.addProperty("required", preflight.required());
                    object.addProperty("currentHost", preflight.currentHost());
                    object.addProperty("buildable", preflight.buildable());
                    object.addProperty("osClassifier", preflight.target().osClassifier());
                    object.addProperty("archClassifier", preflight.target().archClassifier());
                    object.addProperty("libraryExtension", preflight.target().libraryExtension());
                    object.addProperty("libraryName", preflight.libraryName());
                    object.addProperty("zigTarget", preflight.zigTarget());
                    object.addProperty("expectedArtifactPath", preflight.outputPath().toString().replace('\\', '/'));
                    object.addProperty("expectedArtifactName", preflight.outputPath().getFileName().toString());
                    object.addProperty("expectedResourcePath", embeddedJarPaths.getOrDefault(
                            preflight.target().directoryName(),
                            "native/" + preflight.outputPath().getFileName()));
                    object.addProperty("loaderExtractionPathPolicy", "contentAddressedTempCacheBySha256");
                    object.addProperty("symbolVisibilityPolicy", "allowlistOnlyJniOnLoadAndBootstrap");
                    object.addProperty("windowsPdbPolicy", preflight.target().isWindows()
                            ? "excludePdbFromJarAndReports"
                            : "notApplicable");
                    object.add("actualArtifactPath", artifact
                            .<com.google.gson.JsonElement>map(value -> new com.google.gson.JsonPrimitive(value.libraryPath().toString().replace('\\', '/')))
                            .orElse(com.google.gson.JsonNull.INSTANCE));
                    object.add("actualJarPath", artifact
                            .<com.google.gson.JsonElement>map(value -> new com.google.gson.JsonPrimitive(value.jarPath()))
                            .orElse(com.google.gson.JsonNull.INSTANCE));
                    object.add("actualSha256", artifact
                            .<com.google.gson.JsonElement>map(value -> new com.google.gson.JsonPrimitive(value.sha256()))
                            .orElse(com.google.gson.JsonNull.INSTANCE));
                    object.add("exportedSymbols", artifact
                            .map(value -> stringArray(value.exportedSymbols()))
                            .orElseGet(JsonArray::new));
                    object.add("unwindSections", artifact
                            .flatMap(value -> value.unwindSectionInspection())
                            .<com.google.gson.JsonElement>map(inspection -> {
                                JsonObject sections = new JsonObject();
                                inspection.sectionSizes().forEach(
                                        sections::addProperty);
                                return sections;
                            })
                            .orElse(com.google.gson.JsonNull.INSTANCE));
                    object.add("unwindSectionBytes", artifact
                            .flatMap(value -> value.unwindSectionInspection())
                            .<com.google.gson.JsonElement>map(inspection ->
                                    new com.google.gson.JsonPrimitive(
                                            inspection.totalSize()))
                            .orElse(com.google.gson.JsonNull.INSTANCE));
                    object.addProperty("status", artifact.isPresent()
                            ? "built"
                            : preflight.status());
                    object.addProperty("reasonCode", preflight.reasonCode());
                    object.addProperty("reason", preflight.reason());
                    object.addProperty("requiredCapability", preflight.requiredCapability());
                    object.addProperty("platformSdkRequirement", preflight.platformSdkRequirement());
                    object.addProperty("failureKind", preflight.failureKind());
                    object.addProperty("buildLogTail", preflight.buildLogTail());
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
                boolean internalOnly = decision.strategy()
                        == MethodRewriteStrategy
                                .INTERNAL_NATIVE_ONLY;
                method.addProperty(
                        "javaMethodPresent",
                        !internalOnly);
                method.addProperty(
                        "registrationPresent",
                        !internalOnly);
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

    private JsonObject methodTableHiding(MethodTableHidingPlan plan) {
        Objects.requireNonNull(plan, "methodTableHidingPlan");
        JsonObject object = new JsonObject();
        object.addProperty("enabled", plan.enabled());
        object.addProperty("status", plan.changed() ? "RAN" : "SKIPPED");
        nullableString(object, "planId", plan.enabled() ? plan.planId() : null);
        object.addProperty(
                "physicalStrategy",
                "ownerLocalTransientStraightLine");
        object.addProperty("runtimeTokenTableEmitted", false);
        object.addProperty("runtimeFunctionTableEmitted", false);
        object.addProperty("temporaryJniTableZeroized", true);
        object.addProperty("bindingTokensAreReportEvidenceOnly", true);
        object.addProperty("ownerCount", plan.owners().size());
        object.addProperty("bindingCount", plan.owners().stream()
                .mapToInt(owner -> owner.registrationOrder().size())
                .sum());

        JsonArray owners = new JsonArray();
        plan.owners().stream()
                .sorted(Comparator.comparing(owner -> sha256(owner.registrationOwner())))
                .forEach(owner -> {
                    JsonObject ownerObject = new JsonObject();
                    ownerObject.addProperty("ownerHash", sha256(owner.registrationOwner()));
                    ownerObject.addProperty("bindingCount", owner.registrationOrder().size());
                    JsonArray bindingTokens = new JsonArray();
                    owner.registrationOrder().stream()
                            .map(entry -> entry.token())
                            .sorted(Long::compareUnsigned)
                            .map(this::unsignedHex)
                            .forEach(bindingTokens::add);
                    ownerObject.add("bindingTokens", bindingTokens);
                    owners.add(ownerObject);
                });
        object.add("owners", owners);
        return object;
    }

    private JsonArray stringArray(List<String> values) {
        JsonArray array = new JsonArray();
        values.stream().sorted().forEach(array::add);
        return array;
    }

    private String unsignedHex(long value) {
        String hex = Long.toUnsignedString(value, 16);
        return "0".repeat(16 - hex.length()) + hex;
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
