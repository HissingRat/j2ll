package xyz.melodysky.pipeline;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
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

        Path workspace = workspace(profile);
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
        LoweringSummary loweringSummary = LoweringSummary.read(
                workspace.resolve("reports/lowering-report.json"),
                pipeline.outputJar());
        System.out.print(loweringSummary.format(profile));
        if (pipeline.outputJar() != null) {
            System.out.println("Dummy j2ll output jar [" + profile + "]: "
                    + pipeline.outputJar().toAbsolutePath().normalize());
        }
        assertTrue(failures.isEmpty(), failureSummary(profile, original, output, workspace, loweringSummary, failures));
    }

    private Path dummyJar() {
        String configured = System.getProperty("j2ll.dummy.jar");
        Path jar = configured == null || configured.isBlank()
                ? Path.of("build/dummy/Dummy.jar")
                : Path.of(configured);
        return jar.toAbsolutePath().normalize();
    }

    private Path workspace(String profile) throws IOException {
        String configured = System.getProperty("j2ll.dummy.workspaceRoot");
        Path workspace = configured == null || configured.isBlank()
                ? temp.resolve("workspace-" + profile)
                : Path.of(configured).toAbsolutePath().normalize().resolve(profile);
        deleteRecursively(workspace);
        Files.createDirectories(workspace);
        return workspace;
    }

    private void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        try (var walk = Files.walk(path)) {
            for (Path entry : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(entry);
            }
        }
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
            LoweringSummary loweringSummary,
            List<String> failures) {
        StringBuilder builder = new StringBuilder();
        builder.append("Dummy ").append(profile).append(" failed:\n");
        for (String failure : failures) {
            builder.append("- ").append(failure).append('\n');
        }
        builder.append("\nworkspace=").append(workspace).append('\n');
        builder.append('\n').append(loweringSummary.format(profile));
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
                "zoo/basic/PolymorphismBasicCase#virtualDispatch!()Ljava/lang/String;",
                "zoo/basic/PolymorphismBasicCase#abstractDispatch!()Ljava/lang/String;",
                "zoo/basic/PolymorphismBasicCase#superDispatch!()Ljava/lang/String;",
                "zoo/basic/PolymorphismBasicCase#bridgeDispatch!()Ljava/lang/String;",
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
                "zoo/advanced/AnnotationEnumRecordAdvancedCase#run!()Ljava/lang/String;",
                "zoo/advanced/JdkSurfaceAdvancedCase#resourceBundle!()Ljava/lang/String;",
                "zoo/advanced/JdkSurfaceAdvancedCase#localeFormat!()Ljava/lang/String;",
                "zoo/advanced/JdkSurfaceAdvancedCase#moduleApi!()Ljava/lang/String;");
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

    private record LoweringSummary(
            boolean available,
            Map<String, Integer> statusCounts,
            Map<String, Map<String, Integer>> reasonCounts,
            Map<String, List<MethodSummary>> methodsByStatus,
            String unavailableReason) {
        private static final List<String> STATUS_ORDER = List.of(
                "lowered",
                "halfLowered",
                "frontendSkipped",
                "notApplicable",
                "failed",
                "excluded");

        static LoweringSummary read(Path loweringReport, Path outputJar) {
            if (!Files.isRegularFile(loweringReport)) {
                return unavailable("missing " + loweringReport);
            }
            try {
                NativeMethodIndex nativeMethods = NativeMethodIndex.read(outputJar);
                JsonObject root = JsonParser.parseString(Files.readString(loweringReport)).getAsJsonObject();
                Map<String, Integer> statuses = new LinkedHashMap<>();
                for (String status : STATUS_ORDER) {
                    statuses.put(status, 0);
                }
                Map<String, Map<String, Integer>> reasons = new LinkedHashMap<>();
                for (String status : STATUS_ORDER) {
                    reasons.put(status, new TreeMap<>());
                }
                Map<String, List<MethodSummary>> methodsByStatus = new LinkedHashMap<>();
                for (String status : STATUS_ORDER) {
                    methodsByStatus.put(status, new ArrayList<>());
                }
                countRequested(root.getAsJsonArray("requestedMethods"), statuses, reasons, methodsByStatus, nativeMethods);
                countEligibility(
                        root.getAsJsonArray("notApplicable"),
                        "notApplicable",
                        statuses,
                        reasons,
                        methodsByStatus,
                        nativeMethods);
                countEligibility(
                        root.getAsJsonArray("excluded"),
                        "excluded",
                        statuses,
                        reasons,
                        methodsByStatus,
                        nativeMethods);
                return new LoweringSummary(true, statuses, reasons, methodsByStatus, "");
            } catch (Exception exception) {
                return unavailable(exception.getMessage());
            }
        }

        private static LoweringSummary unavailable(String reason) {
            return new LoweringSummary(false, Map.of(), Map.of(), Map.of(), reason);
        }

        private static void countRequested(
                JsonArray methods,
                Map<String, Integer> statuses,
                Map<String, Map<String, Integer>> reasons,
                Map<String, List<MethodSummary>> methodsByStatus,
                NativeMethodIndex nativeMethods) {
            if (methods == null) {
                return;
            }
            for (JsonElement element : methods) {
                JsonObject method = element.getAsJsonObject();
                String status = string(method, "status", "unknown");
                increment(statuses, status);
                addReason(reasons, status, string(method, "reasonCode", null));
                addSiteReasons(reasons, status, method.getAsJsonArray("fallbackSites"));
                addInterestingMethod(methodsByStatus, status, method, nativeMethods);
            }
        }

        private static void countEligibility(
                JsonArray methods,
                String defaultStatus,
                Map<String, Integer> statuses,
                Map<String, Map<String, Integer>> reasons,
                Map<String, List<MethodSummary>> methodsByStatus,
                NativeMethodIndex nativeMethods) {
            if (methods == null) {
                return;
            }
            for (JsonElement element : methods) {
                JsonObject method = element.getAsJsonObject();
                String status = string(method, "status", defaultStatus);
                increment(statuses, status);
                addReason(reasons, status, string(method, "reasonCode", null));
                addInterestingMethod(methodsByStatus, status, method, nativeMethods);
            }
        }

        private static void addSiteReasons(
                Map<String, Map<String, Integer>> reasons,
                String status,
                JsonArray sites) {
            if (sites == null) {
                return;
            }
            for (JsonElement element : sites) {
                addReason(reasons, status, string(element.getAsJsonObject(), "reasonCode", null));
            }
        }

        private static void addInterestingMethod(
                Map<String, List<MethodSummary>> methodsByStatus,
                String status,
                JsonObject method,
                NativeMethodIndex nativeMethods) {
            if (!"lowered".equals(status)
                    && !"halfLowered".equals(status)
                    && !"frontendSkipped".equals(status)) {
                return;
            }
            methodsByStatus.computeIfAbsent(status, ignored -> new ArrayList<>())
                    .add(methodSummary(method, status, nativeMethods));
        }

        private static MethodSummary methodSummary(JsonObject method, String status, NativeMethodIndex nativeMethods) {
            String key = string(method, "class", "?")
                    + "#"
                    + string(method, "method", "?")
                    + string(method, "descriptor", "");
            TreeMap<String, Integer> reasons = new TreeMap<>();
            addReasonTo(reasons, string(method, "reasonCode", null));
            JsonArray fallbackSites = method.getAsJsonArray("fallbackSites");
            if (fallbackSites != null) {
                for (JsonElement site : fallbackSites) {
                    addReasonTo(reasons, string(site.getAsJsonObject(), "reasonCode", null));
                }
            }
            boolean expectedNative = ("lowered".equals(status) || "halfLowered".equals(status))
                    && "nativeOriginal".equals(string(method, "rewriteStrategy", null));
            boolean actualNative = nativeMethods.contains(key);
            return new MethodSummary(key, reasons, expectedNative, actualNative, nativeMethods.available());
        }

        private static void addReasonTo(Map<String, Integer> reasons, String reason) {
            if (reason == null || reason.isBlank()) {
                return;
            }
            reasons.merge(reason, 1, Integer::sum);
        }

        private static void addReason(Map<String, Map<String, Integer>> reasons, String status, String reason) {
            if (reason == null || reason.isBlank()) {
                return;
            }
            reasons.computeIfAbsent(status, ignored -> new TreeMap<>()).merge(reason, 1, Integer::sum);
        }

        private static void increment(Map<String, Integer> counts, String key) {
            counts.merge(key, 1, Integer::sum);
        }

        private static String string(JsonObject object, String name, String fallback) {
            JsonElement element = object.get(name);
            if (element == null || element.isJsonNull()) {
                return fallback;
            }
            return element.getAsString();
        }

        String format(String profile) {
            StringBuilder builder = new StringBuilder();
            builder.append("Dummy j2ll lowering summary [").append(profile).append("]\n");
            if (!available) {
                builder.append("  unavailable: ").append(unavailableReason).append('\n');
                return builder.toString();
            }
            builder.append("  status:");
            for (String status : STATUS_ORDER) {
                builder.append(' ').append(status).append('=').append(statusCounts.getOrDefault(status, 0));
            }
            statusCounts.entrySet().stream()
                    .filter(entry -> !STATUS_ORDER.contains(entry.getKey()))
                    .forEach(entry -> builder.append(' ')
                            .append(entry.getKey())
                            .append('=')
                            .append(entry.getValue()));
            builder.append('\n');
            appendReasons(builder, "halfLowered");
            appendReasons(builder, "frontendSkipped");
            appendReasons(builder, "notApplicable");
            appendReasons(builder, "failed");
            appendReasons(builder, "excluded");
            appendLoweredMismatches(builder);
            appendMethods(builder, "halfLowered");
            appendMethods(builder, "frontendSkipped");
            return builder.toString();
        }

        private void appendReasons(StringBuilder builder, String status) {
            Map<String, Integer> counts = reasonCounts.getOrDefault(status, Map.of());
            if (counts.isEmpty()) {
                return;
            }
            builder.append("  ").append(status).append(" reasons:");
            counts.forEach((reason, count) -> builder.append(' ')
                    .append(reason)
                    .append('=')
                    .append(count));
            builder.append('\n');
        }

        private void appendMethods(StringBuilder builder, String status) {
            List<MethodSummary> methods = methodsByStatus.getOrDefault(status, List.of());
            if (methods.isEmpty()) {
                return;
            }
            builder.append("  ").append(status).append(" methods:\n");
            for (MethodSummary method : methods) {
                builder.append("    ").append(method.marker()).append(' ').append(method.key());
                if (!method.reasons().isEmpty()) {
                    builder.append(" [");
                    boolean first = true;
                    for (Map.Entry<String, Integer> entry : method.reasons().entrySet()) {
                        if (!first) {
                            builder.append(", ");
                        }
                        builder.append(entry.getKey());
                        if (entry.getValue() > 1) {
                            builder.append('=').append(entry.getValue());
                        }
                        first = false;
                    }
                    builder.append(']');
                }
                builder.append('\n');
            }
        }

        private void appendLoweredMismatches(StringBuilder builder) {
            List<MethodSummary> mismatches = methodsByStatus.getOrDefault("lowered", List.of()).stream()
                    .filter(method -> !method.expectedMatchesActual())
                    .toList();
            if (mismatches.isEmpty()) {
                return;
            }
            builder.append("  lowered native mismatches:\n");
            for (MethodSummary method : mismatches) {
                builder.append("    ! ")
                        .append(method.key())
                        .append(" [expected=")
                        .append(method.expectedNativeText())
                        .append(" actual=")
                        .append(method.actualNativeText())
                        .append("]\n");
            }
        }
    }

    private record MethodSummary(
            String key,
            Map<String, Integer> reasons,
            boolean expectedNative,
            boolean actualNative,
            boolean actualKnown) {
        boolean expectedMatchesActual() {
            return actualKnown && expectedNative == actualNative;
        }

        String marker() {
            return expectedMatchesActual() ? "-" : "!";
        }

        String expectedNativeText() {
            return expectedNative ? "native" : "bytecode";
        }

        String actualNativeText() {
            if (!actualKnown) {
                return "unknown";
            }
            return actualNative ? "native" : "bytecode";
        }
    }

    private record NativeMethodIndex(boolean available, Set<String> nativeMethods) {
        static NativeMethodIndex read(Path outputJar) throws IOException {
            if (outputJar == null || !Files.isRegularFile(outputJar)) {
                return new NativeMethodIndex(false, Set.of());
            }
            HashSet<String> methods = new HashSet<>();
            try (JarFile jar = new JarFile(outputJar.toFile(), false)) {
                var entries = jar.entries();
                while (entries.hasMoreElements()) {
                    var entry = entries.nextElement();
                    if (!entry.getName().endsWith(".class") || entry.getName().equals("module-info.class")) {
                        continue;
                    }
                    try (var stream = jar.getInputStream(entry)) {
                        ClassReader reader = new ClassReader(stream);
                        reader.accept(new ClassVisitor(Opcodes.ASM9) {
                            private String owner;

                            @Override
                            public void visit(
                                    int version,
                                    int access,
                                    String name,
                                    String signature,
                                    String superName,
                                    String[] interfaces) {
                                owner = name;
                            }

                            @Override
                            public MethodVisitor visitMethod(
                                    int access,
                                    String name,
                                    String descriptor,
                                    String signature,
                                    String[] exceptions) {
                                if ((access & Opcodes.ACC_NATIVE) != 0) {
                                    methods.add(owner + "#" + name + descriptor);
                                }
                                return null;
                            }
                        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                    }
                }
            }
            return new NativeMethodIndex(true, methods);
        }

        boolean contains(String key) {
            return available && nativeMethods.contains(key);
        }
    }
}
