package xyz.melodysky.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import xyz.melodysky.diagnostic.Diagnostic;
import xyz.melodysky.diagnostic.DiagnosticCode;
import xyz.melodysky.diagnostic.DiagnosticStage;
import xyz.melodysky.testsupport.AsmFixtureBuilder;
import xyz.melodysky.testsupport.FakeManagedZig;
import xyz.melodysky.testsupport.JvmRunner;
import xyz.melodysky.toolchain.HostPlatform;
import xyz.melodysky.toolchain.J2llHomeResolver;
import xyz.melodysky.toolchain.TargetTriple;
import xyz.melodysky.toolchain.ZigArchiveResolver;

class J2llCliTest implements Opcodes {
    @TempDir
    Path temp;

    @Test
    void invalidConfigWritesDiagnosticsAndDoesNotEnterPipeline() throws Exception {
        Path config = temp.resolve("config.json");
        Files.writeString(config, """
                {
                  "jarFile": "input.txt",
                  "whiteList": ["pkg/Foo#doIt!(V)V"]
                }
                """);

        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = J2llCli.run(
                new String[] {"--config", config.toString()},
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));

        assertEquals(2, code);
        String stderr = err.toString(StandardCharsets.UTF_8);
        Path workspace = pathValue(stderr, "reportsDir").getParent();
        assertTrue(Files.exists(workspace.resolve("reports/diagnostics.json")));
        String diagnostics = Files.readString(workspace.resolve("reports/diagnostics.json"));
        assertTrue(diagnostics.contains("\"code\": \"MISSING_REQUIRED_FIELD\""), diagnostics);
        String failure = Files.readString(workspace.resolve("reports/failure-report.json"));
        assertTrue(failure.contains("\"finalArtifactWritten\": false"), failure);
        assertTrue(failure.contains("\"stage\": \"CONFIG\""), failure);
        assertTrue(failure.contains("\"reasonCode\": \"MISSING_REQUIRED_FIELD\""), failure);
        assertFieldInternalizationEvidence(workspace);
        assertTrue(Files.readString(workspace.resolve("reports/summary.json")).contains("\"finalArtifactWritten\": false"));
        assertTrue(Files.readString(workspace.resolve("reports/release-readiness.json")).contains("\"finalArtifactWritten\": false"));
        assertFalse(Files.exists(workspace.resolve("config-failed.jar")));
        assertFalse(Files.exists(workspace.resolve("native")));
        assertFalse(Files.exists(workspace.resolve("build.zig")));
        assertTrue(Files.isRegularFile(workspace.resolve("reports/summary.json")));
        assertTrue(Files.isRegularFile(workspace.resolve("reports/index.json")));
        assertTrue(stderr.contains("config validation failed"));
        assertTrue(stderr.contains("hint="));
        assertTrue(stderr.contains("summaryReport="));
        assertTrue(stderr.contains("reportIndex="));
    }

    @Test
    void frontendFailureFinishesProgressBeforeMachineParseableFailurePaths() throws Exception {
        Path inputJar = temp.resolve("broken-input.jar");
        writeJar(inputJar, Map.of("pkg/Broken.class", new byte[] {0, 1, 2, 3}));
        Path config = temp.resolve("broken-config.json");
        Files.writeString(config, configJson(inputJar, "[\"pkg/Broken\"]", targetJson()));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        int code = J2llCli.run(
                new String[] {"--config", config.toString()},
                new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));

        String stdout = out.toString(StandardCharsets.UTF_8);
        String stderr = err.toString(StandardCharsets.UTF_8);
        assertEquals(3, code, stderr);
        assertTrue(stdout.isEmpty(), stdout);
        assertTrue(stderr.contains("[01/13] Inspecting input  broken-input.jar"), stderr);
        assertTrue(stderr.contains("[02/13] Parsing classes  broken-input.jar"), stderr);
        assertFalse(stderr.contains("BUILD FAILED"), stderr);
        assertTrue(stderr.indexOf("[02/13] Parsing classes")
                < stderr.indexOf("PARSE CLASS_PARSE_FAILED"), stderr);
        assertTrue(stderr.lines().anyMatch(line -> line.startsWith("reportsDir=")), stderr);
        assertTrue(stderr.lines().anyMatch(line -> line.startsWith("summaryReport=")), stderr);
        assertTrue(stderr.lines().anyMatch(line -> line.startsWith("reportIndex=")), stderr);
    }

    @Test
    void buildWithoutNativeWorkShowsEveryPlainProgressStage() throws Exception {
        Path inputJar = temp.resolve("resources-only.jar");
        writeJar(inputJar, Map.of(
                "META-INF/example.txt",
                "resource".getBytes(StandardCharsets.UTF_8)));
        Path config = temp.resolve("resources-config.json");
        Files.writeString(config, configJson(inputJar, "[]", targetJson()));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        int code = J2llCli.run(
                new String[] {"--config", config.toString()},
                new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));

        String stdout = out.toString(StandardCharsets.UTF_8);
        String stderr = err.toString(StandardCharsets.UTF_8);
        assertEquals(0, code, stderr);
        assertTrue(stdout.contains("outputJar="), stdout);
        assertTrue(stderr.contains("[01/13] Inspecting input"), stderr);
        assertTrue(stderr.contains("[02/13] Parsing classes"), stderr);
        assertTrue(stderr.contains("[03/13] Selecting methods"), stderr);
        assertTrue(stderr.contains("[04/13] Analyzing program"), stderr);
        assertTrue(stderr.contains("[05/13] Lowering and protecting methods"), stderr);
        assertTrue(stderr.contains("[06/13] Planning native implementations"), stderr);
        assertTrue(stderr.contains("[07/13] Emitting LLVM"), stderr);
        assertTrue(stderr.contains("[08/13] Writing intermediates"), stderr);
        assertTrue(stderr.contains("[09/13] Checking native targets"), stderr);
        assertTrue(stderr.contains("[10/13] Building native libraries  no native implementations"), stderr);
        assertTrue(stderr.contains("[11/13] Packaging output JAR"), stderr);
        assertTrue(stderr.contains("[12/13] Auditing artifacts"), stderr);
        assertTrue(stderr.contains("[13/13] Writing reports"), stderr);
        assertTrue(stderr.contains("BUILD SUCCESSFUL"), stderr);
        assertFalse(stderr.contains("actionable stages"), stderr);
    }

    @Test
    void usageFailureDoesNotCreateWorkspace() throws Exception {
        int code = J2llCli.run(
                new String[] {"--config"},
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));

        assertEquals(2, code);
        assertFalse(Files.exists(temp.resolve("out")));
    }

    @Test
    void helpAndVersionCommandsAreShortAndSuccessful() throws Exception {
        ByteArrayOutputStream help = new ByteArrayOutputStream();
        int helpCode = J2llCli.run(
                new String[] {"--help"},
                new PrintStream(help, true, StandardCharsets.UTF_8),
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
        ByteArrayOutputStream version = new ByteArrayOutputStream();
        int versionCode = J2llCli.run(
                new String[] {"--version"},
                new PrintStream(version, true, StandardCharsets.UTF_8),
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));

        assertEquals(0, helpCode);
        assertTrue(help.toString(StandardCharsets.UTF_8)
                .contains("j2ll [--config <config.json>] [--validate | --dry-run] [--debug]"));
        assertTrue(help.toString(StandardCharsets.UTF_8)
                .contains("build into <outputDirectory>/build_yyyy-MM-dd_HH-mm-ss[-n]"));
        assertEquals(0, versionCode);
        assertTrue(version.toString(StandardCharsets.UTF_8).startsWith("j2ll "));
    }

    @Test
    void validateCommandOnlyChecksConfig() throws Exception {
        Path inputJar = temp.resolve("validate-input.jar");
        Path config = temp.resolve("validate-config.json");
        Files.writeString(config, configJson(inputJar, "[\"pkg/CorpusMath#add!(II)I\"]", targetJson()));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int code = J2llCli.run(
                new String[] {"--config", config.toString(), "--validate"},
                new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));

        assertEquals(0, code);
        assertTrue(out.toString(StandardCharsets.UTF_8).contains("config=ok"));
        assertFalse(Files.exists(temp.resolve("out")));
    }

    @Test
    void buildPromptsAndUsesUserApprovedCurrentJarOnlyScope() throws Exception {
        Path inputJar = temp.resolve("current-jar-only.jar");
        writeJar(inputJar, Map.of(
                "META-INF/example.txt",
                "resource".getBytes(StandardCharsets.UTF_8)));
        Path config = temp.resolve("current-jar-only.json");
        Files.writeString(
                config,
                configJson(inputJar, "[]", targetJson())
                        .replace("\"classPath\": []", "\"classPath\": [\"missing-dependency.jar\"]")
                        .replace("\"fieldInternalization\": false", "\"fieldInternalization\": true"));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        int code = J2llCli.run(
                new String[] {"--config", config.toString()},
                new ByteArrayInputStream("invalid\nY\n".getBytes(StandardCharsets.UTF_8)),
                new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));

        String stdout = out.toString(StandardCharsets.UTF_8);
        String stderr = err.toString(StandardCharsets.UTF_8);
        assertEquals(0, code, stderr);
        assertEquals(
                2,
                countOccurrences(
                        stderr,
                        "fieldInternalization requires CLOSED_WORLD, continue? (Y/N)"));
        assertTrue(stderr.contains("Please answer Y or N."), stderr);
        assertTrue(stderr.contains(
                "analysisScope=fieldInternalization:currentJarOnlyUserApproved"), stderr);
        Path workspace = pathValue(stdout, "reportsDir").getParent();
        String fieldReport = Files.readString(
                workspace.resolve("reports/field-internalization-report.json"));
        assertTrue(fieldReport.contains("\"configuredWorldModel\": \"PARTIAL_WORLD\""), fieldReport);
        assertTrue(fieldReport.contains("\"scope\": \"CURRENT_JAR_ONLY\""), fieldReport);
        assertTrue(fieldReport.contains("\"authorization\": \"USER_CONFIRMED\""), fieldReport);
        assertTrue(fieldReport.contains("\"classPathAnalyzed\": false"), fieldReport);
        assertTrue(fieldReport.contains(
                "\"externalObserverPolicy\": \"OUT_OF_SCOPE_USER_ACCEPTED\""), fieldReport);
        String diagnostics = Files.readString(workspace.resolve("reports/diagnostics.json"));
        assertTrue(diagnostics.contains(
                "\"code\": \"WHOLE_PROGRAM_CURRENT_JAR_ONLY_USER_APPROVED\""), diagnostics);
        assertTrue(Files.readString(workspace.resolve("config.resolved.json"))
                .contains("\"worldModel\": \"PARTIAL_WORLD\""));
    }

    @Test
    void buildAnswerNoExitsBeforeCreatingWorkspace() throws Exception {
        Path config = temp.resolve("declined-current-jar-only.json");
        Files.writeString(
                config,
                configJson(temp.resolve("input.jar"), "[]", targetJson())
                        .replace("\"fieldInternalization\": false", "\"fieldInternalization\": true"));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        int code = J2llCli.run(
                new String[] {"--config", config.toString()},
                new ByteArrayInputStream("N\n".getBytes(StandardCharsets.UTF_8)),
                new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));

        assertEquals(2, code);
        assertTrue(out.toString(StandardCharsets.UTF_8).isEmpty());
        assertTrue(err.toString(StandardCharsets.UTF_8)
                .contains("cancelled=fieldInternalization requires CLOSED_WORLD"));
        assertFalse(Files.exists(temp.resolve("out")));
    }

    @Test
    void buildEndOfInputFailsClosedBeforeCreatingWorkspace() throws Exception {
        Path config = temp.resolve("eof-current-jar-only.json");
        Files.writeString(
                config,
                configJson(temp.resolve("input.jar"), "[]", targetJson())
                        .replace("\"fieldInternalization\": false", "\"fieldInternalization\": true"));
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        int code = J2llCli.run(
                new String[] {"--config", config.toString()},
                InputStream.nullInputStream(),
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));

        assertEquals(2, code);
        assertTrue(err.toString(StandardCharsets.UTF_8)
                .contains("confirmation was not provided"));
        assertFalse(Files.exists(temp.resolve("out")));
    }

    @Test
    void validateDoesNotConsumeWholeProgramConfirmationInput() throws Exception {
        Path config = temp.resolve("validate-current-jar-only.json");
        Files.writeString(
                config,
                configJson(temp.resolve("input.jar"), "[]", targetJson())
                        .replace("\"fieldInternalization\": false", "\"fieldInternalization\": true"));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        int code = J2llCli.run(
                new String[] {"--config", config.toString(), "--validate"},
                inputThatMustNotBeRead(),
                new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));

        assertEquals(0, code);
        assertTrue(out.toString(StandardCharsets.UTF_8).contains("config=ok"));
        assertTrue(err.toString(StandardCharsets.UTF_8)
                .contains("FIELD_INTERNALIZATION_REQUIRES_CLOSED_WORLD"));
        assertFalse(err.toString(StandardCharsets.UTF_8).contains("(Y/N)"));
        assertFalse(Files.exists(temp.resolve("out")));
    }

    @Test
    void validateCommandReportsConfigFailureWithoutPipelineArtifacts() throws Exception {
        Path config = temp.resolve("validate-bad.json");
        Files.writeString(config, """
                {
                  "schemaVersion": 1,
                  "jarFile": "bad.txt"
                }
                """);

        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = J2llCli.run(
                new String[] {"--config", config.toString(), "--validate"},
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));

        assertEquals(2, code);
        assertTrue(err.toString(StandardCharsets.UTF_8).contains("CONFIG_VALIDATION_FAILED"));
        assertTrue(err.toString(StandardCharsets.UTF_8).contains("hint="));
        assertFalse(Files.exists(temp.resolve("reports")));
    }

    @Test
    void dryRunValidatesSelectorsAndTargetsWithoutNativeBuildOrFinalJar() throws Exception {
        Path inputJar = temp.resolve("dry-run-input.jar");
        writeJar(inputJar, Map.of(
                "pkg/CorpusMath.class", AsmFixtureBuilder.classWithAddMethod("pkg/CorpusMath"),
                "pkg/CliMain.class", cliMainClass()));
        Path config = temp.resolve("dry-run-config.json");
        Files.writeString(config, configJson(inputJar, "[\"pkg/CorpusMath#add!(II)I\"]", targetJson()));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int code = J2llCli.run(
                new String[] {"--config", config.toString(), "--dry-run"},
                new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));

        assertEquals(0, code);
        assertTrue(out.toString(StandardCharsets.UTF_8).contains("dryRunReport="));
        assertTrue(out.toString(StandardCharsets.UTF_8).contains("summaryReport="));
        assertTrue(out.toString(StandardCharsets.UTF_8).contains("reportIndex="));
        Path dryRunReport = pathValue(out.toString(StandardCharsets.UTF_8), "dryRunReport");
        Path workspace = dryRunReport.getParent().getParent();
        assertFalse(Files.exists(workspace.resolve(inputJar.getFileName())));
        assertFalse(Files.exists(workspace.resolve("native")));
        assertFieldInternalizationEvidence(workspace);
        String dryRun = Files.readString(workspace.resolve("reports/dry-run-report.json"));
        assertTrue(dryRun.contains("\"inputJarParsed\": true"), dryRun);
        assertTrue(dryRun.contains("\"requestedMethodCount\": 1"), dryRun);
        assertTrue(dryRun.contains("\"nativeBuildInvoked\": false"), dryRun);
        String packaging = Files.readString(workspace.resolve("reports/packaging-report.json"));
        assertTrue(packaging.contains("\"targetArtifacts\""), packaging);
        assertTrue(Files.readString(workspace.resolve("reports/summary.json")).contains("\"finalArtifactWritten\": false"));
        assertTrue(Files.readString(workspace.resolve("reports/release-readiness.json")).contains("\"finalArtifactWritten\": false"));
    }

    @Test
    void dryRunReportsPendingConfirmationWithoutReadingInput() throws Exception {
        Path inputJar = temp.resolve("dry-run-current-jar-only.jar");
        writeJar(inputJar, Map.of(
                "META-INF/example.txt",
                "resource".getBytes(StandardCharsets.UTF_8)));
        Path config = temp.resolve("dry-run-current-jar-only.json");
        Files.writeString(
                config,
                configJson(inputJar, "[]", targetJson())
                        .replace("\"fieldInternalization\": false", "\"fieldInternalization\": true"));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        int code = J2llCli.run(
                new String[] {"--config", config.toString(), "--dry-run"},
                inputThatMustNotBeRead(),
                new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));

        assertEquals(0, code, err.toString(StandardCharsets.UTF_8));
        assertFalse(err.toString(StandardCharsets.UTF_8).contains("(Y/N)"));
        Path workspace = pathValue(out.toString(StandardCharsets.UTF_8), "reportsDir").getParent();
        String diagnostics = Files.readString(workspace.resolve("reports/diagnostics.json"));
        assertTrue(diagnostics.contains(
                "\"code\": \"FIELD_INTERNALIZATION_REQUIRES_CLOSED_WORLD\""), diagnostics);
        assertTrue(diagnostics.contains("\"decision\": \"confirmationRequired\""), diagnostics);
    }

    @Test
    void closedWorldBuildDoesNotPromptOrReadConfirmationInput() throws Exception {
        Path inputJar = temp.resolve("closed-world.jar");
        writeJar(inputJar, Map.of(
                "META-INF/example.txt",
                "resource".getBytes(StandardCharsets.UTF_8)));
        Path config = temp.resolve("closed-world.json");
        Files.writeString(
                config,
                configJson(inputJar, "[]", targetJson())
                        .replace("\"worldModel\": \"PARTIAL_WORLD\"", "\"worldModel\": \"CLOSED_WORLD\"")
                        .replace("\"fieldInternalization\": false", "\"fieldInternalization\": true"));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        int code = J2llCli.run(
                new String[] {"--config", config.toString()},
                inputThatMustNotBeRead(),
                new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));

        assertEquals(0, code, err.toString(StandardCharsets.UTF_8));
        assertFalse(err.toString(StandardCharsets.UTF_8).contains("(Y/N)"));
        Path workspace = pathValue(out.toString(StandardCharsets.UTF_8), "reportsDir").getParent();
        String report = Files.readString(
                workspace.resolve("reports/field-internalization-report.json"));
        assertTrue(report.contains("\"configuredWorldModel\": \"CLOSED_WORLD\""), report);
        assertTrue(report.contains(
                "\"scope\": \"INPUT_JAR_AND_CONFIGURED_CLASSPATH\""), report);
        assertTrue(report.contains("\"authorization\": \"CONFIG_SATISFIED\""), report);
        assertTrue(report.contains("\"classPathAnalyzed\": true"), report);
    }

    @Test
    void dryRunAcceptsRequiredCrossTargetWithoutInvokingNativeBuild() throws Exception {
        Path inputJar = temp.resolve("dry-run-cross-target.jar");
        writeJar(inputJar, Map.of("pkg/CorpusMath.class", AsmFixtureBuilder.classWithAddMethod("pkg/CorpusMath")));
        Path config = temp.resolve("dry-run-cross-target-config.json");
        Files.writeString(config, configJson(inputJar, "[\"pkg/CorpusMath#add!(II)I\"]", hostPlusCrossTargetJson()));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = J2llCli.run(
                new String[] {"--config", config.toString(), "--dry-run"},
                new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));

        assertEquals(0, code);
        String stdout = out.toString(StandardCharsets.UTF_8);
        String stderr = err.toString(StandardCharsets.UTF_8);
        assertTrue(stdout.contains("dryRunReport="));
        assertFalse(stderr.contains("ZIG_TARGET_UNBUILDABLE"));
        Path workspace = pathValue(stdout, "reportsDir").getParent();
        assertFalse(Files.exists(workspace.resolve("reports/failure-report.json")));
        assertTrue(Files.readString(workspace.resolve("reports/dry-run-report.json"))
                .contains("\"nativeBuildInvoked\": false"));
        assertTrue(Files.readString(workspace.resolve("reports/packaging-report.json"))
                .contains("\"ZIG_CROSS_TARGET_SUPPORTED\""));
        assertTrue(Files.readString(workspace.resolve("reports/summary.json")).contains("\"finalArtifactWritten\": false"));
        assertTrue(Files.readString(workspace.resolve("reports/release-readiness.json")).contains("\"finalArtifactWritten\": false"));
        assertFalse(Files.exists(workspace.resolve(inputJar.getFileName())));
    }

    @Test
    void buildCommandWritesShortSuccessOutputAndRunnableJar() throws Exception {
        Path inputJar = temp.resolve("cli-input.jar");
        writeJar(inputJar, Map.of(
                "pkg/CorpusMath.class", AsmFixtureBuilder.classWithAddMethod("pkg/CorpusMath"),
                "pkg/CliMain.class", cliMainClass()));
        Path config = temp.resolve("config-success.json");
        Files.writeString(config, configJson(inputJar, "[\"pkg/CorpusMath#add!(II)I\"]", targetJson()));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code;
        try (AutoCloseable ignored = FakeManagedZig.installAndUse(temp.resolve("j2ll-home-success"))) {
            code = J2llCli.run(
                    new String[] {"--config", config.toString()},
                    new PrintStream(out, true, StandardCharsets.UTF_8),
                    new PrintStream(err, true, StandardCharsets.UTF_8));
        }

        assertEquals(0, code, err.toString(StandardCharsets.UTF_8));
        String stdout = out.toString(StandardCharsets.UTF_8);
        String stderr = err.toString(StandardCharsets.UTF_8);
        assertTrue(stdout.contains("outputJar="), stdout);
        assertTrue(stdout.contains("reportsDir="), stdout);
        assertTrue(stdout.contains("summaryReport="), stdout);
        assertTrue(stdout.contains("reportIndex="), stdout);
        assertFalse(stdout.contains("[01/13]"), stdout);
        assertTrue(stderr.contains("[01/13] Inspecting input  cli-input.jar"), stderr);
        assertTrue(stderr.contains("[05/13] Lowering and protecting methods"), stderr);
        assertTrue(stderr.contains("[10/13] Building native libraries  1 target"), stderr);
        assertTrue(stderr.contains("BUILD SUCCESSFUL"), stderr);
        assertFalse(stderr.replace("\r\n", "\n").contains("\r"), stderr);
        assertFalse(stderr.contains("\"diagnostics\""), stderr);
        Path outputJar = pathValue(stdout, "outputJar");
        Path workspace = outputJar.getParent();
        assertEquals(inputJar.getFileName(), outputJar.getFileName());
        assertTrue(workspace.getFileName().toString().startsWith("build_"), workspace.toString());
        assertTrue(Files.isRegularFile(outputJar));
        var run = new JvmRunner().run(outputJar, "pkg.CliMain", List.of());
        assertEquals(0, run.exitCode(), run.stderr());
        assertEquals("42\n", run.stdout());
        try (java.util.jar.JarFile jar = new java.util.jar.JarFile(outputJar.toFile(), false)) {
            assertTrue(jar.getJarEntry("META-INF/j2ll/build-info.json") != null);
            assertTrue(jar.getJarEntry("META-INF/j2ll/native-libraries.json") != null);
            assertTrue(jar.getJarEntry("META-INF/j2ll/reports-manifest.json") != null);
            String buildInfo = new String(
                    jar.getInputStream(jar.getJarEntry("META-INF/j2ll/build-info.json")).readAllBytes(),
                    StandardCharsets.UTF_8);
            assertTrue(buildInfo.contains("\"protectionSeedHash\""), buildInfo);
            assertFalse(buildInfo.contains("cli-seed"), buildInfo);
        }
    }

    @Test
    void buildCommandReportsArtifactAuditFailureWithExitCodeSixAndNoFinalJar() throws Exception {
        Path inputJar = temp.resolve("cli-audit-fail.jar");
        writeJar(inputJar, Map.of(
                "pkg/LeakyBox.class", leakyBoxClass(),
                "pkg/LeakyMain.class", leakyMainClass(),
                "leak.txt", "cli-template-leak".getBytes(StandardCharsets.UTF_8)));
        Path config = temp.resolve("config-audit-fail.json");
        Files.writeString(config, configJson(inputJar, "[\"pkg/LeakyBox#<init>!()V\"]", targetJson()));

        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code;
        try (AutoCloseable ignored = FakeManagedZig.installAndUse(temp.resolve("j2ll-home-audit-fail"))) {
            code = J2llCli.run(
                    new String[] {"--config", config.toString()},
                    new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
                    new PrintStream(err, true, StandardCharsets.UTF_8));
        }

        assertEquals(6, code, err.toString(StandardCharsets.UTF_8));
        String stderr = err.toString(StandardCharsets.UTF_8);
        assertTrue(stderr.contains("ARTIFACT_AUDIT"));
        assertTrue(stderr.contains("ARTIFACT_AUDIT_FAILED"));
        assertTrue(stderr.contains("hint="));
        assertTrue(stderr.contains("reportsDir="));
        assertTrue(stderr.contains("summaryReport="));
        assertTrue(stderr.contains("reportIndex="));
        Path workspace = pathValue(stderr, "reportsDir").getParent();
        assertFalse(Files.exists(workspace.resolve(inputJar.getFileName())));
        String failure = Files.readString(workspace.resolve("reports/failure-report.json"));
        assertTrue(failure.contains("\"stage\": \"ARTIFACT_AUDIT\""), failure);
        assertTrue(failure.contains("\"primaryDiagnosticId\": \"ARTIFACT_AUDIT:ARTIFACT_AUDIT_FAILED\""), failure);
        assertTrue(failure.contains("\"finalArtifactWritten\": false"), failure);
        assertTrue(Files.readString(workspace.resolve("reports/summary.json")).contains("\"finalArtifactWritten\": false"));
        assertTrue(Files.readString(workspace.resolve("reports/release-readiness.json")).contains("\"finalArtifactWritten\": false"));
    }

    @Test
    void buildCommandReportsInjectedCrossTargetBuildFailureWithExitCodeFour() throws Exception {
        Path inputJar = temp.resolve("cli-cross-target-failure.jar");
        writeJar(inputJar, Map.of(
                "pkg/CorpusMath.class", AsmFixtureBuilder.classWithAddMethod("pkg/CorpusMath"),
                "pkg/CliMain.class", cliMainClass()));
        Path config = temp.resolve("config-cross-target-failure.json");
        Files.writeString(config, configJson(inputJar, "[\"pkg/CorpusMath#add!(II)I\"]", hostPlusCrossTargetJson()));

        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code;
        try (AutoCloseable ignored = FakeManagedZig.installAndUse(temp.resolve("j2ll-home-cross-target-failure"))) {
            code = J2llCli.run(
                    new String[] {"--config", config.toString()},
                    new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
                    new PrintStream(err, true, StandardCharsets.UTF_8));
        }

        assertEquals(4, code, err.toString(StandardCharsets.UTF_8));
        String stderr = err.toString(StandardCharsets.UTF_8);
        assertTrue(stderr.contains("ZIG_TARGET_UNBUILDABLE"));
        assertTrue(stderr.contains("hint="));
        assertTrue(stderr.contains("summaryReport="));
        assertTrue(stderr.contains("reportIndex="));
        Path workspace = pathValue(stderr, "reportsDir").getParent();
        String packaging = Files.readString(workspace.resolve("reports/packaging-report.json"));
        assertFieldInternalizationEvidence(workspace);
        assertTrue(packaging.contains("\"failureKind\": \"zigBuildFailed\""), packaging);
        assertTrue(packaging.contains("\"requiredCapability\": \"managedZig0.15.2CrossTargetSharedLibrary\""), packaging);
        assertTrue(Files.readString(workspace.resolve("native/zig-workspace/build.zig"))
                .contains("const target_" + crossTarget().safeSymbol()));
        assertTrue(Files.readString(workspace.resolve("reports/failure-report.json")).contains("\"finalArtifactWritten\": false"));
        assertTrue(Files.readString(workspace.resolve("reports/summary.json")).contains("\"finalArtifactWritten\": false"));
        assertTrue(Files.readString(workspace.resolve("reports/release-readiness.json")).contains("\"finalArtifactWritten\": false"));
        assertFalse(Files.exists(workspace.resolve(inputJar.getFileName())));
    }

    @Test
    void buildCommandReportsZigChecksumFailureWithExitCodeFour() throws Exception {
        Path inputJar = temp.resolve("cli-zig-checksum.jar");
        writeJar(inputJar, Map.of(
                "pkg/CorpusMath.class", AsmFixtureBuilder.classWithAddMethod("pkg/CorpusMath"),
                "pkg/CliMain.class", cliMainClass()));
        Path config = temp.resolve("config-zig-checksum.json");
        Files.writeString(config, configJson(inputJar, "[\"pkg/CorpusMath#add!(II)I\"]", targetJson()));
        Path j2llHome = temp.resolve("j2ll-home-checksum");
        Files.createDirectories(j2llHome);
        String archiveName = new ZigArchiveResolver().currentHostArchive().archiveName();
        Files.writeString(j2llHome.resolve(archiveName), "corrupt-zig-archive");

        ByteArrayOutputStream err = new ByteArrayOutputStream();
        String previous = System.getProperty(J2llHomeResolver.OVERRIDE_PROPERTY);
        int code;
        try {
            System.setProperty(J2llHomeResolver.OVERRIDE_PROPERTY, j2llHome.toString());
            code = J2llCli.run(
                    new String[] {"--config", config.toString()},
                    new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
                    new PrintStream(err, true, StandardCharsets.UTF_8));
        } finally {
            if (previous == null) {
                System.clearProperty(J2llHomeResolver.OVERRIDE_PROPERTY);
            } else {
                System.setProperty(J2llHomeResolver.OVERRIDE_PROPERTY, previous);
            }
        }

        assertEquals(4, code, err.toString(StandardCharsets.UTF_8));
        String stderr = err.toString(StandardCharsets.UTF_8);
        assertTrue(stderr.contains("checksum mismatch"), stderr);
        assertTrue(stderr.contains("hint="), stderr);
        Path workspace = pathValue(stderr, "reportsDir").getParent();
        assertFieldInternalizationEvidence(workspace);
        assertTrue(Files.readString(workspace.resolve("reports/failure-report.json")).contains("\"finalArtifactWritten\": false"));
        assertTrue(Files.readString(workspace.resolve("reports/summary.json")).contains("\"finalArtifactWritten\": false"));
        assertTrue(Files.readString(workspace.resolve("reports/release-readiness.json")).contains("\"finalArtifactWritten\": false"));
        assertFalse(Files.exists(workspace.resolve(inputJar.getFileName())));
    }

    @Test
    void diagnosticStagesMapToDocumentedExitCodes() {
        assertEquals(2, exitCode(DiagnosticStage.CONFIG, "CONFIG_FAILED"));
        assertEquals(3, exitCode(DiagnosticStage.LOWERING, "LOWERING_FAILED"));
        assertEquals(4, exitCode(DiagnosticStage.NATIVE_LINK, "ZIG_TARGET_UNBUILDABLE"));
        assertEquals(5, exitCode(DiagnosticStage.PACKAGING, "SIGNATURE_RESIGN_FAILED"));
        assertEquals(6, exitCode(DiagnosticStage.ARTIFACT_AUDIT, "ARTIFACT_AUDIT_FAILED"));
        assertEquals(7, exitCode(DiagnosticStage.RELEASE_READINESS, "RELEASE_READINESS_FAILED"));
        assertEquals(1, exitCode(DiagnosticStage.RUNTIME_ANALYSIS, "INTERNAL_FAILURE"));
    }

    @Test
    void readinessFailureDetailsPrintTopThreeMissingEvidenceOnly() throws Exception {
        Path workspace = temp.resolve("readiness-workspace");
        Files.createDirectories(workspace.resolve("reports"));
        Files.writeString(workspace.resolve("reports/release-readiness.json"), """
                {
                  "schemaVersion": 1,
                  "reportVersion": 1,
                  "status": "failed",
                  "missingEvidence": [
                    {"type": "missingReport", "name": "report:a.json", "reasonCode": "REPORT_MISSING", "detail": "a missing", "reportPath": "reports/a.json"},
                    {"type": "artifactAuditNotPassed", "name": "artifactAudit.status", "reasonCode": "ARTIFACT_AUDIT_FAILED", "detail": "audit failed", "reportPath": "reports/artifact-audit.json"},
                    {"type": "determinismMissing", "name": "releaseSuite.determinism", "reasonCode": "DETERMINISM_EVIDENCE_INCOMPLETE", "detail": "determinism missing", "reportPath": "reports/release-suite-summary.json"},
                    {"type": "targetEvidenceIncomplete", "name": "packaging.targetEvidence", "reasonCode": "TARGET_EVIDENCE_INCOMPLETE", "detail": "target missing", "reportPath": "reports/packaging-report.json"}
                  ],
                  "checks": []
                }
                """);

        List<String> lines = J2llCli.formatReadinessFailureDetails(workspace);

        assertEquals(4, lines.size());
        assertTrue(lines.get(0).contains("releaseReadinessReport="));
        assertTrue(lines.get(1).contains("missingReport REPORT_MISSING a missing"));
        assertTrue(lines.get(2).contains("artifactAuditNotPassed ARTIFACT_AUDIT_FAILED audit failed"));
        assertTrue(lines.get(3).contains("determinismMissing DETERMINISM_EVIDENCE_INCOMPLETE determinism missing"));
        assertFalse(String.join("\n", lines).contains("target missing"));
    }

    private int exitCode(DiagnosticStage stage, String code) {
        return J2llCli.exitCodeForDiagnostics(List.of(Diagnostic.error(stage, DiagnosticCode.of(code), code)));
    }

    private InputStream inputThatMustNotBeRead() {
        return new InputStream() {
            @Override
            public int read() {
                throw new AssertionError("confirmation input must not be read");
            }
        };
    }

    private int countOccurrences(String value, String needle) {
        int count = 0;
        int offset = 0;
        while ((offset = value.indexOf(needle, offset)) >= 0) {
            count++;
            offset += needle.length();
        }
        return count;
    }

    private void assertFieldInternalizationEvidence(Path workspace) throws Exception {
        Path report = workspace.resolve("reports/field-internalization-report.json");
        assertTrue(Files.isRegularFile(report), "missing " + report);
        String json = Files.readString(report);
        assertTrue(json.contains("\"decisions\""), json);
        String index = Files.readString(workspace.resolve("reports/index.json"));
        assertTrue(index.contains("reports/field-internalization-report.json"), index);
    }

    private Path pathValue(String output, String key) {
        String prefix = key + "=";
        return output.lines()
                .filter(line -> line.startsWith(prefix))
                .map(line -> Path.of(line.substring(prefix.length())))
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing " + prefix + " in output:\n" + output));
    }

    private String configJson(Path inputJar, String selectors, String targetJson) {
        return """
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
                    "seed": "cli-seed",
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
                """.formatted(inputJar.toString().replace('\\', '/'), selectors, targetJson);
    }

    private String targetJson() {
        TargetTriple host = HostPlatform.detect().orElseThrow().target();
        return targetJson(host);
    }

    private String hostPlusCrossTargetJson() {
        TargetTriple host = HostPlatform.detect().orElseThrow().target();
        return targetJson(host, crossTarget());
    }

    private TargetTriple crossTarget() {
        TargetTriple host = HostPlatform.detect().orElseThrow().target();
        return host == TargetTriple.LINUX_X64 ? TargetTriple.MACOS_ARM64 : TargetTriple.LINUX_X64;
    }

    private String targetJson(TargetTriple... enabled) {
        java.util.Set<TargetTriple> targets = java.util.Set.of(enabled);
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
                targets.contains(TargetTriple.WINDOWS_X64),
                targets.contains(TargetTriple.WINDOWS_ARM64),
                targets.contains(TargetTriple.LINUX_X64),
                targets.contains(TargetTriple.LINUX_ARM64),
                targets.contains(TargetTriple.MACOS_X64),
                targets.contains(TargetTriple.MACOS_ARM64));
    }

    private void writeJar(Path jar, Map<String, byte[]> entries) throws Exception {
        Files.createDirectories(jar.getParent());
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .toList()) {
                JarEntry jarEntry = new JarEntry(entry.getKey());
                jarEntry.setTime(0L);
                output.putNextEntry(jarEntry);
                output.write(entry.getValue());
                output.closeEntry();
            }
        }
    }

    private byte[] cliMainClass() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(V17, ACC_PUBLIC | ACC_SUPER, "pkg/CliMain", null, "java/lang/Object", null);
        MethodVisitor constructor = writer.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(ALOAD, 0);
        constructor.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitInsn(RETURN);
        constructor.visitMaxs(0, 0);
        constructor.visitEnd();
        MethodVisitor main = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "main", "([Ljava/lang/String;)V", null, null);
        main.visitCode();
        main.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        main.visitIntInsn(BIPUSH, 19);
        main.visitIntInsn(BIPUSH, 23);
        main.visitMethodInsn(INVOKESTATIC, "pkg/CorpusMath", "add", "(II)I", false);
        main.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(I)V", false);
        main.visitInsn(RETURN);
        main.visitMaxs(0, 0);
        main.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private byte[] leakyBoxClass() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(V17, ACC_PUBLIC | ACC_SUPER, "pkg/LeakyBox", null, "java/lang/Object", null);
        writer.visitField(ACC_PRIVATE, "value", "Ljava/lang/String;", null, null).visitEnd();
        MethodVisitor constructor = writer.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(ALOAD, 0);
        constructor.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitVarInsn(ALOAD, 0);
        constructor.visitLdcInsn("cli-template-leak");
        constructor.visitFieldInsn(PUTFIELD, "pkg/LeakyBox", "value", "Ljava/lang/String;");
        constructor.visitInsn(RETURN);
        constructor.visitMaxs(0, 0);
        constructor.visitEnd();
        MethodVisitor value = writer.visitMethod(ACC_PUBLIC, "value", "()Ljava/lang/String;", null, null);
        value.visitCode();
        value.visitVarInsn(ALOAD, 0);
        value.visitFieldInsn(GETFIELD, "pkg/LeakyBox", "value", "Ljava/lang/String;");
        value.visitInsn(ARETURN);
        value.visitMaxs(0, 0);
        value.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private byte[] leakyMainClass() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(V17, ACC_PUBLIC | ACC_SUPER, "pkg/LeakyMain", null, "java/lang/Object", null);
        MethodVisitor constructor = writer.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(ALOAD, 0);
        constructor.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitInsn(RETURN);
        constructor.visitMaxs(0, 0);
        constructor.visitEnd();
        MethodVisitor main = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "main", "([Ljava/lang/String;)V", null, null);
        main.visitCode();
        main.visitTypeInsn(NEW, "pkg/LeakyBox");
        main.visitInsn(DUP);
        main.visitMethodInsn(INVOKESPECIAL, "pkg/LeakyBox", "<init>", "()V", false);
        main.visitInsn(POP);
        main.visitInsn(RETURN);
        main.visitMaxs(0, 0);
        main.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }
}
