package xyz.melodysky.cli;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import xyz.melodysky.config.ConfigLoadResult;
import xyz.melodysky.config.ConfigLoader;
import xyz.melodysky.config.SelectorMatchResult;
import xyz.melodysky.config.SelectorMatcher;
import xyz.melodysky.config.SignaturePolicy;
import xyz.melodysky.diagnostic.Diagnostic;
import xyz.melodysky.diagnostic.DiagnosticBag;
import xyz.melodysky.diagnostic.DiagnosticHints;
import xyz.melodysky.diagnostic.DiagnosticStage;
import xyz.melodysky.frontend.classfile.AsmClassParser;
import xyz.melodysky.frontend.classfile.ClassParseResult;
import xyz.melodysky.frontend.classfile.JarClassFileSource;
import xyz.melodysky.pipeline.MainlinePipeline;
import xyz.melodysky.pipeline.MainlinePipelineResult;
import xyz.melodysky.packaging.JarPreservationReport;
import xyz.melodysky.packaging.SignatureActionReport;
import xyz.melodysky.report.ArtifactAudit;
import xyz.melodysky.report.ArtifactAuditReportWriter;
import xyz.melodysky.report.DryRunReportWriter;
import xyz.melodysky.report.FailureReportWriter;
import xyz.melodysky.report.FrontendSkipReportWriter;
import xyz.melodysky.report.KnownBlockersWriter;
import xyz.melodysky.report.OpcodeSupportMatrixWriter;
import xyz.melodysky.report.PackagingReportWriter;
import xyz.melodysky.report.ProtectionReportWriter;
import xyz.melodysky.report.ReleaseReadinessGate;
import xyz.melodysky.report.ReleaseReadinessWriter;
import xyz.melodysky.report.ReportIndexWriter;
import xyz.melodysky.report.ReportJsonWriter;
import xyz.melodysky.report.ResolvedConfigReportWriter;
import xyz.melodysky.report.SummaryMarkdownWriter;
import xyz.melodysky.report.SummaryReportWriter;
import xyz.melodysky.report.SupportMatrixWriter;
import xyz.melodysky.report.SymbolAuditReportWriter;
import xyz.melodysky.toolchain.NativeBuildPlan;
import xyz.melodysky.toolchain.NativeBuildPlanner;
import xyz.melodysky.toolchain.NativeBuildTargetPreflight;
import xyz.melodysky.toolchain.ToolchainDiagnostics;

public final class J2llCli {
    private static final String VERSION = "j2ll 0.1.0-beta";

    private J2llCli() {
    }

    public static void main(String[] args) throws IOException {
        int code = run(args, System.out, System.err);
        if (code != 0) {
            System.exit(code);
        }
    }

    public static int run(String[] args, PrintStream out, PrintStream err) throws IOException {
        if (args.length == 1 && args[0].equals("--help")) {
            out.print(helpText());
            return 0;
        }
        if (args.length == 1 && args[0].equals("--version")) {
            out.println(VERSION);
            return 0;
        }
        if (args.length == 2 && args[0].equals("validate")) {
            return validate(Path.of(args[1]), out, err);
        }
        if (args.length == 3 && args[0].equals("dry-run")) {
            return dryRun(Path.of(args[1]), Path.of(args[2]), out, err);
        }
        if (args.length == 3 && args[0].equals("build")) {
            return build(Path.of(args[1]), Path.of(args[2]), out, err);
        }
        err.print(helpText());
        return 2;
    }

    private static int build(Path configPath, Path workspace, PrintStream out, PrintStream err) throws IOException {
        Files.createDirectories(workspace.resolve("reports"));

        ConfigLoadResult config = new ConfigLoader().load(configPath);
        if (config.hasErrors() || config.config().isEmpty()) {
            writeConfigFailureReports(workspace, config);
            err.println("config validation failed; diagnostics written to "
                    + workspace.resolve("reports/diagnostics.json"));
            primaryHint(config.diagnostics()).ifPresent(hint -> err.println("hint=" + hint));
            err.println("summaryReport=" + workspace.resolve("reports/summary.json"));
            err.println("reportIndex=" + workspace.resolve("reports/index.json"));
            return 2;
        }

        MainlinePipelineResult result;
        try {
            result = new MainlinePipeline().run(config.config().orElseThrow(), workspace);
        } catch (IOException exception) {
            Diagnostic diagnostic = Diagnostic.error(
                    DiagnosticStage.NATIVE_LINK,
                    ToolchainDiagnostics.ZIG_TARGET_UNBUILDABLE,
                    exception.getMessage());
            writeNativeLinkFailureReports(workspace, config.config().orElseThrow(), java.util.List.of(diagnostic));
            err.println(primaryFailure(java.util.List.of(diagnostic)));
            primaryHint(java.util.List.of(diagnostic)).ifPresent(hint -> err.println("hint=" + hint));
            err.println("reportsDir=" + workspace.resolve("reports"));
            err.println("summaryReport=" + workspace.resolve("reports/summary.json"));
            err.println("reportIndex=" + workspace.resolve("reports/index.json"));
            return 4;
        }
        if (result.successful()) {
            out.println("outputJar=" + result.outputJar());
            out.println("reportsDir=" + workspace.resolve("reports"));
            out.println("summaryReport=" + workspace.resolve("reports/summary.json"));
            out.println("reportIndex=" + workspace.resolve("reports/index.json"));
            return 0;
        }
        int exitCode = exitCodeForDiagnostics(result.diagnostics());
        err.println(primaryFailure(result));
        primaryHint(result.diagnostics()).ifPresent(hint -> err.println("hint=" + hint));
        if (exitCode == 7) {
            formatReadinessFailureDetails(workspace).forEach(err::println);
        }
        err.println("reportsDir=" + workspace.resolve("reports"));
        err.println("summaryReport=" + workspace.resolve("reports/summary.json"));
        err.println("reportIndex=" + workspace.resolve("reports/index.json"));
        return exitCode;
    }

    private static int validate(Path configPath, PrintStream out, PrintStream err) throws IOException {
        ConfigLoadResult config = new ConfigLoader().load(configPath);
        if (config.hasErrors() || config.config().isEmpty()) {
            err.println("CONFIG CONFIG_VALIDATION_FAILED: config validation failed");
            config.diagnostics().stream()
                    .filter(diagnostic -> diagnostic.severity().wireName().equals("error"))
                    .sorted()
                    .findFirst()
                    .ifPresent(diagnostic -> {
                        err.println("reason=" + diagnostic.code().value() + " " + diagnostic.message());
                        String hint = DiagnosticHints.hint(diagnostic);
                        if (!hint.isBlank()) {
                            err.println("hint=" + hint);
                        }
                    });
            return 2;
        }
        out.println("config=ok");
        out.println("configPath=" + configPath);
        return 0;
    }

    private static int dryRun(Path configPath, Path workspace, PrintStream out, PrintStream err) throws IOException {
        Files.createDirectories(workspace.resolve("reports"));
        ConfigLoadResult loaded = new ConfigLoader().load(configPath);
        if (loaded.hasErrors() || loaded.config().isEmpty()) {
            writeConfigFailureReports(workspace, loaded);
            err.println("config validation failed; diagnostics written to "
                    + workspace.resolve("reports/diagnostics.json"));
            primaryHint(loaded.diagnostics()).ifPresent(hint -> err.println("hint=" + hint));
            err.println("reportsDir=" + workspace.resolve("reports"));
            err.println("summaryReport=" + workspace.resolve("reports/summary.json"));
            err.println("reportIndex=" + workspace.resolve("reports/index.json"));
            return 2;
        }

        var config = loaded.config().orElseThrow();
        String libraryName = config.libraryName() == null
                ? "j2ll_" + seedHash(config.protection().seed()).substring(0, 16)
                : config.libraryName();
        NativeBuildPlan buildPlan = new NativeBuildPlanner().plan(workspace, libraryName, config.targets());
        DiagnosticBag diagnostics = new DiagnosticBag();
        loaded.diagnostics().forEach(diagnostics::add);
        for (NativeBuildTargetPreflight preflight : buildPlan.targetPreflights()) {
            Diagnostic diagnostic = preflight.buildable()
                    ? Diagnostic.info(
                            DiagnosticStage.NATIVE_LINK,
                            ToolchainDiagnostics.ZIG_TARGET_PREFLIGHT,
                            "Zig target preflight " + preflight.target().directoryName()
                                    + " -> buildable: " + preflight.reason())
                    : Diagnostic.error(
                            DiagnosticStage.NATIVE_LINK,
                            ToolchainDiagnostics.ZIG_TARGET_UNBUILDABLE,
                            preflight.reason());
            diagnostics.add(diagnostic.withDecision(preflight.status()));
        }

        boolean inputParsed = false;
        int parsedClassCount = 0;
        int requestedMethodCount = 0;
        int notApplicableMethodCount = 0;
        int excludedMethodCount = 0;
        if (Files.isRegularFile(config.jarFile())) {
            var parse = new AsmClassParser().parseAll(new JarClassFileSource(config.jarFile()));
            parse.diagnostics().forEach(diagnostics::add);
            if (parse.artifact().isPresent()) {
                ClassParseResult parsed = parse.artifact().orElseThrow();
                inputParsed = true;
                parsedClassCount = parsed.program().classes().size();
                SelectorMatchResult match = new SelectorMatcher().expand(
                        parsed.program(),
                        config.whiteList(),
                        config.blackList());
                match.diagnostics().forEach(diagnostics::add);
                requestedMethodCount = match.requestedMethods().size();
                notApplicableMethodCount = match.notApplicable().size();
                excludedMethodCount = match.excluded().size();
            }
        }

        writeDryRunReports(
                configPath,
                workspace,
                config,
                buildPlan,
                diagnostics.diagnostics(),
                inputParsed,
                parsedClassCount,
                requestedMethodCount,
                notApplicableMethodCount,
                excludedMethodCount);
        int exitCode = exitCodeForDiagnostics(diagnostics.diagnostics());
        if (exitCode == 1) {
            exitCode = 0;
        }
        if (exitCode == 0) {
            out.println("dryRunReport=" + workspace.resolve("reports/dry-run-report.json"));
            out.println("reportsDir=" + workspace.resolve("reports"));
            out.println("summaryReport=" + workspace.resolve("reports/summary.json"));
            out.println("reportIndex=" + workspace.resolve("reports/index.json"));
        } else {
            err.println(primaryFailure(diagnostics.diagnostics()));
            primaryHint(diagnostics.diagnostics()).ifPresent(hint -> err.println("hint=" + hint));
            err.println("dryRunReport=" + workspace.resolve("reports/dry-run-report.json"));
            err.println("reportsDir=" + workspace.resolve("reports"));
            err.println("summaryReport=" + workspace.resolve("reports/summary.json"));
            err.println("reportIndex=" + workspace.resolve("reports/index.json"));
        }
        return exitCode;
    }

    private static String helpText() {
        return """
                usage:
                  j2ll --help
                  j2ll --version
                  j2ll validate <config.json>
                  j2ll dry-run <config.json> <workspace>
                  j2ll build <config.json> <workspace>

                exit codes: 0 success, 2 config, 3 frontend/lowering, 4 native target/toolchain,
                            5 packaging/signing, 6 artifact audit, 7 readiness, 1 unexpected
                """;
    }

    private static String primaryFailure(MainlinePipelineResult result) {
        return primaryFailure(result.diagnostics());
    }

    private static String primaryFailure(java.util.List<Diagnostic> diagnostics) {
        return diagnostics.stream()
                .filter(diagnostic -> diagnostic.severity().wireName().equals("error"))
                .sorted()
                .findFirst()
                .map(diagnostic -> diagnostic.stage() + " " + diagnostic.code().value() + ": " + diagnostic.message())
                .orElse("unexpected internal error");
    }

    private static java.util.Optional<String> primaryHint(java.util.List<Diagnostic> diagnostics) {
        return diagnostics.stream()
                .filter(diagnostic -> diagnostic.severity().wireName().equals("error"))
                .sorted()
                .map(DiagnosticHints::hint)
                .filter(hint -> !hint.isBlank())
                .findFirst();
    }

    static java.util.List<String> formatReadinessFailureDetails(Path workspace) throws IOException {
        Path report = workspace.resolve("reports/release-readiness.json");
        if (!Files.isRegularFile(report)) {
            return java.util.List.of("releaseReadinessReport=" + report + " (missing)");
        }
        JsonObject root = JsonParser.parseString(Files.readString(report)).getAsJsonObject();
        java.util.ArrayList<String> lines = new java.util.ArrayList<>();
        lines.add("releaseReadinessReport=" + report);
        if (root.has("missingEvidence") && root.get("missingEvidence").isJsonArray()) {
            int count = 0;
            for (JsonElement element : root.getAsJsonArray("missingEvidence")) {
                JsonObject item = element.getAsJsonObject();
                lines.add("missingEvidence="
                        + text(item, "type") + " "
                        + text(item, "reasonCode") + " "
                        + text(item, "detail"));
                count++;
                if (count == 3) {
                    break;
                }
            }
        }
        return lines;
    }

    private static String text(JsonObject object, String field) {
        return object.has(field) && !object.get(field).isJsonNull()
                ? object.get(field).getAsString()
                : "";
    }

    private static String seedHash(String seed) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(seed.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    static int exitCodeForDiagnostics(java.util.List<Diagnostic> diagnostics) {
        return diagnostics.stream()
                .filter(diagnostic -> diagnostic.severity().wireName().equals("error"))
                .sorted(java.util.Comparator.comparingInt((Diagnostic diagnostic) -> exitCode(diagnostic))
                        .thenComparing(Diagnostic::code))
                .mapToInt(J2llCli::exitCode)
                .findFirst()
                .orElse(1);
    }

    private static int exitCode(Diagnostic diagnostic) {
        if (diagnostic.stage() == DiagnosticStage.CONFIG) {
            return 2;
        }
        if (diagnostic.stage() == DiagnosticStage.PARSE
                || diagnostic.stage() == DiagnosticStage.CFG
                || diagnostic.stage() == DiagnosticStage.LOWERING
                || diagnostic.stage() == DiagnosticStage.VALIDATION
                || diagnostic.stage() == DiagnosticStage.LLVM_MODEL
                || diagnostic.stage() == DiagnosticStage.LLVM_EMISSION) {
            return 3;
        }
        if (diagnostic.stage() == DiagnosticStage.NATIVE_LINK || diagnostic.stage() == DiagnosticStage.SYMBOL_AUDIT) {
            return 4;
        }
        if (diagnostic.stage() == DiagnosticStage.PACKAGING) {
            return 5;
        }
        if (diagnostic.stage() == DiagnosticStage.ARTIFACT_AUDIT) {
            return 6;
        }
        if (diagnostic.stage() == DiagnosticStage.RELEASE_READINESS) {
            return 7;
        }
        return 1;
    }

    private static void writeConfigFailureReports(Path workspace, ConfigLoadResult config) throws IOException {
        Path reports = workspace.resolve("reports");
        Files.createDirectories(reports);
        Path outputJar = workspace.resolve("output/config-failed.jar");
        NativeBuildPlan buildPlan = new NativeBuildPlan(java.util.List.of());
        Files.writeString(reports.resolve("diagnostics.json"),
                new ReportJsonWriter().diagnosticsJson(config.diagnostics()));
        Files.writeString(reports.resolve("failure-report.json"),
                new FailureReportWriter().json(config.diagnostics(), false));
        Files.writeString(reports.resolve("packaging-report.json"), new PackagingReportWriter().packagingJson(
                workspace.relativize(outputJar),
                SignaturePolicy.FAIL,
                java.util.List.of(),
                java.util.List.of(),
                java.util.List.of(),
                java.util.List.of(),
                java.util.List.of(),
                null,
                buildPlan,
                java.util.List.of(),
                JarPreservationReport.empty(),
                SignatureActionReport.none(false)));
        Files.writeString(reports.resolve("artifact-audit.json"),
                new ArtifactAuditReportWriter().json(new ArtifactAudit().skipped(
                        "FINAL_ARTIFACT_NOT_WRITTEN",
                        "config validation failed before final output JAR was written")));
        Files.writeString(reports.resolve("frontend-skip-report.json"), new FrontendSkipReportWriter().json(java.util.List.of()));
        Files.writeString(reports.resolve("lowering-report.json"),
                new ReportJsonWriter().loweringJson(java.util.List.of(), java.util.List.of(), java.util.List.of()));
        Files.writeString(reports.resolve("protection-report.json"),
                new ProtectionReportWriter().json("config-failed", java.util.List.of()));
        Files.writeString(reports.resolve("symbol-audit.json"), new SymbolAuditReportWriter().json(java.util.List.of()));
        Files.writeString(reports.resolve("support-matrix.json"), new SupportMatrixWriter().json());
        Files.writeString(reports.resolve("opcode-support-matrix.json"), new OpcodeSupportMatrixWriter().json());
        Files.writeString(reports.resolve("known-blockers.json"), new KnownBlockersWriter().json());
        Files.writeString(reports.resolve("release-readiness.json"),
                new ReleaseReadinessWriter().json(new ReleaseReadinessGate().evaluate(workspace)));
        writeReportSummaryAndIndex(workspace, "build", false);
    }

    private static void writeDryRunReports(
            Path configPath,
            Path workspace,
            xyz.melodysky.config.ResolvedConfig config,
            NativeBuildPlan buildPlan,
            java.util.List<Diagnostic> diagnostics,
            boolean inputParsed,
            int parsedClassCount,
            int requestedMethodCount,
            int notApplicableMethodCount,
            int excludedMethodCount) throws IOException {
        Path reports = workspace.resolve("reports");
        Files.createDirectories(reports);
        Path outputJar = workspace.resolve("output").resolve(config.jarFile().getFileName());
        Files.writeString(reports.resolve("config.resolved.json"), new ResolvedConfigReportWriter().json(config));
        Files.writeString(reports.resolve("diagnostics.json"), new ReportJsonWriter().diagnosticsJson(diagnostics));
        Files.writeString(reports.resolve("packaging-report.json"), new PackagingReportWriter().packagingJson(
                workspace.relativize(outputJar),
                config.signaturePolicy(),
                java.util.List.of(),
                java.util.List.of(),
                java.util.List.of(),
                java.util.List.of(),
                java.util.List.of(),
                null,
                buildPlan,
                java.util.List.of(),
                JarPreservationReport.empty(),
                SignatureActionReport.none(false)));
        Files.writeString(reports.resolve("artifact-audit.json"),
                new ArtifactAuditReportWriter().json(new ArtifactAudit().skipped(
                        "DRY_RUN_NO_FINAL_ARTIFACT",
                        "dry-run does not write final output JAR or invoke native build")));
        Files.writeString(reports.resolve("dry-run-report.json"), new DryRunReportWriter().json(
                configPath,
                workspace,
                inputParsed,
                parsedClassCount,
                requestedMethodCount,
                notApplicableMethodCount,
                excludedMethodCount,
                buildPlan,
                diagnostics.stream().map(diagnostic -> diagnostic.code().value()).toList()));
        Files.writeString(reports.resolve("frontend-skip-report.json"), new FrontendSkipReportWriter().json(java.util.List.of()));
        Files.writeString(reports.resolve("lowering-report.json"),
                new ReportJsonWriter().loweringJson(java.util.List.of(), java.util.List.of(), java.util.List.of()));
        Files.writeString(reports.resolve("protection-report.json"),
                new ProtectionReportWriter().json(config.protection().seed(), java.util.List.of()));
        Files.writeString(reports.resolve("symbol-audit.json"), new SymbolAuditReportWriter().json(java.util.List.of()));
        Files.writeString(reports.resolve("support-matrix.json"), new SupportMatrixWriter().json());
        Files.writeString(reports.resolve("opcode-support-matrix.json"), new OpcodeSupportMatrixWriter().json());
        Files.writeString(reports.resolve("known-blockers.json"), new KnownBlockersWriter().json());
        boolean hasErrors = diagnostics.stream().anyMatch(diagnostic -> diagnostic.severity().wireName().equals("error"));
        if (hasErrors) {
            Files.writeString(reports.resolve("failure-report.json"),
                    new FailureReportWriter().json(diagnostics, false));
        }
        Files.writeString(reports.resolve("release-readiness.json"),
                new ReleaseReadinessWriter().json(new ReleaseReadinessGate().evaluate(workspace)));
        writeReportSummaryAndIndex(workspace, "dry-run", false);
    }

    private static void writeNativeLinkFailureReports(
            Path workspace,
            xyz.melodysky.config.ResolvedConfig config,
            java.util.List<Diagnostic> diagnostics) throws IOException {
        Path reports = workspace.resolve("reports");
        Files.createDirectories(reports);
        Path outputJar = workspace.resolve("output").resolve(config.jarFile().getFileName());
        NativeBuildPlan buildPlan = new NativeBuildPlanner().plan(workspace, config.libraryName(), config.targets());
        Files.writeString(reports.resolve("config.resolved.json"), new ResolvedConfigReportWriter().json(config));
        Files.writeString(reports.resolve("diagnostics.json"), new ReportJsonWriter().diagnosticsJson(diagnostics));
        Files.writeString(reports.resolve("failure-report.json"), new FailureReportWriter().json(diagnostics, false));
        Files.writeString(reports.resolve("packaging-report.json"), new PackagingReportWriter().packagingJson(
                workspace.relativize(outputJar),
                config.signaturePolicy(),
                java.util.List.of(),
                java.util.List.of(),
                java.util.List.of(),
                java.util.List.of(),
                java.util.List.of(),
                null,
                buildPlan,
                java.util.List.of(),
                JarPreservationReport.empty(),
                SignatureActionReport.none(false)));
        Files.writeString(reports.resolve("artifact-audit.json"),
                new ArtifactAuditReportWriter().json(new ArtifactAudit().skipped(
                        "FINAL_ARTIFACT_NOT_WRITTEN",
                        "native toolchain failed before final output JAR was written")));
        Files.writeString(reports.resolve("frontend-skip-report.json"), new FrontendSkipReportWriter().json(java.util.List.of()));
        Files.writeString(reports.resolve("lowering-report.json"),
                new ReportJsonWriter().loweringJson(java.util.List.of(), java.util.List.of(), java.util.List.of()));
        Files.writeString(reports.resolve("protection-report.json"),
                new ProtectionReportWriter().json(config.protection().seed(), java.util.List.of()));
        Files.writeString(reports.resolve("symbol-audit.json"), new SymbolAuditReportWriter().json(java.util.List.of()));
        Files.writeString(reports.resolve("support-matrix.json"), new SupportMatrixWriter().json());
        Files.writeString(reports.resolve("opcode-support-matrix.json"), new OpcodeSupportMatrixWriter().json());
        Files.writeString(reports.resolve("known-blockers.json"), new KnownBlockersWriter().json());
        Files.writeString(reports.resolve("release-readiness.json"),
                new ReleaseReadinessWriter().json(new ReleaseReadinessGate().evaluate(workspace)));
        writeReportSummaryAndIndex(workspace, "build", false);
    }

    private static void writeReportSummaryAndIndex(Path workspace, String mode, boolean finalArtifactWritten)
            throws IOException {
        new SummaryReportWriter().write(workspace, mode, finalArtifactWritten);
        new SummaryMarkdownWriter().write(workspace);
        new ReportIndexWriter().write(workspace);
        Files.writeString(workspace.resolve("reports/release-readiness.json"),
                new ReleaseReadinessWriter().json(new ReleaseReadinessGate().evaluate(workspace)));
        new SummaryReportWriter().write(workspace, mode, finalArtifactWritten);
        new SummaryMarkdownWriter().write(workspace);
        new ReportIndexWriter().write(workspace);
    }
}
