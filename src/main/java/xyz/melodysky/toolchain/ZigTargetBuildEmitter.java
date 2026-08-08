package xyz.melodysky.toolchain;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class ZigTargetBuildEmitter {
    private final ZigBuildWorkspace workspace;
    private final String libraryName;
    private final ZigSourceSet sources;
    private final boolean strip;
    private final NativeUnwindRetentionPolicy unwindRetentionPolicy;

    ZigTargetBuildEmitter(
            ZigBuildWorkspace workspace,
            String libraryName,
            ZigSourceSet sources,
            boolean strip,
            NativeUnwindRetentionPolicy unwindRetentionPolicy) {
        this.workspace = workspace;
        this.libraryName = libraryName;
        this.sources = sources;
        this.strip = strip;
        this.unwindRetentionPolicy = unwindRetentionPolicy;
    }

    String emit(ZigBuildProgressPlan.TargetPlan targetPlan) {
        StringBuilder builder = new StringBuilder();
        TargetTriple target = targetPlan.target();
        ZigTargetBuildPolicy targetPolicy =
                ZigTargetBuildPolicy.resolve(target, unwindRetentionPolicy);
        String targetSymbol = target.safeSymbol();
        builder.append("\n")
                .append("    const target_").append(targetSymbol).append(" = b.resolveTargetQuery(")
                .append(target.zigTargetQuery()).append(");\n");

        ArrayList<String> compileLibrarySymbols = new ArrayList<>();
        ArrayList<String> compileMarkerSymbols = new ArrayList<>();
        for (ZigBuildProgressPlan.CompileUnit compileUnit : targetPlan.compileUnits()) {
            String unitSymbol = targetSymbol + "_" + compileUnit.id().replace('-', '_');
            String compileLibrarySymbol =
                    appendCompileUnit(builder, target, targetPolicy, compileUnit, unitSymbol);
            compileLibrarySymbols.add(compileLibrarySymbol);
            compileMarkerSymbols.add(appendCompileMarker(
                    builder,
                    target,
                    compileUnit,
                    unitSymbol,
                    compileLibrarySymbol));
        }

        String linkingMarker = appendLinkingMarker(
                builder,
                targetPlan,
                targetSymbol,
                compileMarkerSymbols);
        appendLibrary(
                builder,
                targetPlan,
                targetSymbol,
                compileLibrarySymbols,
                linkingMarker);
        return builder.toString();
    }

    private String appendCompileUnit(
            StringBuilder builder,
            TargetTriple target,
            ZigTargetBuildPolicy targetPolicy,
            ZigBuildProgressPlan.CompileUnit compileUnit,
            String unitSymbol) {
        builder.append("    const module_").append(unitSymbol).append(" = b.createModule(.{\n");
        appendModuleOptions(
                builder,
                target,
                compileUnit.kind()
                        == ZigBuildProgressPlan.CompileInputKind.C
                        ? "c_optimize"
                        : "optimize");
        builder.append("    });\n");
        for (ZigBuildProgressPlan.CompileInput compileInput : compileUnit.inputs()) {
            appendCompileInput(builder, targetPolicy, compileInput, unitSymbol);
        }
        String compileLibrarySymbol = "compile_" + unitSymbol;
        builder.append("    const ").append(compileLibrarySymbol).append(" = b.addLibrary(.{\n")
                .append("        .linkage = .static,\n")
                .append("        .name = ")
                .append(quote("j2ll_compile_" + unitSymbol))
                .append(",\n")
                .append("        .root_module = module_").append(unitSymbol).append(",\n")
                .append("    });\n");
        return compileLibrarySymbol;
    }

    private void appendCompileInput(
            StringBuilder builder,
            ZigTargetBuildPolicy targetPolicy,
            ZigBuildProgressPlan.CompileInput compileInput,
            String unitSymbol) {
        if (compileInput.kind() == ZigBuildProgressPlan.CompileInputKind.C) {
            builder.append("    module_").append(unitSymbol).append(".addCSourceFile(.{\n")
                    .append("        .file = b.path(")
                    .append(quote(relative(workspace.buildDirectory(), compileInput.source())))
                    .append("),\n")
                    .append("        .flags = &.{ ");
            appendCFlags(builder, targetPolicy);
            builder.append(" },\n")
                    .append("        .language = .c,\n")
                    .append("    });\n");
        } else {
            Path selectedSource = sources.llvmUnwindSources()
                    .select(compileInput.source(), targetPolicy.unwindRetention());
            builder.append("    module_").append(unitSymbol).append(".addObjectFile(b.path(")
                    .append(quote(relative(workspace.buildDirectory(), selectedSource)))
                    .append("));\n");
        }
    }

    private String appendCompileMarker(
            StringBuilder builder,
            TargetTriple target,
            ZigBuildProgressPlan.CompileUnit compileUnit,
            String unitSymbol,
            String compileLibrarySymbol) {
        String installSymbol = "install_compile_marker_" + unitSymbol;
        builder.append("    const compile_marker_").append(unitSymbol)
                .append(" = progress_markers.add(")
                .append(quote(target.directoryName() + "." + compileUnit.id() + ".done"))
                .append(", ")
                .append(quote(ZigTargetCompletionMonitor.compileMarkerContent(target, compileUnit)))
                .append(");\n")
                .append("    const ").append(installSymbol)
                .append(" = b.addInstallFileWithDir(compile_marker_").append(unitSymbol)
                .append(", .prefix, ")
                .append(quote(relative(
                        workspace.workspaceRoot(),
                        ZigTargetCompletionMonitor.compileMarkerPath(workspace, target, compileUnit))))
                .append(");\n")
                .append("    ").append(installSymbol).append(".step.dependOn(&")
                .append(compileLibrarySymbol).append(".step);\n");
        return installSymbol;
    }

    private String appendLinkingMarker(
            StringBuilder builder,
            ZigBuildProgressPlan.TargetPlan targetPlan,
            String targetSymbol,
            List<String> compileMarkerSymbols) {
        TargetTriple target = targetPlan.target();
        String installSymbol = "install_linking_marker_" + targetSymbol;
        builder.append("    const linking_marker_").append(targetSymbol)
                .append(" = progress_markers.add(")
                .append(quote(target.directoryName() + ".linking"))
                .append(", ")
                .append(quote(ZigTargetCompletionMonitor.linkingMarkerContent(
                        target,
                        targetPlan.compileUnits().size())))
                .append(");\n")
                .append("    const ").append(installSymbol)
                .append(" = b.addInstallFileWithDir(linking_marker_").append(targetSymbol)
                .append(", .prefix, ")
                .append(quote(relative(
                        workspace.workspaceRoot(),
                        ZigTargetCompletionMonitor.linkingMarkerPath(workspace, target))))
                .append(");\n");
        for (String compileMarkerSymbol : compileMarkerSymbols) {
            builder.append("    ").append(installSymbol).append(".step.dependOn(&")
                    .append(compileMarkerSymbol).append(".step);\n");
        }
        return installSymbol;
    }

    private void appendLibrary(
            StringBuilder builder,
            ZigBuildProgressPlan.TargetPlan targetPlan,
            String targetSymbol,
            List<String> compileLibrarySymbols,
            String linkingMarkerSymbol) {
        NativeBuildUnit unit = targetPlan.buildUnit();
        TargetTriple target = targetPlan.target();
        builder.append("    const module_").append(targetSymbol).append(" = b.createModule(.{\n");
        appendModuleOptions(builder, target, "optimize");
        builder.append("    });\n");
        for (String compileLibrarySymbol : compileLibrarySymbols) {
            builder.append("    module_").append(targetSymbol).append(".linkLibrary(")
                    .append(compileLibrarySymbol).append(");\n");
        }
        for (Path object : sources.objectInputs()) {
            builder.append("    module_").append(targetSymbol)
                    .append(".addObjectFile(.{ .cwd_relative = ")
                    .append(quote(object.toAbsolutePath().normalize().toString()))
                    .append(" });\n");
        }
        builder.append("    const lib_").append(targetSymbol).append(" = b.addLibrary(.{\n")
                .append("        .linkage = .dynamic,\n")
                .append("        .name = ").append(quote(libraryName)).append(",\n")
                .append("        .root_module = module_").append(targetSymbol).append(",\n")
                .append("    });\n")
                .append("    lib_").append(targetSymbol)
                .append(".link_gc_sections = true;\n");
        if (!sources.libcRequirement().required()) {
            builder.append("    lib_").append(targetSymbol)
                    .append(".linker_allow_shlib_undefined = false;\n");
        }
        if (target.isWindows() && !sources.libcRequirement().required()) {
            builder.append("    lib_").append(targetSymbol)
                    .append(".entry = .{ .symbol_name = ")
                    .append(quote(HostWindowsDllEntryRuntimeSource.symbol(libraryName)))
                    .append(" };\n");
        }
        appendForcedEntryPoints(builder, target, targetSymbol);
        builder
                .append("    lib_").append(targetSymbol).append(".step.dependOn(&")
                .append(linkingMarkerSymbol).append(".step);\n");
        if (target.zigOsTag().equals("macos")) {
            builder.append("    lib_").append(targetSymbol).append(".discard_local_symbols = true;\n");
            if (!sources.libcRequirement().required()) {
                builder.append("    lib_").append(targetSymbol)
                        .append(".dead_strip_dylibs = true;\n");
            }
        }
        builder.append("    const install_").append(targetSymbol)
                .append(" = b.addInstallArtifact(lib_").append(targetSymbol).append(", .{\n")
                .append("        .dest_dir = .{ .override = .prefix },\n");
        if (target.isWindows()) {
            builder.append("        .implib_dir = .disabled,\n");
        }
        builder.append("        .dest_sub_path = ")
                .append(quote(relative(workspace.workspaceRoot(), unit.outputPath())))
                .append(",\n")
                .append("    });\n")
                .append("    const marker_").append(targetSymbol).append(" = progress_markers.add(")
                .append(quote(target.directoryName() + ".done")).append(", ")
                .append(quote(ZigTargetCompletionMonitor.markerContent(target))).append(");\n")
                .append("    const install_marker_").append(targetSymbol)
                .append(" = b.addInstallFileWithDir(marker_").append(targetSymbol)
                .append(", .prefix, ")
                .append(quote(relative(
                        workspace.workspaceRoot(),
                        ZigTargetCompletionMonitor.markerPath(workspace, target))))
                .append(");\n")
                .append("    install_marker_").append(targetSymbol)
                .append(".step.dependOn(&install_").append(targetSymbol).append(".step);\n")
                .append("    b.getInstallStep().dependOn(&install_marker_")
                .append(targetSymbol).append(".step);\n");
    }

    private void appendForcedEntryPoints(
            StringBuilder builder,
            TargetTriple target,
            String targetSymbol) {
        String prefix = target.zigOsTag().equals("macos") ? "_" : "";
        builder.append("    lib_").append(targetSymbol)
                .append(".forceUndefinedSymbol(")
                .append(quote(prefix + "JNI_OnLoad"))
                .append(");\n");
    }

    private void appendModuleOptions(
            StringBuilder builder,
            TargetTriple target,
            String optimizeMode) {
        builder.append("        .target = target_").append(target.safeSymbol()).append(",\n")
                .append("        .optimize = ")
                .append(optimizeMode)
                .append(",\n")
                .append("        .strip = ").append(strip).append(",\n")
                .append("        .pic = true,\n")
                .append("        .link_libc = ")
                .append(sources.libcRequirement().required())
                .append(",\n");
    }

    private void appendCFlags(
            StringBuilder builder,
            ZigTargetBuildPolicy targetPolicy) {
        ArrayList<String> flags = new ArrayList<>(List.of(
                "-g0",
                "-fvisibility=hidden",
                "-ffunction-sections",
                "-fdata-sections",
                "-ffile-compilation-dir=.",
                "-fdebug-compilation-dir=."));
        if (!sources.libcRequirement().required()) {
            flags.add("-ffreestanding");
            flags.add("-fno-builtin");
        }
        flags.addAll(targetPolicy.generatedCCompilerFlags());
        for (Path include : sources.includeDirectories()) {
            flags.add("-I" + include.toAbsolutePath().normalize());
        }
        builder.append(String.join(", ", flags.stream().map(this::quote).toList()));
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
                        quoted.append(String.format(Locale.ROOT, "\\x%02x", (int) ch));
                    } else {
                        quoted.append(ch);
                    }
                }
            }
        }
        return quoted.append('"').toString();
    }
}
