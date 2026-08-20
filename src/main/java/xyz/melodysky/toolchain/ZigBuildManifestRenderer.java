package xyz.melodysky.toolchain;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.nio.file.Path;
import java.util.List;

/** Renders the stable Zig workspace manifest separately from build-graph generation. */
final class ZigBuildManifestRenderer {
    private static final Gson GSON =
            new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();

    String render(
            ZigBuildWorkspace workspace,
            String libraryName,
            NativeBuildPlan buildPlan,
            ZigInputSet inputs,
            ZigCInputMachinePolicyPlan machinePolicies,
            ZigBuildProgressPlan.CompileInputInventory compileInputs,
            NativeUnwindRetentionPolicy unwindRetentionPolicy) {
        ZigSourceSet sources = inputs.sources();
        JsonObject root = new JsonObject();
        root.addProperty("schemaVersion", 1);
        root.addProperty("libraryName", libraryName);
        root.addProperty("buildZig", workspace.buildZig().toString());
        root.add("cSources", pathArray(workspace.buildDirectory(), sources.cSources()));
        root.add("llvmSources", pathArray(workspace.buildDirectory(), sources.llvmSources()));
        root.add("llvmUnwindSources", llvmUnwindSourceArray(
                workspace.buildDirectory(), sources.llvmUnwindSources()));
        root.add("objectInputs", pathArray(workspace.buildDirectory(), sources.objectInputs()));
        root.add("includeDirectories", pathArray(sources.includeDirectories()));
        root.addProperty("linkLibc", sources.libcRequirement().required());
        JsonArray libcReasons = new JsonArray();
        sources.libcRequirement().reasons().stream()
                .map(Enum::name)
                .sorted()
                .forEach(libcReasons::add);
        root.add("libcRequirementReasons", libcReasons);
        root.add("selectedTargets", targetNameArray(buildPlan.targetPreflights()));
        root.add("requiredTargets", targetNameArray(buildPlan.targetPreflights()));
        root.add("buildableTargets", targetNameArray(buildPlan.buildableTargetPreflights()));
        root.add("skippedTargets", targetPreflightArray(
                workspace, buildPlan.skippedTargetPreflights()));
        root.add("failedTargets", targetPreflightArray(
                workspace, buildPlan.failedTargetPreflights()));
        JsonArray targets = new JsonArray();
        for (NativeBuildTargetPreflight preflight : buildPlan.targetPreflights()) {
            targets.add(target(
                    workspace,
                    sources,
                    machinePolicies,
                    compileInputs,
                    unwindRetentionPolicy,
                    preflight));
        }
        root.add("targets", targets);
        return GSON.toJson(root) + "\n";
    }

    private JsonObject target(
            ZigBuildWorkspace workspace,
            ZigSourceSet sources,
            ZigCInputMachinePolicyPlan machinePolicies,
            ZigBuildProgressPlan.CompileInputInventory compileInputs,
            NativeUnwindRetentionPolicy unwindRetentionPolicy,
            NativeBuildTargetPreflight preflight) {
        NativeUnwindRetentionDecision unwind =
                unwindRetentionPolicy.resolve(preflight.target());
        NativeLlvmUnwindTargetSummary unwindSummary = sources.llvmUnwindSources()
                .summarize(unwind, sources.objectInputs().size());
        NativeMachineOutlinerPolicy machineOutliner =
                NativeMachineOutlinerPolicy.forTarget(preflight.target());
        NativeLibcTargetDecision libcDecision = NativeLibcTargetDecision.resolve(
                preflight.target(), sources.libcRequirement());
        JsonObject target = new JsonObject();
        target.addProperty("target", preflight.target().directoryName());
        target.addProperty("zigTarget", preflight.zigTarget());
        target.addProperty("output", workspace.workspaceRoot().toAbsolutePath().normalize()
                .relativize(preflight.outputPath().toAbsolutePath().normalize())
                .toString().replace('\\', '/'));
        target.addProperty("status", preflight.status());
        target.addProperty("currentHost", preflight.currentHost());
        target.addProperty("required", preflight.required());
        target.addProperty("buildable", preflight.buildable());
        target.addProperty("reasonCode", preflight.reasonCode());
        target.addProperty("reason", preflight.reason());
        target.addProperty("requiredCapability", preflight.requiredCapability());
        target.addProperty("platformSdkRequirement", preflight.platformSdkRequirement());
        target.addProperty("failureKind", preflight.failureKind());
        target.addProperty("buildLogTail", preflight.buildLogTail());
        target.addProperty("retainUnwindInfoRequested", unwind.requested());
        target.addProperty("retainUnwindInfoEffective", unwindSummary.effectiveRetention());
        target.addProperty("retainUnwindInfoReason", unwindSummary.reason().name());
        target.addProperty(
                "generatedCUnwindInfoRetained",
                unwindSummary.generatedCDecision().effective());
        target.addProperty("llvmUnwindModuleCount", unwindSummary.moduleCount());
        target.addProperty("llvmUnwindOmittedModuleCount", unwindSummary.omittedModuleCount());
        target.addProperty("llvmUnwindRetainedModuleCount", unwindSummary.retainedModuleCount());
        target.addProperty("unmodeledObjectInputCount", unwindSummary.unmodeledObjectInputCount());
        target.addProperty("finalUnwindOmissionExpected", unwindSummary.finalOmissionExpected());
        target.addProperty("machineOutlinerEnabled", machineOutliner.enabled());
        target.addProperty(
                "machineOutlinerMinimumBenefitThreshold",
                machineOutliner.minimumBenefitThreshold());
        target.addProperty("machineOutlinerReason", machineOutliner.reasonCode());
        target.addProperty("machineOutlinerPolicyScope", "PER_C_INPUT");
        target.add("cSourceMachinePolicies", new ZigCSourceMachinePolicyManifest().forTarget(
                workspace, compileInputs, machinePolicies, preflight.target()));
        target.addProperty(
                "generatedSourceRequiresLibc",
                libcDecision.generatedSourceRequiresLibc());
        target.addProperty("libcDependencyEffective", libcDecision.effectiveDependency());
        target.addProperty("libcDependencyReason", libcDecision.reason().name());
        return target;
    }

    private JsonArray llvmUnwindSourceArray(Path root, NativeLlvmSourcePlan plan) {
        JsonArray array = new JsonArray();
        for (NativeLlvmSource source : plan.sources()) {
            JsonObject object = new JsonObject();
            object.addProperty("owner", source.owner());
            object.addProperty("retainedPath", display(root, source.retainedPath()));
            source.omissionPath().ifPresent(path ->
                    object.addProperty("omissionPath", display(root, path)));
            object.addProperty("omissionSafe", source.omissionSafe());
            object.addProperty("proofReasonCode", source.proofReasonCode());
            array.add(object);
        }
        return array;
    }

    private JsonArray targetNameArray(List<NativeBuildTargetPreflight> targets) {
        JsonArray array = new JsonArray();
        targets.forEach(target -> array.add(target.target().directoryName()));
        return array;
    }

    private JsonArray targetPreflightArray(
            ZigBuildWorkspace workspace,
            List<NativeBuildTargetPreflight> preflights) {
        JsonArray array = new JsonArray();
        for (NativeBuildTargetPreflight preflight : preflights) {
            JsonObject object = new JsonObject();
            object.addProperty("target", preflight.target().directoryName());
            object.addProperty("zigTarget", preflight.zigTarget());
            object.addProperty("output", workspace.workspaceRoot().toAbsolutePath().normalize()
                    .relativize(preflight.outputPath().toAbsolutePath().normalize())
                    .toString().replace('\\', '/'));
            object.addProperty("reasonCode", preflight.reasonCode());
            object.addProperty("reason", preflight.reason());
            object.addProperty("requiredCapability", preflight.requiredCapability());
            object.addProperty("platformSdkRequirement", preflight.platformSdkRequirement());
            object.addProperty("required", preflight.required());
            object.addProperty("failureKind", preflight.failureKind());
            object.addProperty("buildLogTail", preflight.buildLogTail());
            array.add(object);
        }
        return array;
    }

    private JsonArray pathArray(List<Path> paths) {
        JsonArray array = new JsonArray();
        paths.forEach(path -> array.add(path.toString().replace('\\', '/')));
        return array;
    }

    private JsonArray pathArray(Path root, List<Path> paths) {
        JsonArray array = new JsonArray();
        paths.forEach(path -> array.add(display(root, path)));
        return array;
    }

    private String display(Path root, Path path) {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path normalizedPath = path.toAbsolutePath().normalize();
        Path value = normalizedPath.startsWith(normalizedRoot)
                ? normalizedRoot.relativize(normalizedPath)
                : normalizedPath;
        return value.toString().replace('\\', '/');
    }
}
