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
        for (NativeBuildUnit unit : buildPlan.units()) {
            appendTarget(builder, workspace, libraryName, unit, sources, strip);
        }
        builder.append("}\n");
        return builder.toString();
    }

    private void appendTarget(
            StringBuilder builder,
            ZigBuildWorkspace workspace,
            String libraryName,
            NativeBuildUnit unit,
            ZigSourceSet sources,
            boolean strip) {
        TargetTriple target = unit.target();
        String symbol = target.safeSymbol();
        builder.append("\n")
                .append("    const target_").append(symbol).append(" = b.resolveTargetQuery(")
                .append(target.zigTargetQuery()).append(");\n")
                .append("    const module_").append(symbol).append(" = b.createModule(.{\n")
                .append("        .target = target_").append(symbol).append(",\n")
                .append("        .optimize = optimize,\n")
                .append("        .strip = ").append(strip).append(",\n")
                .append("        .link_libc = true,\n")
                .append("    });\n");
        if (!sources.cSources().isEmpty()) {
            builder.append("    module_").append(symbol).append(".addCSourceFiles(.{\n")
                    .append("        .root = b.path(\".\"),\n")
                    .append("        .files = &.{ ");
            builder.append(String.join(", ", sources.cSources().stream()
                    .map(path -> quote(relative(workspace.buildDirectory(), path)))
                    .toList()));
            builder.append(" },\n")
                    .append("        .language = .c,\n")
                    .append("        .flags = &.{ \"-g0\", \"-fvisibility=hidden\", \"-ffile-compilation-dir=.\", \"-fdebug-compilation-dir=.\"");
            for (Path include : sources.includeDirectories()) {
                builder.append(", ")
                        .append(quote("-I" + include.toAbsolutePath().normalize()));
            }
            builder.append(" },\n")
                    .append("    });\n");
        }
        for (Path llvm : sources.llvmSources()) {
            builder.append("    module_").append(symbol).append(".addObjectFile(b.path(")
                    .append(quote(relative(workspace.buildDirectory(), llvm)))
                    .append("));\n");
        }
        for (Path object : sources.objectInputs()) {
            builder.append("    module_").append(symbol).append(".addObjectFile(.{ .cwd_relative = ")
                    .append(quote(object.toAbsolutePath().normalize().toString())).append(" });\n");
        }
        builder.append("    const lib_").append(symbol).append(" = b.addLibrary(.{\n")
                .append("        .linkage = .dynamic,\n")
                .append("        .name = ").append(quote(libraryName)).append(",\n")
                .append("        .root_module = module_").append(symbol).append(",\n")
                .append("    });\n");
        if (target.zigOsTag().equals("macos")) {
            builder.append("    lib_").append(symbol).append(".discard_local_symbols = true;\n");
        }
        builder.append("    const install_").append(symbol).append(" = b.addInstallArtifact(lib_").append(symbol).append(", .{\n")
                .append("        .dest_dir = .{ .override = .prefix },\n");
        if (target.isWindows()) {
            builder.append("        .implib_dir = .disabled,\n");
        }
        builder.append("        .dest_sub_path = ")
                .append(quote(relative(workspace.workspaceRoot(), unit.outputPath())))
                .append(",\n")
                .append("    });\n")
                .append("    const marker_").append(symbol).append(" = progress_markers.add(")
                .append(quote(target.directoryName() + ".done")).append(", ")
                .append(quote(ZigTargetCompletionMonitor.markerContent(target))).append(");\n")
                .append("    const install_marker_").append(symbol)
                .append(" = b.addInstallFileWithDir(marker_").append(symbol).append(", .prefix, ")
                .append(quote(relative(
                        workspace.workspaceRoot(),
                        ZigTargetCompletionMonitor.markerPath(workspace, target))))
                .append(");\n")
                .append("    install_marker_").append(symbol).append(".step.dependOn(&install_")
                .append(symbol).append(".step);\n")
                .append("    b.getInstallStep().dependOn(&install_marker_").append(symbol).append(".step);\n");
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

    private String quote(String value) {
        StringBuilder quoted = new StringBuilder("\"");
        for (int index = 0; index < value.length(); index++) {
            char ch = value.charAt(index);
            switch (ch) {
                case '\\' -> quoted.append("\\\\");
                case '"' -> quoted.append("\\\"");
                case '\n' -> quoted.append("\\n");
                case '\r' -> quoted.append("\\r");
                case '\t' -> quoted.append("\\t");
                default -> {
                    if (ch < 0x20 || ch == 0x7f) {
                        quoted.append(String.format(java.util.Locale.ROOT, "\\x%02x", (int) ch));
                    } else {
                        quoted.append(ch);
                    }
                }
            }
        }
        return quoted.append('"').toString();
    }

    private void requireSafeLibraryName(String libraryName) {
        if (!NativeLibraryName.isSafe(libraryName)) {
            throw new IllegalArgumentException("unsafe native library name: " + libraryName);
        }
    }
}
