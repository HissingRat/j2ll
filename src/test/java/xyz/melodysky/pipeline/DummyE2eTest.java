package xyz.melodysky.pipeline;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import xyz.melodysky.config.ConfigLoader;
import xyz.melodysky.config.ResolvedConfig;
import xyz.melodysky.testsupport.FakeManagedZig;
import xyz.melodysky.testsupport.dummy.DummyReportAsserter;
import xyz.melodysky.toolchain.HostPlatform;
import xyz.melodysky.toolchain.TargetTriple;

class DummyE2eTest {
    @TempDir
    Path temp;

    @Test
    void dummyBasicRunsThroughJ2ll() throws Exception {
        runProfile("basic", basicSelectors(), List.of());
    }

    @Test
    void dummyAdvancedRunsThroughJ2ll() throws Exception {
        runProfile("advanced", advancedSelectors(), advancedReasons());
    }

    @Test
    void dummyAllRunsThroughJ2ll() throws Exception {
        ArrayList<String> selectors = new ArrayList<>();
        selectors.addAll(basicSelectors());
        selectors.addAll(advancedSelectors());
        runProfile("all", selectors, advancedReasons());
    }

    private void runProfile(String profile, List<String> selectors, List<String> expectedReasonCodes)
            throws Exception {
        ArrayList<String> failures = new ArrayList<>();
        Path inputJar = dummyJar();
        ChildRun original = runJar(inputJar, profile);
        collectRuntimeFailures("original", original, failures);

        Path workspace = temp.resolve("workspace-" + profile);
        MainlinePipelineResult pipeline;
        try (AutoCloseable ignored = FakeManagedZig.installAndUse(temp.resolve("j2ll-home-" + profile))) {
            pipeline = new MainlinePipeline().run(config(inputJar, selectors), workspace);
        }
        if (!pipeline.successful()) {
            failures.add("pipeline: build failed: " + pipeline.diagnostics());
        }
        if (pipeline.outputJar() == null || !Files.isRegularFile(pipeline.outputJar())) {
            failures.add("pipeline: output jar missing");
        }

        ChildRun output = null;
        if (pipeline.successful() && pipeline.outputJar() != null && Files.isRegularFile(pipeline.outputJar())) {
            output = runJar(pipeline.outputJar(), profile);
            collectRuntimeFailures("output", output, failures);
            compare(original, output, failures);
        }
        if (pipeline.outputJar() != null) {
            DummyReportAsserter.assertProfile(profile, workspace, pipeline.outputJar(), expectedReasonCodes, failures);
        }
        assertTrue(failures.isEmpty(), failureSummary(profile, original, output, workspace, failures));
    }

    private Path dummyJar() {
        String configured = System.getProperty("j2ll.dummy.jar");
        Path jar = configured == null || configured.isBlank()
                ? Path.of("build/dummy/Dummy.jar")
                : Path.of(configured);
        return jar.toAbsolutePath().normalize();
    }

    private void collectRuntimeFailures(String side, ChildRun run, List<String> failures) {
        if (run.exitCode() != 0) {
            failures.add(side + ": exit code " + run.exitCode());
        }
        if (!run.stderr().isEmpty()) {
            failures.add(side + ": stderr was not empty: " + run.stderr().trim());
        }
        run.stdout().lines()
                .filter(line -> line.contains("=FAIL:") || line.contains("GROUP ") && line.endsWith(" FAIL"))
                .forEach(line -> failures.add(side + ": " + line));
    }

    private void compare(ChildRun original, ChildRun output, List<String> failures) {
        if (original.exitCode() != output.exitCode()) {
            failures.add("differential: exit code differs original="
                    + original.exitCode() + " output=" + output.exitCode());
        }
        if (!original.stdout().equals(output.stdout())) {
            failures.add("differential: stdout differs");
        }
        if (!original.stderr().equals(output.stderr())) {
            failures.add("differential: stderr differs");
        }
    }

    private String failureSummary(
            String profile,
            ChildRun original,
            ChildRun output,
            Path workspace,
            List<String> failures) {
        StringBuilder builder = new StringBuilder();
        builder.append("Dummy ").append(profile).append(" failed:\n");
        for (String failure : failures) {
            builder.append("- ").append(failure).append('\n');
        }
        builder.append("\nworkspace=").append(workspace).append('\n');
        builder.append("\noriginal stdout:\n").append(original.stdout());
        builder.append("\noriginal stderr:\n").append(original.stderr());
        if (output != null) {
            builder.append("\noutput stdout:\n").append(output.stdout());
            builder.append("\noutput stderr:\n").append(output.stderr());
        }
        return builder.toString();
    }

    private ResolvedConfig config(Path inputJar, List<String> selectors) {
        JsonObject json = JsonParser.parseString(baseJson(inputJar, selectors)).getAsJsonObject();
        return new ConfigLoader().load(json, temp).config().orElseThrow();
    }

    private String baseJson(Path inputJar, List<String> selectors) {
        String selectorJson = selectors.stream()
                .map(selector -> "\"" + selector + "\"")
                .collect(java.util.stream.Collectors.joining(", "));
        return """
                {
                  "schemaVersion": 1,
                  "jarFile": "%s",
                  "classPath": [],
                  "javaHome": null,
                  "runtimeImage": null,
                  "worldModel": "PARTIAL_WORLD",
                  "javaSupportTier": "TIER_5",
                  "fallbackMode": "nativeEmbeddedClassBlob",
                  "outputDirectory": "out",
                  "whiteList": [%s],
                  "blackList": [],
                  "target": %s,
                  "libraryName": "j2lldummy",
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
                    "seed": "dummy-secret-seed",
                    "intensity": "normal",
                    "ir": {
                      "enabled": true,
                      "controlFlowFlattening": { "enabled": true, "intensity": "normal" },
                      "fakeBranches": { "enabled": true, "intensity": "normal" },
                      "basicBlockSplitting": { "enabled": true, "intensity": "normal" },
                      "constantEncryption": { "enabled": true, "intensity": "normal" },
                      "stringEncryption": { "enabled": true, "intensity": "normal", "cacheStrings": false },
                      "methodInlining": { "enabled": true, "intensity": "normal" },
                      "methodSplitting": { "enabled": true, "intensity": "normal" },
                      "callIndirection": { "enabled": true, "intensity": "normal" },
                      "methodTableHiding": { "enabled": true, "intensity": "normal" }
                    },
                    "llvm": {
                      "enabled": true,
                      "nameObfuscation": { "enabled": true, "intensity": "normal" },
                      "opaquePredicates": { "enabled": true, "intensity": "normal" },
                      "blockLayoutPerturbation": { "enabled": true, "intensity": "normal" },
                      "indirectCalls": { "enabled": true, "intensity": "normal" },
                      "globalLayout": { "enabled": true, "intensity": "normal" },
                      "visibilityHardening": { "enabled": true }
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
                """.formatted(inputJar.toString().replace("\\", "\\\\"), selectorJson, hostTargetJson());
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
                }""".formatted(
                target == TargetTriple.WINDOWS_X64,
                target == TargetTriple.WINDOWS_ARM64,
                target == TargetTriple.LINUX_X64,
                target == TargetTriple.LINUX_ARM64,
                target == TargetTriple.MACOS_X64,
                target == TargetTriple.MACOS_ARM64);
    }

    private List<String> advancedReasons() {
        return List.of(
                "REFLECTION_UNSUPPORTED_SCAN",
                "METHOD_HANDLE_CHAIN_FALLBACK",
                "METHOD_HANDLE_PERMUTE_FALLBACK",
                "METHOD_HANDLE_FILTER_FALLBACK",
                "METHOD_HANDLE_FOLD_FALLBACK",
                "METHOD_HANDLE_COLLECTOR_UNSUPPORTED",
                "UNSAFE_RAW_MEMORY_FALLBACK",
                "WAIT_NOTIFY_FALLBACK",
                "UNSUPPORTED_DEFAULT_INTERFACE_SUPER");
    }

    private List<String> basicSelectors() {
        return List.of(
                "zoo/basic/PrimitiveBasicCase#simpleInt!(II)I",
                "zoo/basic/PrimitiveBasicCase#longMath!(JJ)J",
                "zoo/basic/PrimitiveBasicCase#lessThan!(II)Z",
                "zoo/basic/PrimitiveBasicCase#floatValue!()F",
                "zoo/basic/PrimitiveBasicCase#doubleValue!()D",
                "zoo/basic/ArrayBasicCase#run!()Ljava/lang/String;",
                "zoo/basic/ControlFlowBasicCase#negate!(I)I",
                "zoo/basic/ControlFlowBasicCase#table!(I)I",
                "zoo/basic/ControlFlowBasicCase#lookup!(I)I",
                "zoo/basic/ExceptionBasicCase#catchCode!()I",
                "zoo/basic/StringJdkBasicCase#stableStringOps!()Ljava/lang/String;",
                "zoo/basic/InterfaceLambdaConcatBasicCase#run!()Ljava/lang/String;",
                "zoo/basic/ReflectionBasicCase#run!()Ljava/lang/String;");
    }

    private List<String> advancedSelectors() {
        return List.of(
                "zoo/advanced/ReflectionAdvancedCase#run!()Ljava/lang/String;",
                "zoo/advanced/MethodHandleAdvancedCase#methodHandleBoundary!()Ljava/lang/String;",
                "zoo/advanced/UnsafeVarHandleAdvancedCase#run!()Ljava/lang/String;",
                "zoo/advanced/ThreadMonitorAdvancedCase#run!()Ljava/lang/String;",
                "zoo/advanced/InterfaceBoundaryAdvancedCase#run!()Ljava/lang/String;",
                "zoo/advanced/InterfaceBoundaryAdvancedCase$SuperCall#call!()Ljava/lang/String;",
                "zoo/advanced/ComplexFinallyBoundaryCase#run!()Ljava/lang/String;",
                "zoo/advanced/AnnotationEnumRecordAdvancedCase#run!()Ljava/lang/String;");
    }

    private ChildRun runJar(Path jar, String mode) throws IOException, InterruptedException {
        ArrayList<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home")).resolve("bin").resolve(javaBinary()).toString());
        command.add("-Duser.language=en");
        command.add("-Duser.country=US");
        command.add("-Dfile.encoding=UTF-8");
        command.add("--enable-native-access=ALL-UNNAMED");
        command.add("--sun-misc-unsafe-memory-access=allow");
        command.add("-jar");
        command.add(jar.toString());
        command.add(mode);
        Process process = new ProcessBuilder(command)
                .redirectErrorStream(false)
                .start();
        boolean exited = process.waitFor(20, TimeUnit.SECONDS);
        if (!exited) {
            process.destroyForcibly();
            throw new IOException("Dummy JVM timed out: " + command);
        }
        return new ChildRun(
                process.exitValue(),
                new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8),
                new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8));
    }

    private String javaBinary() {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")
                ? "java.exe"
                : "java";
    }

    private record ChildRun(int exitCode, String stdout, String stderr) {}
}
