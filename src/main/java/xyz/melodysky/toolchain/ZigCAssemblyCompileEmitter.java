package xyz.melodysky.toolchain;

import static xyz.melodysky.toolchain.ZigBuildText.quote;
import static xyz.melodysky.toolchain.ZigBuildText.relative;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Emits the single C-to-assembly step whose output is both audited and linked. */
final class ZigCAssemblyCompileEmitter {
    private final ZigBuildWorkspace workspace;
    private final ZigSourceSet sources;
    private final ZigCInputMachinePolicyPlan machinePolicies;
    private final TargetTriple target;
    private final ZigTargetBuildPolicy targetPolicy;

    ZigCAssemblyCompileEmitter(
            ZigBuildWorkspace workspace,
            ZigSourceSet sources,
            ZigCInputMachinePolicyPlan machinePolicies,
            TargetTriple target,
            ZigTargetBuildPolicy targetPolicy) {
        this.workspace = workspace;
        this.sources = sources;
        this.machinePolicies = machinePolicies;
        this.target = target;
        this.targetPolicy = targetPolicy;
    }

    String emit(
            StringBuilder builder,
            ZigBuildProgressPlan.CompileInput input,
            String unitSymbol) {
        String inputSymbol = unitSymbol + "_" + input.id().replace('-', '_');
        String commandSymbol = "compile_assembly_" + inputSymbol;
        String assemblySymbol = "optimized_assembly_" + inputSymbol;
        String installSymbol = "install_" + assemblySymbol;
        builder.append("    const ").append(commandSymbol)
                .append(" = b.addSystemCommand(&.{ b.graph.zig_exe, \"cc\" });\n")
                .append("    ").append(commandSymbol).append(".addArgs(&.{ ")
                .append(quote("-target")).append(", ")
                .append(quote(target.zigTarget())).append(", ")
                .append(quotedFlags(input.source()))
                .append(" });\n");
        for (Path include : sources.includeDirectories()) {
            builder.append("    ").append(commandSymbol)
                    .append(".addPrefixedDirectoryArg(\"-I\", b.path(")
                    .append(quote(relative(workspace.buildDirectory(), include)))
                    .append("));\n");
        }
        builder.append("    ").append(commandSymbol).append(".addFileArg(b.path(")
                .append(quote(relative(workspace.buildDirectory(), input.source())))
                .append("));\n")
                .append("    ").append(commandSymbol).append(".addArg(\"-o\");\n")
                .append("    const ").append(assemblySymbol).append(" = ")
                .append(commandSymbol).append(".addOutputFileArg(")
                .append(quote(input.id() + ".s"))
                .append(");\n")
                .append("    module_").append(unitSymbol).append(".addAssemblyFile(")
                .append(assemblySymbol).append(");\n")
                .append("    const ").append(installSymbol)
                .append(" = b.addInstallFileWithDir(")
                .append(assemblySymbol).append(", .prefix, ")
                .append(quote(relative(
                        workspace.workspaceRoot(),
                        ZigOptimizedAssemblyEvidence.path(workspace, target, input))))
                .append(");\n");
        return installSymbol;
    }

    private String quotedFlags(Path source) {
        ArrayList<String> flags = new ArrayList<>(List.of(
                "-std=gnu11",
                "-Oz",
                "-S",
                "-g0",
                "-fPIC",
                "-DNDEBUG",
                "-fvisibility=hidden",
                "-ffunction-sections",
                "-fdata-sections",
                "-ffile-compilation-dir=.",
                "-fdebug-compilation-dir=."));
        if (!sources.libcRequirement().required()) {
            flags.add("-ffreestanding");
            flags.add("-fno-builtin");
        }
        flags.addAll(targetPolicy.generatedCCompilerFlags(
                machinePolicies.modeFor(source)));
        return String.join(", ", flags.stream().map(ZigBuildText::quote).toList());
    }

}
