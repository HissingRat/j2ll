package xyz.melodysky.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import xyz.melodysky.config.ConfigLoader;
import xyz.melodysky.config.ResolvedConfig;
import xyz.melodysky.testsupport.DifferentialHarness;
import xyz.melodysky.testsupport.ProtectedExceptionFlowFixture;
import xyz.melodysky.toolchain.HostPlatform;
import xyz.melodysky.toolchain.J2llHomeResolver;
import xyz.melodysky.toolchain.TargetTriple;

class ProtectedJvmExceptionFlowNativeRuntimeE2eTest {
    @TempDir
    Path temp;

    @Test
    void protectedPendingExceptionsRunThroughTypedCatchCatchAllAndRethrowInRealJvm()
            throws Exception {
        Path j2llHome = realJ2llHome();
        assumeTrue(
                j2llHome != null && Files.isRegularFile(zigExecutable(j2llHome)),
                "set -Dj2ll.realHome=<distribution containing zig/zig(.exe)> "
                        + "to run the protected exception-flow native E2E");

        Path inputJar = ProtectedExceptionFlowFixture.compileJar(temp.resolve("fixture"));
        ResolvedConfig config = config(inputJar);
        Path workspace = temp.resolve("out/protected-exception-flow");
        MainlinePipelineResult pipeline;
        try (AutoCloseable ignored = useJ2llHome(j2llHome)) {
            pipeline = new MainlinePipeline().run(config, workspace);
        }

        assertTrue(pipeline.successful(), pipeline.diagnostics().toString());
        var differential = new DifferentialHarness().compareOriginalToOutputJar(
                inputJar,
                pipeline.outputJar(),
                ProtectedExceptionFlowFixture.MAIN_CLASS);
        assertEquals(0, differential.originalRun().exitCode(), differential.originalRun().stderr());
        assertEquals(0, differential.outputRun().exitCode(), differential.outputRun().stderr());
        assertEquals(differential.originalRun().stdout(), differential.outputRun().stdout());
        assertEquals(ProtectedExceptionFlowFixture.expectedOutput(), differential.outputRun().stdout());

        String lowering = Files.readString(workspace.resolve("reports/lowering-report.json"));
        assertFalse(lowering.contains("\"status\": \"skipped\""), lowering);
        assertEquals(4, occurrences(
                lowering,
                "\"nativeImplementationPath\": \"LLVM_NATIVE_PATH\""));
        JsonObject skipped = JsonParser.parseString(Files.readString(
                        workspace.resolve("reports/skipped-method-report.json")))
                .getAsJsonObject();
        assertEquals(0, skipped.getAsJsonArray("entries").size(), skipped.toString());

        String llvm = emittedLlvm(workspace);
        assertTrue(llvm.contains("call ptr @j2ll_rt_pending_exception("), llvm);
        assertTrue(llvm.contains("call void @j2ll_rt_clear_exception("), llvm);
        assertTrue(llvm.contains("call i32 @j2ll_rt_instanceof("), llvm);
        assertTrue(llvm.contains("call void @j2ll_rt_rethrow("), llvm);

        String generatedC = generatedC(workspace);
        assertTrue(generatedC.contains("j2ll_rt_pending_exception"), generatedC);
        assertTrue(generatedC.contains("j2ll_rt_clear_exception"), generatedC);
        assertTrue(generatedC.contains("j2ll_rt_rethrow"), generatedC);
        assertTrue(generatedC.contains("ExceptionOccurred"), generatedC);
        assertTrue(generatedC.contains("ExceptionClear"), generatedC);
    }

    private ResolvedConfig config(Path inputJar) {
        String selectors = ProtectedExceptionFlowFixture.selectors().stream()
                .map(selector -> "\"" + selector + "\"")
                .collect(Collectors.joining(", "));
        JsonObject json = JsonParser.parseString("""
                {
                  "schemaVersion": 1,
                  "jarFile": "%s",
                  "classPath": [],
                  "javaHome": null,
                  "runtimeImage": null,
                  "worldModel": "PARTIAL_WORLD",
                  "outputDirectory": "out",
                  "whiteList": [%s],
                  "blackList": [],
                  "target": %s,
                  "embeddedLibraryDirectory": "native0",
                  "signaturePolicy": "fail",
                  "signing": null,
                  "intermediates": {
                    "enabled": true,
                    "includeDebugDumps": true,
                    "includePerClassIr": true,
                    "includePerClassLlvm": true,
                    "includePerClassC": true
                  },
                  "protection": {
                    "enabled": true,
                    "seed": "protected-exception-flow-test",
                    "ir": {
                      "enabled": true,
                      "controlFlowFlattening": true,
                      "fakeBranches": true,
                      "basicBlockSplitting": true,
                      "constantEncryption": true,
                      "stringEncryption": true,
                      "methodInlining": true,
                      "methodSplitting": true,
                      "callIndirection": true,
                      "fieldInternalization": false,
                      "methodInternalization": false,
                      "publicMethodInternalizationAllowList": [],
                      "methodTableHiding": true,
                      "blockNameObfuscation": true
                    },
                    "llvm": {
                      "enabled": true,
                      "nameObfuscation": true,
                      "opaquePredicates": true,
                      "blockLayoutPerturbation": true,
                      "indirectCalls": true,
                      "globalLayout": true
                    },
                    "binary": {
                      "enabled": true,
                      "hideInternalSymbols": true,
                      "strip": true,
                      "removePdb": true,
                      "symbolAudit": true
                    }
                  }
                }
                """.formatted(
                inputJar.toString().replace("\\", "\\\\"),
                selectors,
                hostTargetJson())).getAsJsonObject();
        return new ConfigLoader().load(json, temp).config().orElseThrow();
    }

    private String emittedLlvm(Path workspace) throws Exception {
        try (var files = Files.list(workspace.resolve("native/zig-workspace/llvm"))) {
            return files.filter(path -> path.getFileName().toString().endsWith(".ll"))
                    .sorted()
                    .map(this::read)
                    .collect(Collectors.joining("\n"));
        }
    }

    private String generatedC(Path workspace) throws Exception {
        try (var files = Files.list(workspace.resolve("native/zig-workspace/jni"))) {
            return files.filter(path -> path.getFileName().toString().endsWith(".c"))
                    .sorted()
                    .map(this::read)
                    .collect(Collectors.joining("\n"));
        }
    }

    private String read(Path path) {
        try {
            return Files.readString(path);
        } catch (java.io.IOException exception) {
            throw new java.io.UncheckedIOException(exception);
        }
    }

    private String hostTargetJson() {
        TargetTriple target = HostPlatform.detect().orElseThrow().target();
        return """
                {
                  "windowsX64": %s,
                  "windowsArm64": %s,
                  "linuxX64": %s,
                  "linuxArm64": %s,
                  "macosX64": %s,
                  "macosArm64": %s
                }
                """.formatted(
                target == TargetTriple.WINDOWS_X64,
                target == TargetTriple.WINDOWS_ARM64,
                target == TargetTriple.LINUX_X64,
                target == TargetTriple.LINUX_ARM64,
                target == TargetTriple.MACOS_X64,
                target == TargetTriple.MACOS_ARM64);
    }

    private Path realJ2llHome() {
        String configured = System.getProperty("j2ll.realHome");
        if (configured == null || configured.isBlank()) {
            configured = System.getenv("J2LL_REAL_HOME");
        }
        return configured == null || configured.isBlank()
                ? null
                : Path.of(configured).toAbsolutePath().normalize();
    }

    private Path zigExecutable(Path home) {
        return home.resolve("zig").resolve(isWindows() ? "zig.exe" : "zig");
    }

    private AutoCloseable useJ2llHome(Path home) {
        String previous = System.getProperty(J2llHomeResolver.OVERRIDE_PROPERTY);
        System.setProperty(J2llHomeResolver.OVERRIDE_PROPERTY, home.toString());
        return () -> {
            if (previous == null) {
                System.clearProperty(J2llHomeResolver.OVERRIDE_PROPERTY);
            } else {
                System.setProperty(J2llHomeResolver.OVERRIDE_PROPERTY, previous);
            }
        };
    }

    private boolean isWindows() {
        return System.getProperty("os.name", "")
                .toLowerCase(Locale.ROOT)
                .contains("win");
    }

    private int occurrences(String text, String needle) {
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(needle, index)) >= 0) {
            count++;
            index += needle.length();
        }
        return count;
    }
}
