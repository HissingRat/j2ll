package xyz.melodysky.toolchain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
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
        String wrapperSource = new CMetadataStringObfuscator().obfuscate("""
                #include <jni.h>
                #include <stddef.h>
                #include <stdint.h>

                static const char* j2ll_probe_owner =
                        "j2ll/privacy/Owner_91e8b5fd67a249f4";
                static const char* j2ll_probe_field_name =
                        "fieldToken_4e902d3bca5f41a6";
                static const char* j2ll_probe_field_descriptor =
                        "Lj2ll/privacy/FieldType_85a60137d42b4c9e;";
                static volatile uintptr_t j2ll_probe_metadata_sink = 0;

                static jint j2ll_hidden_c(jint value) {
                    return value + 1;
                }

                extern jint j2ll_hidden_llvm(jint value);

                static void j2ll_probe_native(JNIEnv* env, jobject self, jobject argument) {
                    (void)env;
                    (void)self;
                    (void)argument;
                }

                static JNINativeMethod j2ll_probe_methods[] = {
                    {
                        "nativeMethod_73c4f5e0a8614bbc",
                        "(Lj2ll/privacy/Argument_1f7c29e658a34d98;)V",
                        (void*)j2ll_probe_native
                    },
                };

                JNIEXPORT jint JNICALL j2ll_register(JavaVM* vm) {
                    (void)vm;
                    j2ll_probe_metadata_sink ^= (uintptr_t)j2ll_probe_owner;
                    j2ll_probe_metadata_sink ^= (uintptr_t)j2ll_probe_field_name;
                    j2ll_probe_metadata_sink ^= (uintptr_t)j2ll_probe_field_descriptor;
                    j2ll_probe_metadata_sink ^= (uintptr_t)j2ll_probe_methods[0].name;
                    j2ll_probe_metadata_sink ^= (uintptr_t)j2ll_probe_methods[0].signature;
                    return j2ll_hidden_c(j2ll_hidden_llvm(JNI_VERSION_1_8)) - 2;
                }

                JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
                    (void)reserved;
                    return j2ll_register(vm);
                }
                """);
        for (String sensitive : sensitiveMetadata) {
            assertFalse(wrapperSource.contains(sensitive), "generated C plaintext: " + sensitive);
        }
        Files.writeString(wrapper, wrapperSource, StandardCharsets.UTF_8);
        Path llvm = workspace.llvmDirectory().resolve("probe.ll");
        Files.writeString(llvm, """
                define hidden i32 @j2ll_hidden_llvm(i32 %value) {
                entry:
                  %result = add i32 %value, 1
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
