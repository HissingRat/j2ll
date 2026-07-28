package xyz.melodysky.toolchain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import xyz.melodysky.toolchain.nativetext.GeneratedCFragmentTextObfuscator;
import xyz.melodysky.toolchain.nativetext.GeneratedNativeHardeningAudit;
import xyz.melodysky.toolchain.nativetext.NativeTextCEmitter;
import xyz.melodysky.toolchain.nativetext.NativeTextBuildKey;
import xyz.melodysky.toolchain.symbols.NativeBinaryPrivacyInspector;
import xyz.melodysky.toolchain.symbols.NativeSymbolInspector;
import xyz.melodysky.toolchain.symbols.SymbolVisibilityPlanner;

class ZigCrossTargetBuildTest {
    @TempDir
    Path temp;

    @Test
    void realManagedZigBuildsAndInspectsAllSixTargetsInOneInvocation() throws Exception {
        Path zigExecutable = realZigExecutable();
        assumeTrue(zigExecutable != null && Files.isRegularFile(zigExecutable),
                "set -Dj2ll.realZig=<zig 0.15.2 executable> to run the real cross-target build");
        ZigCommandResult version = ZigCommandRunner.process().run(
                List.of(zigExecutable.toString(), "version"),
                zigExecutable.getParent(),
                java.util.Map.of());
        assumeTrue(version.exitCode() == 0 && version.stdout().trim().equals("0.15.2"),
                "the cross-target build test requires Zig 0.15.2");

        ZigBuildWorkspace workspace = ZigBuildWorkspace.under(temp);
        Files.createDirectories(workspace.jniDirectory());
        Files.createDirectories(workspace.llvmDirectory());
        Files.createDirectories(workspace.logsDirectory());
        Path wrapper = workspace.jniDirectory().resolve("probe.c");
        List<String> sensitiveMetadata = List.of(
                "j2ll/privacy/Owner_91e8b5fd67a249f4",
                "nativeMethod_73c4f5e0a8614bbc",
                "fieldToken_4e902d3bca5f41a6",
                "(Lj2ll/privacy/Argument_1f7c29e658a34d98;)V",
                "Lj2ll/privacy/FieldType_85a60137d42b4c9e;");
        List<HostNativeLocalAbiBridgeSource.Parameter> localAbiParameters =
                List.of(
                        new HostNativeLocalAbiBridgeSource.Parameter(
                                "jint",
                                "first",
                                "JNI_VERSION_1_8"),
                        new HostNativeLocalAbiBridgeSource.Parameter(
                                "jlong",
                                "second",
                                "1LL"),
                        new HostNativeLocalAbiBridgeSource.Parameter(
                                "jdouble",
                                "third",
                                "2.0"),
                        new HostNativeLocalAbiBridgeSource.Parameter(
                                "jobject",
                                "fourth",
                                "NULL"));
        HostNativeLocalAbiBridgeSource.Emission localAbi = null;
        for (int index = 0; index < 1024 && localAbi == null; index++) {
            HostNativeLocalAbiBridgeSource.Emission candidate =
                    new HostNativeLocalAbiBridgeSource().emit(
                            NativeTextBuildKey.fromUtf8(
                                    "cross-target-local-abi-build-"
                                            + index),
                            "j2ll/probe#hidden!(III)I",
                            "jint",
                            "j2ll_hidden_llvm",
                            localAbiParameters);
            if (candidate.plan().shape()
                    == NativeLocalAbiPlan.Shape
                            .BRANCHED_PERMUTING_BRIDGE) {
                localAbi = candidate;
            }
        }
        assertTrue(localAbi != null);
        String wrapperSource = """
                #include <stddef.h>
                #include <stdint.h>
                """
                + new NativeTextCEmitter().runtimeSource()
                + new GeneratedCFragmentTextObfuscator().obfuscate(
                NativeTextBuildKey.fromUtf8("cross-target-probe-build"),
                "cross-target-probe",
                """
                #include <jni.h>
                #include <stddef.h>
                #include <stdint.h>

                static volatile uintptr_t j2ll_probe_metadata_sink = 0;

                static jint j2ll_hidden_c(jint value) {
                    return value + 1;
                }

                extern jint j2ll_hidden_llvm(
                        jint first,
                        jlong second,
                        jdouble third,
                        jobject fourth);

                """
                + localAbi.source()
                + """
                static void j2ll_probe_native(JNIEnv* env, jobject self, jobject argument) {
                    (void)env;
                    (void)self;
                    (void)argument;
                }

                static jint j2ll_register(JavaVM* vm) {
                    (void)vm;
                    const char* j2ll_probe_owner =
                            "j2ll/privacy/Owner_91e8b5fd67a249f4";
                    const char* j2ll_probe_field_name =
                            "fieldToken_4e902d3bca5f41a6";
                    const char* j2ll_probe_field_descriptor =
                            "Lj2ll/privacy/FieldType_85a60137d42b4c9e;";
                    const char* j2ll_probe_method_name =
                            "nativeMethod_73c4f5e0a8614bbc";
                    const char* j2ll_probe_method_descriptor =
                            "(Lj2ll/privacy/Argument_1f7c29e658a34d98;)V";
                    JNINativeMethod j2ll_probe_methods[1];
                    j2ll_probe_methods[0].name = (char*)j2ll_probe_method_name;
                    j2ll_probe_methods[0].signature =
                            (char*)j2ll_probe_method_descriptor;
                    j2ll_probe_methods[0].fnPtr = (void*)j2ll_probe_native;
                    j2ll_probe_metadata_sink ^= (uintptr_t)j2ll_probe_owner;
                    j2ll_probe_metadata_sink ^= (uintptr_t)j2ll_probe_field_name;
                    j2ll_probe_metadata_sink ^= (uintptr_t)j2ll_probe_field_descriptor;
                    j2ll_probe_metadata_sink ^= (uintptr_t)j2ll_probe_methods[0].name;
                    j2ll_probe_metadata_sink ^= (uintptr_t)j2ll_probe_methods[0].signature;
                """
                + localAbi.wrapperPrelude()
                + """
                    return j2ll_hidden_c(
                            """
                + localAbi.wrapperInvocation()
                + """
                            ) - 4;
                }

                JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
                    (void)reserved;
                    return j2ll_register(vm);
                }
                """);
        for (String sensitive : sensitiveMetadata) {
            assertFalse(wrapperSource.contains(sensitive), "generated C plaintext: " + sensitive);
        }
        assertTrue(new GeneratedNativeHardeningAudit().audit(wrapperSource).passed());
        assertFalse(wrapperSource.contains("j2ll_encoded_metadata_strings"));
        assertFalse(wrapperSource.contains("j2ll_decode_metadata_strings"));
        assertFalse(wrapperSource.contains("j2ll_lab_slot_"));
        assertFalse(wrapperSource.contains(
                "static volatile uintptr_t j2ll_lab_"));
        assertTrue(wrapperSource.contains(
                "volatile uintptr_t j2ll_lab_"));
        assertTrue(wrapperSource.contains(
                "__attribute__((noinline, optnone, used))"));
        Files.writeString(wrapper, wrapperSource, StandardCharsets.UTF_8);
        Path llvm = workspace.llvmDirectory().resolve("probe.ll");
        Files.writeString(llvm, """
                define hidden i32 @j2ll_hidden_llvm(
                        i32 %first,
                        i64 %second,
                        double %third,
                        ptr %fourth) {
                entry:
                  %second32 = trunc i64 %second to i32
                  %third32 = fptosi double %third to i32
                  %partial = add i32 %first, %second32
                  %result = add i32 %partial, %third32
                  ret i32 %result
                }
                """, StandardCharsets.UTF_8);

        List<TargetTriple> targets = Arrays.asList(TargetTriple.values());
        NativeBuildPlan plan = new NativeBuildPlanner().plan(temp, "j2ll_probe", targets);
        ZigSourceSet sources = new ZigSourceSet(
                List.of(llvm),
                List.of(wrapper),
                List.of(),
                new ZigJniHeaderSet().prepare(workspace));
        new ZigBuildWriter().write(workspace, "j2ll_probe", plan, new ZigInputSet(sources));
        ManagedZig zig = new ManagedZig(
                zigExecutable,
                zigExecutable.getParent(),
                "0.15.2",
                "testProvidedVerifiedExecutable");
        assertOptimizedBranchedTopology(
                zigExecutable,
                wrapper,
                sources.includeDirectories().get(0),
                localAbi);

        CopyOnWriteArrayList<TargetTriple> completedTargets = new CopyOnWriteArrayList<>();
        new ZigBuildInvoker().invoke(
                zig,
                workspace,
                plan,
                sources,
                (target, completed, total) -> completedTargets.add(target));

        assertEquals(plan.units().size(), completedTargets.size());
        assertEquals(
                Set.copyOf(plan.units().stream().map(NativeBuildUnit::target).toList()),
                Set.copyOf(completedTargets));

        NativeSymbolInspector inspector = new NativeSymbolInspector();
        NativeBinaryPrivacyInspector privacyInspector = new NativeBinaryPrivacyInspector();
        String workspacePath = workspace.workspaceRoot().toAbsolutePath().normalize().toString();
        List<String> forbiddenBinaryText = new java.util.ArrayList<>(sensitiveMetadata);
        forbiddenBinaryText.add(workspacePath);
        forbiddenBinaryText.add(workspacePath.replace('\\', '/'));
        assertTrue(Files.notExists(
                ZigTargetCompletionMonitor.progressDirectory(workspace),
                LinkOption.NOFOLLOW_LINKS));
        for (NativeBuildUnit unit : plan.units()) {
            assertTrue(Files.isRegularFile(unit.outputPath()), unit.outputPath().toString());
            assertTrue(Files.size(unit.outputPath()) > 0, unit.outputPath().toString());
            byte[] binary = Files.readAllBytes(unit.outputPath());
            for (String forbidden : forbiddenBinaryText.stream().distinct().toList()) {
                assertEncodedTextAbsent(binary, forbidden, unit.target());
            }
            List<String> exports = inspector.exportedSymbols(unit.target(), unit.outputPath());
            assertEquals(
                    new SymbolVisibilityPlanner().loaderExports(unit.target()).symbols().stream()
                            .map(symbol -> symbol.name())
                            .toList(),
                    exports,
                    unit.target().directoryName());
            assertFalse(exports.contains("j2ll_hidden_c"), unit.target().directoryName());
            assertFalse(exports.contains("j2ll_hidden_llvm"), unit.target().directoryName());
            assertFalse(exports.contains("j2ll_register"), unit.target().directoryName());
            assertFalse(
                    exports.stream().anyMatch(symbol -> symbol.startsWith(
                            "j2ll_lab_")),
                    unit.target().directoryName());
            if (unit.target().isWindows()) {
                NativeBinaryPrivacyInspector.PePrivacyInfo pe = privacyInspector.inspectPe(binary);
                assertFalse(pe.hasCoffSymbolTable(), unit.target().directoryName() + " COFF symbol table");
                assertFalse(pe.hasCodeViewDebugEntry(), unit.target().directoryName() + " CodeView debug entry");
            }
            try (var outputs = Files.list(unit.outputPath().getParent())) {
                assertTrue(outputs.noneMatch(path -> path.getFileName().toString().endsWith(".pdb")),
                        unit.target().directoryName());
            }
        }
    }

    private void assertOptimizedBranchedTopology(
            Path zigExecutable,
            Path wrapper,
            Path includeDirectory,
            HostNativeLocalAbiBridgeSource.Emission localAbi)
            throws Exception {
        for (TargetTriple target : TargetTriple.values()) {
            Path optimized = temp.resolve(
                    "local-abi-"
                            + target.directoryName()
                            + ".ll");
            ZigCommandResult compile = ZigCommandRunner.process().run(
                    List.of(
                            zigExecutable.toString(),
                            "cc",
                            "-target",
                            target.zigTarget(),
                            "-std=gnu11",
                            "-O2",
                            "-S",
                            "-emit-llvm",
                            "-I",
                            includeDirectory.toString(),
                            wrapper.toString(),
                            "-o",
                            optimized.toString()),
                    temp,
                    java.util.Map.of());
            assertEquals(
                    0,
                    compile.exitCode(),
                    target.directoryName()
                            + ": "
                            + compile.stderr());
            String llvm = Files.readString(
                    optimized,
                    StandardCharsets.UTF_8);
            assertTrue(
                    llvm.contains("load volatile"),
                    target.directoryName());
            for (String branchCallee :
                    localAbi.plan().bridgeSymbols().subList(0, 2)) {
                assertTrue(
                        Pattern.compile(
                                        "\\bcall\\b[^\\n]*@"
                                                + Pattern.quote(branchCallee)
                                                + "\\(")
                                .matcher(llvm)
                                .find(),
                        target.directoryName()
                                + ": "
                                + branchCallee);
            }
            assertTrue(
                    Pattern.compile(
                                    "\\bcall\\b[^\\n]*@"
                                            + Pattern.quote(localAbi.plan()
                                                    .bridgeSymbols()
                                                    .get(2))
                                            + "\\(")
                            .matcher(llvm)
                            .find(),
                    target.directoryName());
        }
    }

    private void assertEncodedTextAbsent(byte[] binary, String forbidden, TargetTriple target) {
        assertFalse(
                NativeBinaryPrivacyInspector.contains(binary, forbidden.getBytes(StandardCharsets.UTF_8)),
                target.directoryName() + " UTF-8 plaintext: " + forbidden);
        assertFalse(
                NativeBinaryPrivacyInspector.contains(binary, forbidden.getBytes(StandardCharsets.UTF_16LE)),
                target.directoryName() + " UTF-16LE plaintext: " + forbidden);
    }

    private Path realZigExecutable() {
        String configured = System.getProperty("j2ll.realZig");
        if (configured == null || configured.isBlank()) {
            configured = System.getenv("J2LL_REAL_ZIG");
        }
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured);
        }
        String path = System.getenv("PATH");
        if (path == null || path.isBlank()) {
            return null;
        }
        String executable = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT)
                .contains("win") ? "zig.exe" : "zig";
        for (String directory : path.split(java.util.regex.Pattern.quote(File.pathSeparator))) {
            if (directory.isBlank()) {
                continue;
            }
            Path candidate = Path.of(directory).resolve(executable);
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        return null;
    }
}
