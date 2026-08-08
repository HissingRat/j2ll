package xyz.melodysky.toolchain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import xyz.melodysky.backend.llvm.model.LlvmNativeUnwindProof;

class ZigBuildWriterTest {
    @TempDir
    Path temp;

    @Test
    void generatedBuildZigExposesRealCompileAndLinkBoundariesPerTarget() throws Exception {
        ZigBuildWorkspace workspace = ZigBuildWorkspace.under(temp);
        ZigSourceSet sources = new ZigSourceSet(
                List.of(workspace.llvmDirectory().resolve("pkg_A.ll")),
                List.of(
                        workspace.jniDirectory().resolve("wrapper.c"),
                        workspace.runtimeDirectory().resolve("helper.c")),
                List.of(),
                List.of());
        NativeBuildPlan plan = new NativeBuildPlan(List.of(
                unit(TargetTriple.WINDOWS_X64),
                unit(TargetTriple.MACOS_X64),
                unit(TargetTriple.LINUX_ARM64)));

        new ZigBuildWriter().write(workspace, "j2lltest", plan, new ZigInputSet(sources));

        String buildZig = Files.readString(workspace.buildZig());
        assertTrue(buildZig.contains("const optimize = .ReleaseSafe;"));
        assertTrue(buildZig.contains(
                "const c_optimize = .ReleaseSmall;"));
        assertTrue(buildZig.contains("const progress_markers = b.addWriteFiles();"));
        assertFalse(buildZig.contains(".addCSourceFiles("));
        assertTrue(buildZig.contains(".pic = true"));
        assertTrue(buildZig.contains(".implib_dir = .disabled"));
        assertTrue(buildZig.contains("lib_macos_x64.discard_local_symbols = true"));
        assertTrue(buildZig.contains("lib_windows_x64.link_gc_sections = true"));
        assertTrue(buildZig.contains("\"-ffunction-sections\""));
        assertTrue(buildZig.contains("\"-fdata-sections\""));
        assertEquals(6, countOccurrences(
                buildZig,
                "\"-Werror=implicit-function-declaration\""));
        assertEquals(4, countOccurrences(
                buildZig,
                "\"-enable-machine-outliner=always\""));
        assertEquals(4, countOccurrences(
                buildZig,
                "\"-outliner-benefit-threshold=16\""));
        for (TargetTriple target : List.of(
                TargetTriple.LINUX_ARM64,
                TargetTriple.MACOS_X64,
                TargetTriple.WINDOWS_X64)) {
            assertTargetGraph(buildZig, workspace, target);
        }

        String manifest = Files.readString(workspace.manifest());
        assertTrue(manifest.contains("\"libraryName\": \"j2lltest\""));
        assertTrue(manifest.contains("\"llvm/pkg_A.ll\""));
        assertTrue(manifest.contains("\"target\": \"linux-arm64\""));
        assertTrue(manifest.contains("\"target\": \"macos-x64\""));
        assertTrue(manifest.contains("\"target\": \"windows-x64\""));
        assertTrue(manifest.contains("\"machineOutlinerEnabled\": true"));
        assertTrue(manifest.contains(
                "\"machineOutlinerMinimumBenefitThreshold\": 16"));
        assertTrue(manifest.contains(
                "\"machineOutlinerMinimumBenefitThreshold\": 0"));
        assertTrue(manifest.contains(
                "\"machineOutlinerReason\": \"MACHINE_OUTLINER_WINDOWS_SEH_UNSUPPORTED\""));
    }

    @Test
    void prebuiltObjectsStayLinkInputsAndDoNotBecomeCompileProgressUnits() {
        ZigBuildWorkspace workspace = ZigBuildWorkspace.under(temp);
        Path prebuilt = temp.resolve("input.o");
        NativeBuildPlan plan = new NativeBuildPlan(List.of(unit(TargetTriple.LINUX_X64)));
        ZigSourceSet sources = new ZigSourceSet(
                List.of(),
                List.of(),
                List.of(prebuilt),
                List.of());

        String buildZig =
                new ZigBuildWriter().buildZig(workspace, "j2lltest", plan, sources, true);

        assertTrue(buildZig.contains(".addObjectFile(.{ .cwd_relative = "));
        assertFalse(buildZig.contains("compile_marker_linux_x64"));
        assertTrue(buildZig.contains(
                "j2ll-target-linking-v1:linux-x64:0\\n"));
    }

    @Test
    void largeMixedSourceSetUsesBoundedStaticCompileUnitsWithRealMarkers() {
        ZigBuildWorkspace workspace = ZigBuildWorkspace.under(temp);
        NativeBuildPlan plan = new NativeBuildPlan(List.of(unit(TargetTriple.LINUX_X64)));
        List<Path> cSources = java.util.stream.IntStream.range(0, 65)
                .mapToObj(index -> workspace.jniDirectory().resolve("input-" + index + ".c"))
                .toList();
        List<Path> llvmSources = List.of(
                workspace.llvmDirectory().resolve("first.ll"),
                workspace.llvmDirectory().resolve("second.ll"));
        ZigSourceSet sources = new ZigSourceSet(
                llvmSources,
                cSources,
                List.of(),
                List.of());

        String buildZig =
                new ZigBuildWriter().buildZig(workspace, "j2lltest", plan, sources, true);
        ZigBuildProgressPlan.TargetPlan target =
                ZigBuildProgressPlan.forSources(plan, sources).targets().get(0);

        assertEquals(ZigBuildProgressPlan.MAX_COMPILE_UNITS, target.compileUnits().size());
        assertEquals(
                ZigBuildProgressPlan.MAX_COMPILE_UNITS,
                countOccurrences(buildZig, ".linkage = .static"));
        assertEquals(
                ZigBuildProgressPlan.MAX_COMPILE_UNITS,
                countOccurrences(buildZig, "module_linux_x64.linkLibrary(compile_linux_x64_"));
        for (ZigBuildProgressPlan.CompileUnit compileUnit : target.compileUnits()) {
            assertEquals(
                    1,
                    compileUnit.inputs().stream()
                            .map(ZigBuildProgressPlan.CompileInput::kind)
                            .distinct()
                            .count(),
                    compileUnit.id());
            String unitSymbol = "linux_x64_" + compileUnit.id().replace('-', '_');
            assertTrue(buildZig.contains(
                    "install_compile_marker_" + unitSymbol
                            + ".step.dependOn(&compile_" + unitSymbol + ".step)"));
            String optimizeMode = compileUnit.kind()
                    == ZigBuildProgressPlan.CompileInputKind.C
                    ? "c_optimize"
                    : "optimize";
            assertTrue(buildZig.contains(
                    "const module_" + unitSymbol
                            + " = b.createModule(.{\n"
                            + "        .target = target_linux_x64,\n"
                            + "        .optimize = " + optimizeMode + ",\n"),
                    compileUnit.id());
        }
        for (Path source : java.util.stream.Stream.concat(
                        cSources.stream(),
                        llvmSources.stream())
                .toList()) {
            String relative = workspace.buildDirectory()
                    .toAbsolutePath()
                    .normalize()
                    .relativize(source.toAbsolutePath().normalize())
                    .toString()
                    .replace('\\', '/');
            assertEquals(1, countOccurrences(buildZig, "\"" + relative + "\""), relative);
        }
    }

    @Test
    void manifestRecordsSelectedTargetsThatArePreflightOnly() throws Exception {
        ZigBuildWorkspace workspace = ZigBuildWorkspace.under(temp);
        ZigSourceSet sources = new ZigSourceSet(
                List.of(),
                List.of(workspace.jniDirectory().resolve("wrapper.c")),
                List.of(),
                List.of());
        NativeBuildUnit macos = unit(TargetTriple.MACOS_ARM64);
        NativeBuildTargetPreflight linux = new NativeBuildTargetPreflight(
                TargetTriple.LINUX_X64,
                temp.resolve("native/x64-linux.so"),
                "j2lltest",
                false,
                false,
                "ZIG_TARGET_UNBUILDABLE",
                "synthetic unsupported target for manifest coverage",
                "managedZig0.15.2CrossTargetSharedLibrary",
                "synthetic missing capability",
                true,
                "syntheticFailure",
                "synthetic preflight failure");
        NativeBuildTargetPreflight macosPreflight = new NativeBuildTargetPreflight(
                macos.target(),
                macos.outputPath(),
                macos.libraryName(),
                true,
                true,
                "CURRENT_HOST_TARGET",
                "synthetic host target",
                "managedZig0.15.2CrossTargetSharedLibrary",
                "managed Zig target support");
        NativeBuildPlan plan = new NativeBuildPlan(List.of(macos), List.of(linux, macosPreflight));

        new ZigBuildWriter().write(workspace, "j2lltest", plan, new ZigInputSet(sources));

        String buildZig = Files.readString(workspace.buildZig());
        assertTrue(buildZig.contains("const target_macos_arm64"));
        assertFalse(buildZig.contains("const target_linux_x64"));
        String manifest = Files.readString(workspace.manifest());
        assertTrue(manifest.contains("\"selectedTargets\""));
        assertTrue(manifest.contains("\"requiredTargets\""));
        assertTrue(manifest.contains("\"linux-x64\""));
        assertTrue(manifest.contains("\"buildableTargets\""));
        assertTrue(manifest.contains("\"skippedTargets\""));
        assertTrue(manifest.contains("\"failedTargets\""));
        assertTrue(manifest.contains("\"status\": \"failed\""));
        assertTrue(manifest.contains("\"reasonCode\": \"ZIG_TARGET_UNBUILDABLE\""));
        assertTrue(manifest.contains(
                "\"requiredCapability\": \"managedZig0.15.2CrossTargetSharedLibrary\""));
    }

    @Test
    void resolvedBinaryStripPolicyControlsEveryZigModule() {
        ZigBuildWorkspace workspace = ZigBuildWorkspace.under(temp);
        NativeBuildPlan plan = new NativeBuildPlan(List.of(unit(TargetTriple.LINUX_X64)));
        ZigSourceSet sources = new ZigSourceSet(
                List.of(workspace.llvmDirectory().resolve("input.ll")),
                List.of(workspace.jniDirectory().resolve("input.c")),
                List.of(),
                List.of());

        String buildZig =
                new ZigBuildWriter().buildZig(workspace, "j2lltest", plan, sources, false);

        assertFalse(buildZig.contains(".strip = true"));
        assertTrue(countOccurrences(buildZig, ".strip = false") == 3);
    }

    @Test
    void unwindPolicyOnlyAddsNoUnwindFlagsToEligibleGeneratedCUnits() throws Exception {
        ZigBuildWorkspace workspace = ZigBuildWorkspace.under(temp);
        NativeBuildPlan plan = new NativeBuildPlan(List.of(
                unit(TargetTriple.WINDOWS_X64),
                unit(TargetTriple.LINUX_X64),
                unit(TargetTriple.MACOS_ARM64)));
        ZigSourceSet sources = new ZigSourceSet(
                List.of(workspace.llvmDirectory().resolve("input.ll")),
                List.of(workspace.jniDirectory().resolve("input.c")),
                List.of(),
                List.of());
        NativeUnwindRetentionPolicy policy =
                new NativeUnwindRetentionPolicy(false, false);

        String buildZig = new ZigBuildWriter().buildZig(
                workspace,
                "j2lltest",
                plan,
                sources,
                true,
                policy);
        new ZigBuildWriter().write(
                workspace,
                "j2lltest",
                plan,
                new ZigInputSet(sources),
                true,
                policy);

        assertEquals(2, countOccurrences(buildZig, "\"-fno-unwind-tables\""));
        assertEquals(2, countOccurrences(
                buildZig,
                "\"-fno-asynchronous-unwind-tables\""));
        String windowsCUnit = sourceBlock(buildZig, "module_windows_x64_c_0.addCSourceFile");
        assertFalse(windowsCUnit.contains("-fno-unwind-tables"));
        String linuxCUnit = sourceBlock(buildZig, "module_linux_x64_c_0.addCSourceFile");
        assertTrue(linuxCUnit.contains("-fno-unwind-tables"));

        String manifest = Files.readString(workspace.manifest());
        assertTrue(manifest.contains("\"retainUnwindInfoRequested\": false"));
        assertTrue(manifest.contains("\"generatedCUnwindInfoRetained\": false"));
        assertTrue(manifest.contains(
                "\"retainUnwindInfoReason\": \"LLVM_MODULE_PROOF_RETAINED\""));
        assertTrue(manifest.contains("\"retainUnwindInfoReason\": \"WINDOWS_SEH_REQUIRED\""));
    }

    @Test
    void targetSelectsTheProvenLlvmVariantAndReportsItsEffectiveUnwindPlan()
            throws Exception {
        ZigBuildWorkspace workspace = ZigBuildWorkspace.under(temp);
        Path retained = workspace.llvmDirectory().resolve("pkg_A.ll");
        Path omission = workspace.llvmDirectory().resolve("pkg_A.no-unwind.ll");
        NativeLlvmSourcePlan llvmPlan = new NativeLlvmSourcePlan(List.of(
                new NativeLlvmSource(
                        "pkg/A",
                        retained,
                        Optional.of(omission),
                        true,
                        LlvmNativeUnwindProof.PROVEN_ABSENT)));
        ZigSourceSet sources = new ZigSourceSet(
                List.of(retained),
                List.of(),
                List.of(),
                List.of(),
                NativeLibcRequirementPlan.retaining(),
                llvmPlan);
        NativeBuildPlan plan = new NativeBuildPlan(List.of(
                unit(TargetTriple.LINUX_X64),
                unit(TargetTriple.WINDOWS_X64)));
        NativeUnwindRetentionPolicy policy =
                new NativeUnwindRetentionPolicy(false, false);

        new ZigBuildWriter().write(
                workspace,
                "j2lltest",
                plan,
                new ZigInputSet(sources),
                true,
                policy);

        String buildZig = Files.readString(workspace.buildZig());
        assertTrue(buildZig.contains(
                "module_linux_x64_llvm_0.addObjectFile(b.path(\"llvm/pkg_A.no-unwind.ll\"));"));
        assertTrue(buildZig.contains(
                "module_windows_x64_llvm_0.addObjectFile(b.path(\"llvm/pkg_A.ll\"));"));
        assertFalse(buildZig.contains(
                "module_linux_x64_llvm_0.addObjectFile(b.path(\"llvm/pkg_A.ll\"));"));
        assertFalse(buildZig.contains(
                "module_windows_x64_llvm_0.addObjectFile(b.path(\"llvm/pkg_A.no-unwind.ll\"));"));

        JsonObject manifest = JsonParser.parseString(
                        Files.readString(workspace.manifest()))
                .getAsJsonObject();
        JsonArray unwindSources = manifest.getAsJsonArray("llvmUnwindSources");
        assertEquals(1, unwindSources.size());
        JsonObject source = unwindSources.get(0).getAsJsonObject();
        assertEquals("llvm/pkg_A.ll", source.get("retainedPath").getAsString());
        assertEquals("llvm/pkg_A.no-unwind.ll", source.get("omissionPath").getAsString());
        assertTrue(source.get("omissionSafe").getAsBoolean());
        assertEquals(
                LlvmNativeUnwindProof.PROVEN_ABSENT,
                source.get("proofReasonCode").getAsString());

        JsonObject linux = targetManifest(manifest, "linux-x64");
        assertFalse(linux.get("retainUnwindInfoEffective").getAsBoolean());
        assertEquals("CONFIG_DISABLED", linux.get("retainUnwindInfoReason").getAsString());
        assertEquals(1, linux.get("llvmUnwindModuleCount").getAsInt());
        assertEquals(1, linux.get("llvmUnwindOmittedModuleCount").getAsInt());
        assertEquals(0, linux.get("llvmUnwindRetainedModuleCount").getAsInt());
        assertTrue(linux.get("finalUnwindOmissionExpected").getAsBoolean());

        JsonObject windows = targetManifest(manifest, "windows-x64");
        assertTrue(windows.get("retainUnwindInfoEffective").getAsBoolean());
        assertEquals(
                "WINDOWS_SEH_REQUIRED",
                windows.get("retainUnwindInfoReason").getAsString());
        assertEquals(1, windows.get("llvmUnwindModuleCount").getAsInt());
        assertEquals(0, windows.get("llvmUnwindOmittedModuleCount").getAsInt());
        assertEquals(1, windows.get("llvmUnwindRetainedModuleCount").getAsInt());
        assertFalse(windows.get("finalUnwindOmissionExpected").getAsBoolean());
    }

    @Test
    void exactGeneratedCSurfaceCanOmitLibcWhileCompatibilityInputsRetainIt()
            throws Exception {
        ZigBuildWorkspace workspace = ZigBuildWorkspace.under(temp);
        NativeBuildPlan plan = new NativeBuildPlan(
                List.of(unit(TargetTriple.LINUX_X64)));
        Path source = workspace.jniDirectory().resolve("input.c");
        ZigSourceSet libcFree = new ZigSourceSet(
                List.of(),
                List.of(source),
                List.of(),
                List.of(),
                NativeLibcRequirementPlan.inspect(
                        "static int helper(int value) { return value + 1; }"));

        String buildZig = new ZigBuildWriter().buildZig(
                workspace,
                "j2lltest",
                plan,
                libcFree,
                true);
        new ZigBuildWriter().write(
                workspace,
                "j2lltest",
                plan,
                new ZigInputSet(libcFree));

        assertFalse(buildZig.contains(".link_libc = true"));
        assertEquals(2, countOccurrences(buildZig, ".link_libc = false"));
        assertTrue(buildZig.contains(".linker_allow_shlib_undefined = false"));
        assertTrue(buildZig.contains("\"-ffreestanding\""));
        assertTrue(buildZig.contains("\"-fno-builtin\""));
        String manifest = Files.readString(workspace.manifest());
        assertTrue(manifest.contains("\"linkLibc\": false"));
        assertTrue(manifest.contains("\"libcRequirementReasons\": []"));
        assertTrue(manifest.contains("\"generatedSourceRequiresLibc\": false"));
        assertTrue(manifest.contains("\"libcDependencyEffective\": false"));
        assertTrue(manifest.contains(
                "\"libcDependencyReason\": \"GENERATED_SOURCE_LIBC_FREE\""));

        ZigSourceSet compatibility =
                new ZigSourceSet(List.of(), List.of(source), List.of(), List.of());
        String compatibilityBuild = new ZigBuildWriter().buildZig(
                workspace,
                "j2lltest",
                plan,
                compatibility,
                true);
        assertTrue(compatibilityBuild.contains(".link_libc = true"));
    }

    @Test
    void windowsLibcFreeLibraryDisablesTheCrtEntryPoint() {
        ZigBuildWorkspace workspace = ZigBuildWorkspace.under(temp);
        NativeBuildPlan plan = new NativeBuildPlan(
                List.of(unit(TargetTriple.WINDOWS_X64)));
        Path source = workspace.jniDirectory().resolve("input.c");
        ZigSourceSet libcFree = new ZigSourceSet(
                List.of(),
                List.of(source),
                List.of(),
                List.of(),
                NativeLibcRequirementPlan.inspect("int helper(void) { return 1; }"));

        String buildZig = new ZigBuildWriter().buildZig(
                workspace,
                "j2lltest",
                plan,
                libcFree,
                true);

        assertTrue(buildZig.contains(
                "lib_windows_x64.entry = .{ .symbol_name = \"j2lltest_entry\" };"));
        assertTrue(buildZig.contains(".link_libc = false"));
    }

    @Test
    void rejectsLibraryNameThatCouldInjectAPathOrZigSource() {
        ZigBuildWorkspace workspace = ZigBuildWorkspace.under(temp);
        NativeBuildPlan plan = new NativeBuildPlan(List.of(unit(TargetTriple.LINUX_X64)));

        assertThrows(
                IllegalArgumentException.class,
                () -> new ZigBuildWriter().buildZig(
                        workspace,
                        "../outside\nconst injected = true",
                        plan,
                        new ZigSourceSet(List.of(), List.of(), List.of(), List.of()),
                        true));
    }

    private void assertTargetGraph(
            String buildZig,
            ZigBuildWorkspace workspace,
            TargetTriple target) {
        String symbol = target.safeSymbol();
        String progressRoot = workspace.workspaceRoot()
                .toAbsolutePath()
                .normalize()
                .relativize(ZigTargetCompletionMonitor.progressDirectory(workspace))
                .toString()
                .replace('\\', '/');
        assertTrue(buildZig.contains(
                "const compile_" + symbol + "_c_0 = b.addLibrary"));
        assertTrue(buildZig.contains(
                "const compile_" + symbol + "_c_1 = b.addLibrary"));
        assertTrue(buildZig.contains(
                "const compile_" + symbol + "_llvm_0 = b.addLibrary"));
        assertTrue(buildZig.contains(
                "const module_" + symbol
                        + "_c_0 = b.createModule(.{\n"
                        + "        .target = target_" + symbol + ",\n"
                        + "        .optimize = c_optimize,\n"));
        assertTrue(buildZig.contains(
                "const module_" + symbol
                        + "_llvm_0 = b.createModule(.{\n"
                        + "        .target = target_" + symbol + ",\n"
                        + "        .optimize = optimize,\n"));
        assertTrue(buildZig.contains(
                "const module_" + symbol
                        + " = b.createModule(.{\n"
                        + "        .target = target_" + symbol + ",\n"
                        + "        .optimize = optimize,\n"));
        assertTrue(buildZig.contains(
                "module_" + symbol + ".linkLibrary(compile_" + symbol + "_c_0)"));
        assertTrue(buildZig.contains(
                "module_" + symbol + ".linkLibrary(compile_" + symbol + "_c_1)"));
        assertTrue(buildZig.contains(
                "module_" + symbol + ".linkLibrary(compile_" + symbol + "_llvm_0)"));
        assertTrue(buildZig.contains(
                "install_linking_marker_" + symbol
                        + ".step.dependOn(&install_compile_marker_" + symbol + "_c_0.step)"));
        assertTrue(buildZig.contains(
                "lib_" + symbol + ".step.dependOn(&install_linking_marker_" + symbol + ".step)"));
        assertTrue(buildZig.contains(
                "install_marker_" + symbol + ".step.dependOn(&install_" + symbol + ".step)"));
        assertTrue(buildZig.contains(
                "j2ll-target-linking-v1:" + target.directoryName() + ":3\\n"));
        assertTrue(buildZig.contains(
                "j2ll-target-complete-v1:" + target.directoryName() + "\\n"));
        assertTrue(buildZig.contains(
                "\"" + progressRoot + "/" + target.directoryName() + ".c-0.done\""));
        assertTrue(buildZig.contains(
                "\"" + progressRoot + "/" + target.directoryName() + ".linking\""));
        assertTrue(buildZig.contains(
                "\"" + progressRoot + "/" + target.directoryName() + ".done\""));
        String prefix = target.zigOsTag().equals("macos") ? "_" : "";
        assertTrue(buildZig.contains(
                "lib_" + symbol + ".forceUndefinedSymbol(\""
                        + prefix + "JNI_OnLoad\")"));
        assertFalse(buildZig.contains("forceUndefinedSymbol(\""
                + prefix + "j2ll_register\")"));
    }

    private int countOccurrences(String value, String needle) {
        int count = 0;
        int offset = 0;
        while ((offset = value.indexOf(needle, offset)) >= 0) {
            count++;
            offset += needle.length();
        }
        return count;
    }

    private String sourceBlock(String buildZig, String startNeedle) {
        int start = buildZig.indexOf(startNeedle);
        assertTrue(start >= 0, startNeedle);
        int end = buildZig.indexOf("});", start);
        assertTrue(end >= start, startNeedle);
        return buildZig.substring(start, end + 3);
    }

    private JsonObject targetManifest(JsonObject manifest, String targetName) {
        for (var element : manifest.getAsJsonArray("targets")) {
            JsonObject target = element.getAsJsonObject();
            if (targetName.equals(target.get("target").getAsString())) {
                return target;
            }
        }
        throw new AssertionError("missing target manifest: " + targetName);
    }

    private NativeBuildUnit unit(TargetTriple target) {
        return new NativeBuildUnit(
                target,
                temp.resolve("native").resolve(target.libraryFileName()),
                "j2lltest");
    }
}
