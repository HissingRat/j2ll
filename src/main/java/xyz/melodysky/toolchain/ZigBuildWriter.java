package xyz.melodysky.toolchain;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class ZigBuildWriter {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();

    public Path write(
            ZigBuildWorkspace workspace,
            String libraryName,
            NativeBuildPlan buildPlan,
            ZigInputSet inputs) throws IOException {
        return write(workspace, libraryName, buildPlan, inputs, true);
    }

    public Path write(
            ZigBuildWorkspace workspace,
            String libraryName,
            NativeBuildPlan buildPlan,
            ZigInputSet inputs,
            boolean strip) throws IOException {
        requireSafeLibraryName(libraryName);
        Files.createDirectories(workspace.buildDirectory());
        Files.writeString(
                workspace.buildZig(),
                buildZig(workspace, libraryName, buildPlan, inputs.sources(), strip),
                StandardCharsets.UTF_8);
        Files.writeString(
                workspace.manifest(),
                manifestJson(workspace, libraryName, buildPlan, inputs.sources()),
                StandardCharsets.UTF_8);
        return workspace.buildZig();
    }

    public String buildZig(
            ZigBuildWorkspace workspace,
            String libraryName,
            NativeBuildPlan buildPlan,
            ZigSourceSet sources) {
        return buildZig(workspace, libraryName, buildPlan, sources, true);
    }

    public String buildZig(
            ZigBuildWorkspace workspace,
            String libraryName,
            NativeBuildPlan buildPlan,
            ZigSourceSet sources,
            boolean strip) {
        requireSafeLibraryName(libraryName);
        StringBuilder builder = new StringBuilder();
        builder.append("""
                const std = @import("std");

                pub fn build(b: *std.Build) void {
                    const optimize = .ReleaseSafe;
                """);
        if (!buildPlan.units().isEmpty()) {
            builder.append("    const progress_markers = b.addWriteFiles();\n");
        }
        ZigTargetBuildEmitter targetEmitter =
                new ZigTargetBuildEmitter(workspace, libraryName, sources, strip);
        for (ZigBuildProgressPlan.TargetPlan target :
                ZigBuildProgressPlan.forSources(buildPlan, sources).targets()) {
            builder.append(targetEmitter.emit(target));
        }
        builder.append("}\n");
        return builder.toString();
    }

    private String manifestJson(
            ZigBuildWorkspace workspace,
            String libraryName,
            NativeBuildPlan buildPlan,
            ZigSourceSet sources) {
        JsonObject root = new JsonObject();
        root.addProperty("schemaVersion", 1);
        root.addProperty("libraryName", libraryName);
        root.addProperty("buildZig", workspace.buildZig().toString());
        root.add("cSources", pathArray(workspace.buildDirectory(), sources.cSources()));
        root.add("llvmSources", pathArray(workspace.buildDirectory(), sources.llvmSources()));
        root.add("objectInputs", pathArray(workspace.buildDirectory(), sources.objectInputs()));
        root.add("includeDirectories", pathArray(sources.includeDirectories()));
        root.add("selectedTargets", targetNameArray(buildPlan.targetPreflights()));
        root.add("requiredTargets", targetNameArray(buildPlan.targetPreflights()));
        root.add("buildableTargets", targetNameArray(buildPlan.buildableTargetPreflights()));
        root.add("skippedTargets", targetPreflightArray(workspace, buildPlan.skippedTargetPreflights()));
        root.add("failedTargets", targetPreflightArray(workspace, buildPlan.failedTargetPreflights()));
        JsonArray targets = new JsonArray();
        for (NativeBuildTargetPreflight preflight : buildPlan.targetPreflights()) {
            JsonObject target = new JsonObject();
            target.addProperty("target", preflight.target().directoryName());
            target.addProperty("zigTarget", preflight.zigTarget());
            target.addProperty("output", workspace.workspaceRoot().toAbsolutePath().normalize()
                    .relativize(preflight.outputPath().toAbsolutePath().normalize())
                    .toString()
                    .replace('\\', '/'));
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
            targets.add(target);
        }
        root.add("targets", targets);
        return GSON.toJson(root) + "\n";
    }

    private JsonArray targetNameArray(List<NativeBuildTargetPreflight> targets) {
        JsonArray array = new JsonArray();
        for (NativeBuildTargetPreflight target : targets) {
            array.add(target.target().directoryName());
        }
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
                    .toString()
                    .replace('\\', '/'));
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
        for (Path path : paths) {
            array.add(path.toString().replace('\\', '/'));
        }
        return array;
    }

    private JsonArray pathArray(Path root, List<Path> paths) {
        JsonArray array = new JsonArray();
        for (Path path : paths) {
            Path normalizedRoot = root.toAbsolutePath().normalize();
            Path normalizedPath = path.toAbsolutePath().normalize();
            String value = normalizedPath.startsWith(normalizedRoot)
                    ? normalizedRoot.relativize(normalizedPath).toString()
                    : normalizedPath.toString();
            array.add(value.replace('\\', '/'));
        }
        return array;
    }

    private String relative(Path root, Path child) {
        return root.toAbsolutePath().normalize()
                .relativize(child.toAbsolutePath().normalize())
                .toString()
                .replace('\\', '/');
    }

    private void requireSafeLibraryName(String libraryName) {
        if (!NativeLibraryName.isSafe(libraryName)) {
            throw new IllegalArgumentException("unsafe native library name: " + libraryName);
        }
    }
}
