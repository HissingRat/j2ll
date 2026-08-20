package xyz.melodysky.toolchain;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.nio.file.Path;

/** Renders additive, per-input machine-policy evidence for the Zig workspace manifest. */
final class ZigCSourceMachinePolicyManifest {
    JsonArray forTarget(
            ZigBuildWorkspace workspace,
            ZigBuildProgressPlan.CompileInputInventory inventory,
            ZigCInputMachinePolicyPlan plan,
            TargetTriple target) {
        JsonArray result = new JsonArray();
        java.util.List<ZigBuildProgressPlan.CompileInput> cInputs =
                inventory.inputs().stream()
                        .filter(input -> input.kind()
                                == ZigBuildProgressPlan.CompileInputKind.C)
                        .toList();
        for (ZigBuildProgressPlan.CompileInput compileInput : cInputs) {
            Path source = compileInput.source();
            String inputId = compileInput.id();
            ZigCInputMachinePolicyPlan.Mode mode = plan.modeFor(source);
            NativeMachineOutlinerPolicy effective =
                    NativeMachineOutlinerPolicy.forSource(target, mode);
            JsonObject item = new JsonObject();
            item.addProperty("source", display(workspace.buildDirectory(), source));
            item.addProperty("compileInputId", inputId);
            item.addProperty("mode", mode.name());
            item.addProperty("machineOutlinerEnabled", effective.enabled());
            item.addProperty(
                    "machineOutlinerMinimumBenefitThreshold",
                    effective.minimumBenefitThreshold());
            item.addProperty("machineOutlinerReason", effective.reasonCode());
            JsonArray flags = new JsonArray();
            effective.cFlags().forEach(flags::add);
            item.add("machineOutlinerCFlags", flags);
            item.addProperty(
                    "optimizedAssemblyEvidence",
                        display(
                            workspace.buildDirectory(),
                            ZigOptimizedAssemblyEvidence.path(
                                    workspace,
                                    target,
                                    compileInput)));
            result.add(item);
        }
        return result;
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
