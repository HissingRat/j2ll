package xyz.melodysky.toolchain;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class ZigBuildWriter {
    public Path write(
            ZigBuildWorkspace workspace,
            String libraryName,
            NativeBuildPlan buildPlan,
            ZigInputSet inputs) throws IOException {
        return write(
                workspace,
                libraryName,
                buildPlan,
                inputs,
                true,
                NativeUnwindRetentionPolicy.retaining());
    }

    public Path write(
            ZigBuildWorkspace workspace,
            String libraryName,
            NativeBuildPlan buildPlan,
            ZigInputSet inputs,
            boolean strip) throws IOException {
        return write(
                workspace,
                libraryName,
                buildPlan,
                inputs,
                strip,
                NativeUnwindRetentionPolicy.retaining());
    }

    public Path write(
            ZigBuildWorkspace workspace,
            String libraryName,
            NativeBuildPlan buildPlan,
            ZigInputSet inputs,
            boolean strip,
            NativeUnwindRetentionPolicy unwindRetentionPolicy) throws IOException {
        return write(
                workspace,
                libraryName,
                buildPlan,
                inputs,
                ZigCInputMachinePolicyPlan.defaults(inputs),
                strip,
                unwindRetentionPolicy);
    }

    Path write(
            ZigBuildWorkspace workspace,
            String libraryName,
            NativeBuildPlan buildPlan,
            ZigInputSet inputs,
            ZigCInputMachinePolicyPlan machinePolicies,
            boolean strip,
            NativeUnwindRetentionPolicy unwindRetentionPolicy) throws IOException {
        requireSafeLibraryName(libraryName);
        ZigBuildProgressPlan.CompileInputInventory compileInputs =
                ZigBuildProgressPlan.inventory(inputs.sources());
        Files.createDirectories(workspace.buildDirectory());
        Files.writeString(
                workspace.buildZig(),
                buildZig(
                        workspace,
                        libraryName,
                        buildPlan,
                        inputs,
                        machinePolicies,
                        strip,
                        unwindRetentionPolicy,
                        compileInputs),
                StandardCharsets.UTF_8);
        Files.writeString(
                workspace.manifest(),
                new ZigBuildManifestRenderer().render(
                        workspace,
                        libraryName,
                        buildPlan,
                        inputs,
                        machinePolicies,
                        compileInputs,
                        unwindRetentionPolicy),
                StandardCharsets.UTF_8);
        return workspace.buildZig();
    }

    public String buildZig(
            ZigBuildWorkspace workspace,
            String libraryName,
            NativeBuildPlan buildPlan,
            ZigSourceSet sources) {
        return buildZig(
                workspace,
                libraryName,
                buildPlan,
                sources,
                true,
                NativeUnwindRetentionPolicy.retaining());
    }

    public String buildZig(
            ZigBuildWorkspace workspace,
            String libraryName,
            NativeBuildPlan buildPlan,
            ZigSourceSet sources,
            boolean strip) {
        return buildZig(
                workspace,
                libraryName,
                buildPlan,
                sources,
                strip,
                NativeUnwindRetentionPolicy.retaining());
    }

    public String buildZig(
            ZigBuildWorkspace workspace,
            String libraryName,
            NativeBuildPlan buildPlan,
            ZigSourceSet sources,
            boolean strip,
            NativeUnwindRetentionPolicy unwindRetentionPolicy) {
        ZigInputSet inputs = new ZigInputSet(sources);
        return buildZig(
                workspace,
                libraryName,
                buildPlan,
                inputs,
                ZigCInputMachinePolicyPlan.defaults(inputs),
                strip,
                unwindRetentionPolicy);
    }

    String buildZig(
            ZigBuildWorkspace workspace,
            String libraryName,
            NativeBuildPlan buildPlan,
            ZigInputSet inputs,
            ZigCInputMachinePolicyPlan machinePolicies,
            boolean strip,
            NativeUnwindRetentionPolicy unwindRetentionPolicy) {
        return buildZig(
                workspace,
                libraryName,
                buildPlan,
                inputs,
                machinePolicies,
                strip,
                unwindRetentionPolicy,
                ZigBuildProgressPlan.inventory(inputs.sources()));
    }

    private String buildZig(
            ZigBuildWorkspace workspace,
            String libraryName,
            NativeBuildPlan buildPlan,
            ZigInputSet inputs,
            ZigCInputMachinePolicyPlan machinePolicies,
            boolean strip,
            NativeUnwindRetentionPolicy unwindRetentionPolicy,
            ZigBuildProgressPlan.CompileInputInventory compileInputs) {
        requireSafeLibraryName(libraryName);
        ZigSourceSet sources = inputs.sources();
        StringBuilder builder = new StringBuilder();
        builder.append("""
                const std = @import("std");

                pub fn build(b: *std.Build) void {
                    const optimize = .ReleaseSafe;
                    const c_optimize = .ReleaseSmall;
                """);
        if (!buildPlan.units().isEmpty()) {
            builder.append("    const progress_markers = b.addWriteFiles();\n");
        }
        ZigTargetBuildEmitter targetEmitter =
                new ZigTargetBuildEmitter(
                        workspace,
                        libraryName,
                        sources,
                        machinePolicies,
                        strip,
                        unwindRetentionPolicy);
        for (ZigBuildProgressPlan.TargetPlan target :
                ZigBuildProgressPlan.forInventory(buildPlan, compileInputs).targets()) {
            builder.append(targetEmitter.emit(target));
        }
        builder.append("}\n");
        return builder.toString();
    }

    private void requireSafeLibraryName(String libraryName) {
        if (!NativeLibraryName.isSafe(libraryName)) {
            throw new IllegalArgumentException("unsafe native library name: " + libraryName);
        }
    }
}
