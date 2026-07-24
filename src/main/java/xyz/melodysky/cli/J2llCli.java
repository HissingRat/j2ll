package xyz.melodysky.cli;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.fusesource.jansi.AnsiConsole;
import xyz.melodysky.cli.progress.LegacyProgressRenderer;
import xyz.melodysky.config.ConfigLoadResult;
import xyz.melodysky.config.ResolvedConfig;
import xyz.melodysky.config.SelectorMatchResult;
import xyz.melodysky.config.SelectorMatcher;
import xyz.melodysky.diagnostic.Diagnostic;
import xyz.melodysky.diagnostic.DiagnosticBag;
import xyz.melodysky.diagnostic.DiagnosticStage;
import xyz.melodysky.frontend.classfile.AsmClassParser;
import xyz.melodysky.frontend.classfile.ClassParseResult;
import xyz.melodysky.frontend.classfile.JarClassFileSource;
import xyz.melodysky.pipeline.BuildWorkspaceAllocator;
import xyz.melodysky.pipeline.MainlinePipeline;
import xyz.melodysky.pipeline.MainlinePipelineResult;
import xyz.melodysky.toolchain.NativeBuildPlan;
import xyz.melodysky.toolchain.NativeBuildPlanner;
import xyz.melodysky.toolchain.NativeBuildTargetPreflight;
import xyz.melodysky.toolchain.NativeLibraryName;
import xyz.melodysky.toolchain.ToolchainDiagnostics;
import xyz.melodysky.toolchain.ZigBuildException;

public final class J2llCli {
    private static final String VERSION = "j2ll 0.1.0-beta";
    private static final CliDiagnostics CLI_DIAGNOSTICS = new CliDiagnostics();
    private static final CliReportWriter CLI_REPORTS = new CliReportWriter();

    private J2llCli() {
    }

    public static void main(String[] args) throws IOException {
        AnsiConsole.systemInstall();
        int code;
        try {
            code = run(args, System.out, System.err);
        } finally {
            AnsiConsole.systemUninstall();
        }
        if (code != 0) {
            System.exit(code);
        }
    }

    public static int run(String[] args, PrintStream out, PrintStream err) throws IOException {
        CliParseResult parsed = new CliArgumentsParser().parse(args);
        if (parsed.hasErrors() || parsed.options().isEmpty()) {
            parsed.errors().forEach(error -> err.println("error=" + error));
            err.print(helpText());
            return 2;
        }
        CliOptions options = parsed.options().orElseThrow();
        if (options.helpRequested()) {
            out.print(helpText());
            return 0;
        }
        if (options.versionRequested()) {
            out.println(VERSION);
            return 0;
        }

        CliConfigResolver configResolver = new CliConfigResolver();
        ConfigLoadResult loaded = configResolver.load(options.configPath());
        if (options.mode() == CliMode.VALIDATE) {
            return validate(options.configPath(), loaded, out, err);
        }

        Path workspace;
        try {
            workspace = new BuildWorkspaceAllocator().create(
                    configResolver.outputDirectory(options.configPath(), loaded));
        } catch (IOException exception) {
            err.println("CONFIG INVALID_PATH: cannot create build workspace: " + exception.getMessage());
            return 2;
        }
        if (loaded.hasErrors() || loaded.config().isEmpty()) {
            CLI_REPORTS.writeConfigFailure(workspace, loaded, options.mode());
            printConfigFailure(workspace, loaded, err);
            return 2;
        }

        ResolvedConfig config = new CliConfigOverrides().applyDebug(
                loaded.config().orElseThrow(),
                options.debug() && options.mode() == CliMode.BUILD);
        return options.mode() == CliMode.DRY_RUN
                ? dryRun(options.configPath(), workspace, config, loaded, out, err)
                : build(config, workspace, out, err);
    }

    private static int build(ResolvedConfig config, Path workspace, PrintStream out, PrintStream err)
            throws IOException {
        LegacyProgressRenderer progress = LegacyProgressRenderer.forCli(err);
        MainlinePipelineResult result;
        try {
            result = new MainlinePipeline().run(config, workspace, progress);
        } catch (IOException exception) {
            progress.finished(false);
            Diagnostic diagnostic = Diagnostic.error(
                    DiagnosticStage.NATIVE_LINK,
                    ToolchainDiagnostics.ZIG_TARGET_UNBUILDABLE,
                    exception.getMessage());
            CLI_REPORTS.writeNativeLinkFailure(
                    workspace,
                    config,
                    java.util.List.of(diagnostic),
                    exception instanceof ZigBuildException zigFailure ? zigFailure : null);
            err.println(CLI_DIAGNOSTICS.primaryFailure(java.util.List.of(diagnostic)));
            CLI_DIAGNOSTICS.primaryHint(java.util.List.of(diagnostic)).ifPresent(hint -> err.println("hint=" + hint));
            err.println("reportsDir=" + workspace.resolve("reports"));
            err.println("summaryReport=" + workspace.resolve("reports/summary.json"));
            err.println("reportIndex=" + workspace.resolve("reports/index.json"));
            return 4;
        } catch (RuntimeException exception) {
            progress.finished(false);
            throw exception;
        }
        progress.finished(result.successful());
        if (result.successful()) {
            out.println("outputJar=" + result.outputJar());
            out.println("reportsDir=" + workspace.resolve("reports"));
            out.println("summaryReport=" + workspace.resolve("reports/summary.json"));
            out.println("reportIndex=" + workspace.resolve("reports/index.json"));
            return 0;
        }
        int exitCode = exitCodeForDiagnostics(result.diagnostics());
        err.println(CLI_DIAGNOSTICS.primaryFailure(result.diagnostics()));
        CLI_DIAGNOSTICS.primaryHint(result.diagnostics()).ifPresent(hint -> err.println("hint=" + hint));
        if (exitCode == 7) {
            formatReadinessFailureDetails(workspace).forEach(err::println);
        }
        err.println("reportsDir=" + workspace.resolve("reports"));
        err.println("summaryReport=" + workspace.resolve("reports/summary.json"));
        err.println("reportIndex=" + workspace.resolve("reports/index.json"));
        return exitCode;
    }

    private static int validate(
            Path configPath,
            ConfigLoadResult config,
            PrintStream out,
            PrintStream err) {
        if (config.hasErrors() || config.config().isEmpty()) {
            err.println("CONFIG CONFIG_VALIDATION_FAILED: config validation failed");
            config.diagnostics().stream()
                    .filter(diagnostic -> diagnostic.severity().wireName().equals("error"))
                    .sorted()
                    .findFirst()
                    .ifPresent(diagnostic -> {
                        err.println("reason=" + diagnostic.code().value() + " " + diagnostic.message());
                        CLI_DIAGNOSTICS.primaryHint(java.util.List.of(diagnostic))
                                .ifPresent(hint -> err.println("hint=" + hint));
                    });
            return 2;
        }
        out.println("config=ok");
        out.println("configPath=" + configPath);
        return 0;
    }

    private static int dryRun(
            Path configPath,
            Path workspace,
            ResolvedConfig config,
            ConfigLoadResult loaded,
            PrintStream out,
            PrintStream err) throws IOException {
        String libraryName = NativeLibraryName.resolve(config.libraryName(), config.protection().seed());
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

        CLI_REPORTS.writeDryRun(
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
            err.println(CLI_DIAGNOSTICS.primaryFailure(diagnostics.diagnostics()));
            CLI_DIAGNOSTICS.primaryHint(diagnostics.diagnostics()).ifPresent(hint -> err.println("hint=" + hint));
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
                  j2ll [--config <config.json>] [--validate | --dry-run] [--debug]

                modes:
                  --validate  check config only; do not create a build workspace
                  --dry-run   check config, selectors, and targets without building native artifacts
                  (default)   build into <outputDirectory>/build_yyyy-MM-dd_HH-mm-ss[-n]

                options:
                  --config    config file path (default: Config.json)
                  --debug     write CFG, runtime, SSA, LLVM, and C intermediate artifacts

                exit codes: 0 success, 2 config, 3 frontend/lowering, 4 native target/toolchain,
                            5 packaging/signing, 6 artifact audit, 7 readiness, 1 unexpected
                """;
    }

    static java.util.List<String> formatReadinessFailureDetails(Path workspace) throws IOException {
        return CLI_DIAGNOSTICS.readinessFailureDetails(workspace);
    }

    static int exitCodeForDiagnostics(java.util.List<Diagnostic> diagnostics) {
        return CLI_DIAGNOSTICS.exitCodeFor(diagnostics);
    }

    private static void printConfigFailure(Path workspace, ConfigLoadResult config, PrintStream err) {
        err.println("config validation failed; diagnostics written to "
                + workspace.resolve("reports/diagnostics.json"));
        CLI_DIAGNOSTICS.primaryHint(config.diagnostics()).ifPresent(hint -> err.println("hint=" + hint));
        err.println("reportsDir=" + workspace.resolve("reports"));
        err.println("summaryReport=" + workspace.resolve("reports/summary.json"));
        err.println("reportIndex=" + workspace.resolve("reports/index.json"));
    }
}
