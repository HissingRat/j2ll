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
import java.util.jar.JarOutputStream;
import xyz.melodysky.config.ConfigLoader;
import xyz.melodysky.config.ResolvedConfig;
import xyz.melodysky.cli.J2llCli;
import xyz.melodysky.pipeline.MainlinePipeline;
import xyz.melodysky.pipeline.MainlinePipelineResult;
import xyz.melodysky.packaging.NativeRegistrationPlan;
import xyz.melodysky.testsupport.FakeManagedZig;
import xyz.melodysky.testsupport.JvmRunner;
import xyz.melodysky.toolchain.NativeBuildPlan;
import xyz.melodysky.toolchain.HostPlatform;
import xyz.melodysky.toolchain.TargetTriple;

public final class CorpusRunner {
    private static final List<String> REPORTS = List.of(
            "diagnostics.json",
            "artifact-audit.json",
            "failure-report.json",
            "frontend-skip-report.json",
            "known-blockers.json",
            "lowering-report.json",
            "opcode-support-matrix.json",
            "packaging-report.json",
            "protection-report.json",
            "release-readiness.json",
            "support-matrix.json",
            "symbol-audit.json");

    public CorpusRunResult run(CorpusCase corpusCase, Path root) throws Exception {
        Path inputJar = root.resolve("input").resolve(corpusCase.name() + ".jar");
        if (!corpusCase.jarEntries().isEmpty()) {
            writeJar(inputJar, corpusCase.jarEntries());
        }
        var originalRun = corpusCase.runChildDifferential()
                ? new JvmRunner().run(inputJar, corpusCase.mainClass(), List.of())
                : null;
        Path workspace = root.resolve("workspace").resolve(corpusCase.name());
        MainlinePipelineResult pipelineResult;
        try (AutoCloseable ignored = FakeManagedZig.installAndUse(root.resolve("j2ll-home"))) {
            if (corpusCase.configJsonOverride() == null) {
                var configResult = configResult(inputJar, corpusCase);
                ResolvedConfig config = configResult.config()
                        .orElseThrow(() -> new IllegalStateException(
                                "release corpus config failed to load: " + configResult.diagnostics()));
                pipelineResult = new MainlinePipeline(corpusCase.environment()::get).run(config, workspace);
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
                new CorpusReportPaths(reportPaths(workspace)));
    }

    private ResolvedConfig config(Path inputJar, CorpusCase corpusCase) {
        var result = configResult(inputJar, corpusCase);
        return result.config()
                .orElseThrow(() -> new IllegalStateException(
                        "release corpus config failed to load: " + result.diagnostics()));
    }

    private xyz.melodysky.config.ConfigLoadResult configResult(Path inputJar, CorpusCase corpusCase) {
        JsonObject root = JsonParser.parseString("""
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
                  "whiteList": %s,
                  "blackList": [],
                  "target": %s,
                  "libraryName": "j2llcorpus",
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
                    "intensity": "normal",
                    "ir": {
                      "enabled": %s,
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
                      "enabled": %s,
                      "nameObfuscation": { "enabled": true, "intensity": "normal" },
                      "opaquePredicates": { "enabled": true, "intensity": "normal" },
                      "blockLayoutPerturbation": { "enabled": true, "intensity": "normal" },
                      "indirectCalls": { "enabled": true, "intensity": "normal" },
                      "globalLayout": { "enabled": true, "intensity": "normal" },
                      "visibilityHardening": { "enabled": true }
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
        return new ConfigLoader().load(root, inputJar.getParent());
    }

    private MainlinePipelineResult runCliConfigCase(Path inputJar, CorpusCase corpusCase, Path workspace)
            throws IOException {
        Path configPath = workspace.resolve("config.json");
        Files.createDirectories(configPath.getParent());
        Files.writeString(configPath, materializedConfigJson(inputJar, corpusCase.configJsonOverride()));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        J2llCli.run(
                new String[] {"build", configPath.toString(), workspace.toString()},
                new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
        var loaded = new ConfigLoader().load(configPath);
        Path outputJar = workspace.resolve("output/config-failed.jar");
        return new MainlinePipelineResult(
                workspace,
                outputJar,
                loaded.diagnostics(),
                new NativeBuildPlan(List.of()),
                new NativeRegistrationPlan(List.of()),
                false);
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
}
