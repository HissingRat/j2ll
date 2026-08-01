package xyz.melodysky.testsupport.corpus;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import xyz.melodysky.config.ConfigLoader;
import xyz.melodysky.config.ResolvedConfig;
import xyz.melodysky.cli.J2llCli;
import xyz.melodysky.diagnostic.Diagnostic;
import xyz.melodysky.diagnostic.DiagnosticStage;
import xyz.melodysky.pipeline.MainlinePipeline;
import xyz.melodysky.pipeline.MainlinePipelineResult;
import xyz.melodysky.packaging.NativeRegistrationPlan;
import xyz.melodysky.testsupport.FakeManagedZig;
import xyz.melodysky.testsupport.JvmRunner;
import xyz.melodysky.toolchain.NativeBuildPlan;
import xyz.melodysky.toolchain.HostPlatform;
import xyz.melodysky.toolchain.TargetTriple;
import xyz.melodysky.toolchain.ToolchainDiagnostics;

public final class CorpusRunner {
    private static final List<String> REPORTS = List.of(
            "diagnostics.json",
            "artifact-audit.json",
            "failure-report.json",
            "index.json",
            "known-blockers.json",
            "lowering-report.json",
            "opcode-support-matrix.json",
            "packaging-report.json",
            "protection-report.json",
            "release-readiness.json",
            "skipped-method-report.json",
            "summary.json",
            "summary.md",
            "support-matrix.json",
            "symbol-audit.json");

    public CorpusRunResult run(CorpusCase corpusCase, Path root) throws Exception {
        Path inputJar = root.resolve("input").resolve(corpusCase.name() + ".jar");
        if (!corpusCase.jarEntries().isEmpty()) {
            writeJar(inputJar, corpusCase.jarEntries());
        }
        Path baselineJar = baselineJarForOriginalRun(inputJar, corpusCase, root);
        var originalRun = corpusCase.runChildDifferential()
                ? new JvmRunner().run(baselineJar, corpusCase.mainClass(), List.of())
                : null;
        Path workspace = root.resolve("workspace").resolve(corpusCase.name());
        MainlinePipelineResult pipelineResult;
        try (AutoCloseable ignored = FakeManagedZig.installAndUse(root.resolve("j2ll-home"))) {
            if (corpusCase.configJsonOverride() == null) {
                var configResult = configResult(inputJar, corpusCase);
                ResolvedConfig config = configResult.config()
                        .orElseThrow(() -> new IllegalStateException(
                                "release corpus config failed to load: " + configResult.diagnostics()));
                pipelineResult = "TOOLCHAIN".equals(corpusCase.expectedFailureStage())
                        ? runCliToolchainFailureCase(inputJar, corpusCase, workspace)
                        : new MainlinePipeline(corpusCase.environment()::get).run(config, workspace);
                if (!configResult.diagnostics().isEmpty()) {
                    ArrayList<xyz.melodysky.diagnostic.Diagnostic> diagnostics = new ArrayList<>();
                    diagnostics.addAll(configResult.diagnostics());
                    diagnostics.addAll(pipelineResult.diagnostics());
                    pipelineResult = new MainlinePipelineResult(
                            pipelineResult.workspaceRoot(),
                            pipelineResult.outputJar(),
                            diagnostics,
                            pipelineResult.nativeBuildPlan(),
                            pipelineResult.nativeRegistrationPlan(),
                            pipelineResult.successful());
                    Files.writeString(workspace.resolve("reports/diagnostics.json"),
                            new xyz.melodysky.report.ReportJsonWriter().diagnosticsJson(diagnostics));
                }
            } else {
                pipelineResult = runCliConfigCase(inputJar, corpusCase, workspace);
            }
        }
        var outputRun = corpusCase.runChildDifferential()
                        && pipelineResult.successful()
                        && Files.isRegularFile(pipelineResult.outputJar())
                ? new JvmRunner().run(pipelineResult.outputJar(), corpusCase.mainClass(), List.of())
                : null;
        return new CorpusRunResult(
                corpusCase,
                inputJar,
                pipelineResult,
                originalRun,
                outputRun,
                new CorpusReportPaths(reportPaths(pipelineResult.workspaceRoot())));
    }

    private ResolvedConfig config(Path inputJar, CorpusCase corpusCase) {
        var result = configResult(inputJar, corpusCase);
        return result.config()
                .orElseThrow(() -> new IllegalStateException(
                        "release corpus config failed to load: " + result.diagnostics()));
    }

    private xyz.melodysky.config.ConfigLoadResult configResult(Path inputJar, CorpusCase corpusCase) {
        return new ConfigLoader().load(configRoot(inputJar, corpusCase), inputJar.getParent());
    }

    private JsonObject configRoot(Path inputJar, CorpusCase corpusCase) {
        JsonObject root = JsonParser.parseString("""
                {
                  "schemaVersion": 1,
                  "jarFile": "%s",
                  "classPath": [],
                  "javaHome": null,
                  "runtimeImage": null,
                  "worldModel": "PARTIAL_WORLD",
                  "outputDirectory": "out",
                  "whiteList": %s,
                  "blackList": [],
                  "target": %s,
                  "embeddedLibraryDirectory": "native0",
                  "signaturePolicy": "%s",
                  "signing": %s,
                  "intermediates": {
                    "enabled": true,
                    "includeDebugDumps": true,
                    "includePerClassIr": true,
                    "includePerClassLlvm": true,
                    "includePerClassC": true
                  },
                  "protection": {
                    "enabled": %s,
                    "seed": "corpus-seed",
                    "ir": {
                      "enabled": %s,
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
                      "enabled": %s,
                      "nameObfuscation": true,
                      "opaquePredicates": true,
                      "blockLayoutPerturbation": true,
                      "indirectCalls": true,
                      "globalLayout": true
                    },
                    "binary": {
                      "enabled": %s,
                      "hideInternalSymbols": true,
                      "strip": true,
                      "removePdb": true,
                      "symbolAudit": true
                    }
                  }
                }
                """.formatted(
                        inputJar.toString().replace('\\', '/'),
                        selectorsJson(corpusCase.selectors()),
                        corpusCase.targetJson() == null ? targetJson() : corpusCase.targetJson(),
                        corpusCase.signaturePolicy(),
                        corpusCase.signingJson() == null ? "null" : corpusCase.signingJson(),
                        corpusCase.protectionEnabled(),
                        corpusCase.protectionEnabled(),
                        corpusCase.protectionEnabled(),
                        corpusCase.protectionEnabled()))
                .getAsJsonObject();
        addExtraTopLevelFields(root, corpusCase.extraTopLevelConfigFields());
        return root;
    }

    private MainlinePipelineResult runCliToolchainFailureCase(
            Path inputJar,
            CorpusCase corpusCase,
            Path requestedWorkspace)
            throws IOException {
        Path configPath = requestedWorkspace.resolve("config.json");
        Files.createDirectories(configPath.getParent());
        Files.writeString(configPath, configRoot(inputJar, corpusCase).toString());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int exitCode = J2llCli.run(
                new String[] {"--config", configPath.toString()},
                new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
        if (exitCode != 4) {
            throw new IllegalStateException(
                    "expected injected toolchain failure exit 4, got " + exitCode + ":\n"
                            + err.toString(StandardCharsets.UTF_8));
        }
        Path actualWorkspace = pathValue(err.toString(StandardCharsets.UTF_8), "reportsDir").getParent();
        Diagnostic diagnostic = Diagnostic.error(
                        DiagnosticStage.NATIVE_LINK,
                        ToolchainDiagnostics.ZIG_TARGET_UNBUILDABLE,
                        "test-only managed Zig did not produce every selected target artifact")
                .withDecision("failed");
        return new MainlinePipelineResult(
                actualWorkspace,
                actualWorkspace.resolve(inputJar.getFileName()),
                List.of(diagnostic),
                new NativeBuildPlan(List.of()),
                new NativeRegistrationPlan(List.of()),
                false);
    }

    private MainlinePipelineResult runCliConfigCase(Path inputJar, CorpusCase corpusCase, Path workspace)
            throws IOException {
        Path configPath = workspace.resolve("config.json");
        Files.createDirectories(configPath.getParent());
        Files.writeString(configPath, materializedConfigJson(inputJar, corpusCase.configJsonOverride()));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        J2llCli.run(
                new String[] {"--config", configPath.toString()},
                new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
        var loaded = new ConfigLoader().load(configPath);
        Path reportsDirectory = pathValue(err.toString(StandardCharsets.UTF_8), "reportsDir");
        Path actualWorkspace = reportsDirectory.getParent();
        Path outputJar = actualWorkspace.resolve("config-failed.jar");
        return new MainlinePipelineResult(
                actualWorkspace,
                outputJar,
                loaded.diagnostics(),
                new NativeBuildPlan(List.of()),
                new NativeRegistrationPlan(List.of()),
                false);
    }

    private Path pathValue(String output, String key) {
        String prefix = key + "=";
        return output.lines()
                .filter(line -> line.startsWith(prefix))
                .map(line -> Path.of(line.substring(prefix.length())))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("missing " + prefix + " in CLI output:\n" + output));
    }

    private String materializedConfigJson(Path inputJar, String configJson) {
        return configJson.replace("${INPUT_JAR}", inputJar.toString().replace('\\', '/'));
    }

    private void addExtraTopLevelFields(JsonObject root, String extraTopLevelConfigFields) {
        if (extraTopLevelConfigFields == null || extraTopLevelConfigFields.isBlank()) {
            return;
        }
        JsonObject extra = JsonParser.parseString("{" + extraTopLevelConfigFields + "}").getAsJsonObject();
        extra.entrySet().forEach(entry -> root.add(entry.getKey(), entry.getValue()));
    }

    private String selectorsJson(List<String> selectors) {
        return selectors.stream()
                .sorted()
                .map(selector -> "\"" + selector + "\"")
                .collect(java.util.stream.Collectors.joining(", ", "[", "]"));
    }

    private String targetJson() {
        TargetTriple host = HostPlatform.detect().orElseThrow().target();
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
                host == TargetTriple.WINDOWS_X64,
                host == TargetTriple.WINDOWS_ARM64,
                host == TargetTriple.LINUX_X64,
                host == TargetTriple.LINUX_ARM64,
                host == TargetTriple.MACOS_X64,
                host == TargetTriple.MACOS_ARM64);
    }

    private Map<String, Path> reportPaths(Path workspace) {
        LinkedHashMap<String, Path> paths = new LinkedHashMap<>();
        REPORTS.stream()
                .sorted()
                .forEach(report -> {
                    Path path = workspace.resolve("reports").resolve(report);
                    if (Files.isRegularFile(path)) {
                        paths.put(report, path);
                    }
                });
        return paths;
    }

    private void writeJar(Path path, Map<String, byte[]> entries) throws IOException {
        Files.createDirectories(path.getParent());
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(path))) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet().stream()
                    .sorted(Comparator.comparing(Map.Entry::getKey))
                    .toList()) {
                JarEntry jarEntry = new JarEntry(entry.getKey());
                jarEntry.setTime(0L);
                output.putNextEntry(jarEntry);
                output.write(entry.getValue());
                output.closeEntry();
            }
        }
    }

    private Path baselineJarForOriginalRun(Path inputJar, CorpusCase corpusCase, Path root) throws IOException {
        if (!corpusCase.signaturePolicy().equals("strip") && !corpusCase.signaturePolicy().equals("resign")) {
            return inputJar;
        }
        Path baseline = root.resolve("input").resolve(corpusCase.name() + "-unsigned-baseline.jar");
        Files.createDirectories(baseline.getParent());
        try (JarFile input = new JarFile(inputJar.toFile(), false);
                JarOutputStream output = new JarOutputStream(Files.newOutputStream(baseline))) {
            for (JarEntry entry : input.stream().toList()) {
                if (isSignatureMetadata(entry.getName())) {
                    continue;
                }
                output.putNextEntry(new JarEntry(entry.getName()));
                if (!entry.isDirectory()) {
                    output.write(input.getInputStream(entry).readAllBytes());
                }
                output.closeEntry();
            }
        }
        return baseline;
    }

    private boolean isSignatureMetadata(String name) {
        if (!name.startsWith("META-INF/")) {
            return false;
        }
        String upper = name.toUpperCase(java.util.Locale.ROOT);
        return upper.endsWith(".SF")
                || upper.endsWith(".RSA")
                || upper.endsWith(".DSA")
                || upper.endsWith(".EC");
    }
}
