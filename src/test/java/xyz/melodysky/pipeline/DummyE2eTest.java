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
import xyz.melodysky.protection.audit.HashOnlyEvidence;
import xyz.melodysky.testsupport.FakeManagedZig;
import xyz.melodysky.testsupport.dummy.DummyReportAsserter;
import xyz.melodysky.toolchain.HostPlatform;
import xyz.melodysky.toolchain.J2llHomeResolver;
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
        try (AutoCloseable ignored = useManagedZig(profile)) {
            pipeline = new MainlinePipeline().run(
                    config(inputJar, selectors),
                    workspace,
                    xyz.melodysky.progress.BuildProgressListener.none(),
                    xyz.melodysky.analysis.world.WholeProgramAnalysisPolicy.strict(),
                    SkippedMethodApproval.allowAll());
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
        if (profile.equals("basic") || profile.equals("all")) {
            assertBigEndianIntFrameIntrinsicEvidence(
                    workspace.resolve("reports/lowering-report.json"),
                    failures);
            assertControlFlowFlatteningEvidence(
                    workspace.resolve("reports/protection-report.json"),
                    failures,
                    List.of(
                            "zoo/basic/ControlFlowBasicCase#regionAroundOwnedBoundary!(I[Ljava/lang/String;)Ljava/lang/String;",
                            "zoo/basic/ControlFlowBasicCase#regionAroundTypedCatch!(II)I"));
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
                    "seed": "dummy-secret-seed",
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
                      "symbolAudit": true,
                      "retainUnwindInfo": false
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
                "zoo/basic/ControlFlowBasicCase#regionAroundOwnedBoundary!(I[Ljava/lang/String;)Ljava/lang/String;",
                "zoo/basic/ControlFlowBasicCase#regionAroundTypedCatch!(II)I",
                "zoo/basic/ExceptionBasicCase#catchCode!()I",
                "zoo/basic/StringJdkBasicCase#stableStringOps!()Ljava/lang/String;",
                "zoo/basic/StringJdkBasicCase#bigEndianIntFrame!(I)[B",
                "zoo/basic/InterfaceLambdaConcatBasicCase#run!()Ljava/lang/String;",
                "zoo/basic/PolymorphismBasicCase#virtualDispatch!()Ljava/lang/String;",
                "zoo/basic/PolymorphismBasicCase#abstractDispatch!()Ljava/lang/String;",
                "zoo/basic/PolymorphismBasicCase#superDispatch!()Ljava/lang/String;",
                "zoo/basic/PolymorphismBasicCase#bridgeDispatch!()Ljava/lang/String;",
                "zoo/basic/ReflectionBasicCase#run!()Ljava/lang/String;");
    }

    private void assertBigEndianIntFrameIntrinsicEvidence(
            Path loweringReport,
            List<String> failures) {
        if (!Files.isRegularFile(loweringReport)) {
            return;
        }
        try {
            JsonArray methods = JsonParser.parseString(Files.readString(loweringReport))
                    .getAsJsonObject()
                    .getAsJsonArray("requestedMethods");
            if (methods == null) {
                failures.add("reports: lowering report has no requestedMethods");
                return;
            }
            for (JsonElement element : methods) {
                JsonObject method = element.getAsJsonObject();
                if (!"zoo/basic/StringJdkBasicCase".equals(string(method, "class"))
                        || !"bigEndianIntFrame".equals(string(method, "method"))
                        || !"(I)[B".equals(string(method, "descriptor"))) {
                    continue;
                }
                if (!"nativeLowered".equals(string(method, "status"))) {
                    failures.add("reports: bigEndianIntFrame was not nativeLowered");
                    return;
                }
                JsonArray sites = method.getAsJsonArray("helperBackedSites");
                if (sites != null
                        && java.util.stream.StreamSupport.stream(sites.spliterator(), false)
                                .map(JsonElement::getAsJsonObject)
                                .map(site -> string(site, "reasonCode"))
                                .anyMatch("JDK_INTRINSIC_HELPER"::equals)) {
                    return;
                }
                failures.add("reports: bigEndianIntFrame has no JDK_INTRINSIC_HELPER evidence");
                return;
            }
            failures.add("reports: bigEndianIntFrame is missing from lowering report");
        } catch (Exception exception) {
            failures.add("reports: failed to inspect bigEndianIntFrame evidence: "
                    + exception.getMessage());
        }
    }

    private void assertControlFlowFlatteningEvidence(
            Path protectionReport,
            List<String> failures,
            List<String> methodKeys) {
        if (!Files.isRegularFile(protectionReport)) {
            failures.add("reports: protection report is missing");
            return;
        }
        try {
            JsonObject root = JsonParser.parseString(Files.readString(protectionReport))
                    .getAsJsonObject();
            JsonObject coverage = root.getAsJsonObject("coverage");
            JsonArray facts = coverage == null ? null : coverage.getAsJsonArray("facts");
            if (facts == null) {
                failures.add("reports: protection report has no coverage facts");
                return;
            }
            for (String methodKey : methodKeys) {
                String subjectIdentityHash = HashOnlyEvidence.sha256(
                        "protection-report-method-subject",
                        methodKey);
                List<JsonObject> matches = java.util.stream.StreamSupport.stream(
                                facts.spliterator(),
                                false)
                        .map(JsonElement::getAsJsonObject)
                        .filter(fact -> "IR".equals(string(fact, "layer")))
                        .filter(fact -> "CONTROL_FLOW_FLATTENING".equals(string(fact, "passName")))
                        .filter(fact -> subjectIdentityHash.equals(string(fact, "subjectIdentityHash")))
                        .toList();
                if (matches.size() != 1) {
                    failures.add("reports: expected exactly one CFF coverage fact for "
                            + methodKey + " but found " + matches.size());
                    continue;
                }
                JsonObject fact = matches.get(0);
                if (!"RAN".equals(string(fact, "status"))) {
                    failures.add("reports: CFF status for " + methodKey + " was "
                            + string(fact, "status") + " instead of RAN");
                }
                if (!fact.has("affected") || !fact.get("affected").getAsBoolean()) {
                    failures.add("reports: CFF did not affect " + methodKey);
                }
                if (!"applicable".equals(string(fact, "applicability"))) {
                    failures.add("reports: CFF applicability for " + methodKey + " was "
                            + string(fact, "applicability") + " instead of applicable");
                }
                if (!"CONTROL_FLOW_FLATTENING".equals(string(fact, "reasonCode"))) {
                    failures.add("reports: CFF reason for " + methodKey + " was "
                            + string(fact, "reasonCode"));
                }
            }
        } catch (Exception exception) {
            failures.add("reports: failed to inspect CFF coverage evidence: "
                    + exception.getMessage());
        }
    }

    private String string(JsonObject object, String name) {
        JsonElement element = object.get(name);
        return element == null || element.isJsonNull() ? null : element.getAsString();
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
        return isWindows()
                ? "java.exe"
                : "java";
    }

    private AutoCloseable useManagedZig(String profile) throws Exception {
        Path realHome = realJ2llHome();
        if (realHome != null && Files.isRegularFile(zigExecutable(realHome))) {
            return useJ2llHome(realHome);
        }
        return FakeManagedZig.installAndUse(temp.resolve("j2ll-home-" + profile));
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
                .toLowerCase(java.util.Locale.ROOT)
                .contains("win");
    }

    private record ChildRun(int exitCode, String stdout, String stderr) {}

    private record LoweringSummary(
            boolean available,
            Map<String, Integer> statusCounts,
            Map<String, Map<String, Integer>> reasonCounts,
            Map<String, List<MethodSummary>> methodsByStatus,
            String unavailableReason) {
        private static final List<String> STATUS_ORDER = List.of(
                "nativeLowered",
                "skipped",
                "ineligible",
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
                        root.getAsJsonArray("ineligible"),
                        "ineligible",
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
                addSiteReasons(reasons, status, method.getAsJsonArray("helperBackedSites"));
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
            if (!"nativeLowered".equals(status)
                    && !"skipped".equals(status)) {
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
            JsonArray helperBackedSites = method.getAsJsonArray("helperBackedSites");
            if (helperBackedSites != null) {
                for (JsonElement site : helperBackedSites) {
                    addReasonTo(reasons, string(site.getAsJsonObject(), "reasonCode", null));
                }
            }
            boolean expectedNative = "nativeLowered".equals(status)
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
            appendReasons(builder, "skipped");
            appendReasons(builder, "ineligible");
            appendReasons(builder, "excluded");
            appendLoweredMismatches(builder);
            appendMethods(builder, "skipped");
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
            List<MethodSummary> mismatches = methodsByStatus.getOrDefault("nativeLowered", List.of()).stream()
                    .filter(method -> !method.expectedMatchesActual())
                    .toList();
            if (mismatches.isEmpty()) {
                return;
            }
            builder.append("  nativeLowered native mismatches:\n");
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
