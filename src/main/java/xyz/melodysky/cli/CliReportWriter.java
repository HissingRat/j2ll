package xyz.melodysky.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import xyz.melodysky.analysis.field.NativeFieldInternalizationPlan;
import xyz.melodysky.analysis.world.WholeProgramAnalysisFeature;
import xyz.melodysky.analysis.world.WholeProgramAnalysisPolicy;
import xyz.melodysky.analysis.world.WholeProgramAnalysisScope;
import xyz.melodysky.config.ConfigLoadResult;
import xyz.melodysky.config.ResolvedConfig;
import xyz.melodysky.config.SignaturePolicy;
import xyz.melodysky.diagnostic.Diagnostic;
import xyz.melodysky.packaging.JarPreservationReport;
import xyz.melodysky.packaging.SignatureActionReport;
import xyz.melodysky.pipeline.WorkspaceLayout;
import xyz.melodysky.report.ArtifactAudit;
import xyz.melodysky.report.ArtifactAuditReportWriter;
import xyz.melodysky.report.DryRunReportWriter;
import xyz.melodysky.report.FailureReportWriter;
import xyz.melodysky.report.FieldInternalizationReportWriter;
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
import xyz.melodysky.toolchain.NativeLibraryName;
import xyz.melodysky.toolchain.ZigBuildException;

/** Writes the complete CLI-side evidence set for runs that stop outside the main pipeline. */
final class CliReportWriter {
    void writeConfigFailure(Path workspace, ConfigLoadResult config, CliMode mode) throws IOException {
        WorkspaceLayout layout = new WorkspaceLayout(workspace);
        Files.createDirectories(layout.reportsDirectory());
        Path reports = layout.reportsDirectory();
        Path outputJar = layout.failedOutputJar();
        NativeBuildPlan buildPlan = new NativeBuildPlan(List.of());
        Files.writeString(reports.resolve("diagnostics.json"),
                new ReportJsonWriter().diagnosticsJson(config.diagnostics()));
        Files.writeString(reports.resolve("failure-report.json"),
                new FailureReportWriter().json(config.diagnostics(), false));
        Files.writeString(reports.resolve("packaging-report.json"), new PackagingReportWriter().packagingJson(
                workspace.relativize(outputJar),
                SignaturePolicy.FAIL,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                null,
                buildPlan,
                List.of(),
                JarPreservationReport.empty(),
                SignatureActionReport.none(false)));
        Files.writeString(reports.resolve("artifact-audit.json"),
                new ArtifactAuditReportWriter().json(new ArtifactAudit().skipped(
                        "FINAL_ARTIFACT_NOT_WRITTEN",
                        "config validation failed before final output JAR was written")));
        writeEmptyStageReports(reports, "config-failed");
        writeSummaryAndIndex(workspace, mode == CliMode.DRY_RUN ? "dry-run" : "build", false);
    }

    void writeDryRun(
            Path configPath,
            Path workspace,
            ResolvedConfig config,
            NativeBuildPlan buildPlan,
            List<Diagnostic> diagnostics,
            boolean inputParsed,
            int parsedClassCount,
            int requestedMethodCount,
            int notApplicableMethodCount,
            int excludedMethodCount) throws IOException {
        WorkspaceLayout layout = new WorkspaceLayout(workspace);
        Path reports = layout.reportsDirectory();
        Files.createDirectories(reports);
        Path outputJar = layout.outputJar(config.jarFile());
        Files.writeString(workspace.resolve("config.resolved.json"), new ResolvedConfigReportWriter().json(config));
        Files.writeString(reports.resolve("diagnostics.json"), new ReportJsonWriter().diagnosticsJson(diagnostics));
        Files.writeString(reports.resolve("packaging-report.json"), new PackagingReportWriter().packagingJson(
                workspace.relativize(outputJar),
                config.signaturePolicy(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                null,
                buildPlan,
                List.of(),
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
        writeEmptyStageReports(reports, config.protection().seed());
        writeFieldInternalizationNotRun(
                reports,
                config,
                WholeProgramAnalysisPolicy.strict());
        boolean hasErrors = diagnostics.stream()
                .anyMatch(diagnostic -> diagnostic.severity().wireName().equals("error"));
        if (hasErrors) {
            Files.writeString(reports.resolve("failure-report.json"),
                    new FailureReportWriter().json(diagnostics, false));
        }
        writeSummaryAndIndex(workspace, "dry-run", false);
    }

    void writeNativeLinkFailure(
            Path workspace,
            ResolvedConfig config,
            List<Diagnostic> diagnostics,
            ZigBuildException zigFailure,
            WholeProgramAnalysisPolicy wholeProgramPolicy)
            throws IOException {
        WorkspaceLayout layout = new WorkspaceLayout(workspace);
        Path reports = layout.reportsDirectory();
        Files.createDirectories(reports);
        Path outputJar = layout.outputJar(config.jarFile());
        NativeBuildPlan buildPlan = new NativeBuildPlanner().plan(
                workspace,
                NativeLibraryName.derive(config.protection().seed()),
                config.targets());
        if (zigFailure != null) {
            buildPlan = buildPlan.withBuildFailures(
                    zigFailure.failedTargets(),
                    "zigBuildFailed",
                    zigFailure.logTail());
        }
        Files.writeString(workspace.resolve("config.resolved.json"), new ResolvedConfigReportWriter().json(config));
        Files.writeString(reports.resolve("diagnostics.json"), new ReportJsonWriter().diagnosticsJson(diagnostics));
        Files.writeString(reports.resolve("failure-report.json"), new FailureReportWriter().json(diagnostics, false));
        Files.writeString(reports.resolve("packaging-report.json"), new PackagingReportWriter().packagingJson(
                workspace.relativize(outputJar),
                config.signaturePolicy(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                null,
                buildPlan,
                List.of(),
                JarPreservationReport.empty(),
                SignatureActionReport.none(false)));
        Files.writeString(reports.resolve("artifact-audit.json"),
                new ArtifactAuditReportWriter().json(new ArtifactAudit().skipped(
                        "FINAL_ARTIFACT_NOT_WRITTEN",
                        "native toolchain failed before final output JAR was written")));
        writeEmptyStageReports(reports, config.protection().seed());
        writeFieldInternalizationNotRun(reports, config, wholeProgramPolicy);
        writeSummaryAndIndex(workspace, "build", false);
    }

    private void writeFieldInternalizationNotRun(
            Path reports,
            ResolvedConfig config,
            WholeProgramAnalysisPolicy wholeProgramPolicy) throws IOException {
        boolean enabled = config.protection().enabled()
                && config.protection().ir().enabled()
                && config.protection().ir().fieldInternalization();
        WholeProgramAnalysisScope scope = enabled
                ? wholeProgramPolicy.scopeFor(
                        WholeProgramAnalysisFeature.FIELD_INTERNALIZATION,
                        config.worldModel())
                : WholeProgramAnalysisScope.NOT_REQUIRED;
        Files.writeString(
                reports.resolve("field-internalization-report.json"),
                new FieldInternalizationReportWriter().json(
                        new NativeFieldInternalizationPlan(List.of()),
                        enabled,
                        false,
                        new xyz.melodysky.toolchain.NativeImplementationPlan(List.of()),
                        config.worldModel(),
                        scope,
                        false,
                        false));
    }

    private void writeEmptyStageReports(Path reports, String protectionSeed) throws IOException {
        Files.writeString(reports.resolve("frontend-skip-report.json"),
                new FrontendSkipReportWriter().json(List.of()));
        Files.writeString(reports.resolve("lowering-report.json"),
                new ReportJsonWriter().loweringJson(List.of(), List.of(), List.of()));
        Files.writeString(reports.resolve("protection-report.json"),
                new ProtectionReportWriter().json(protectionSeed, List.of()));
        Files.writeString(
                reports.resolve("field-internalization-report.json"),
                new FieldInternalizationReportWriter().json(
                        new NativeFieldInternalizationPlan(List.of()),
                        false,
                        false));
        Files.writeString(reports.resolve("symbol-audit.json"), new SymbolAuditReportWriter().json(List.of()));
        Files.writeString(reports.resolve("support-matrix.json"), new SupportMatrixWriter().json());
        Files.writeString(reports.resolve("opcode-support-matrix.json"), new OpcodeSupportMatrixWriter().json());
        Files.writeString(reports.resolve("known-blockers.json"), new KnownBlockersWriter().json());
    }

    private void writeSummaryAndIndex(Path workspace, String mode, boolean finalArtifactWritten) throws IOException {
        Files.writeString(workspace.resolve("reports/release-readiness.json"),
                new ReleaseReadinessWriter().json(new ReleaseReadinessGate().evaluate(workspace)));
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
