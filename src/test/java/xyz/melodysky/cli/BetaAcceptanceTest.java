package xyz.melodysky.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import xyz.melodysky.testsupport.AsmFixtureBuilder;
import xyz.melodysky.testsupport.FakeManagedZig;
import xyz.melodysky.testsupport.JvmRunner;

class BetaAcceptanceTest implements Opcodes {
    private static final String SECRET_SEED = "beta-acceptance-secret-seed";

    @TempDir
    Path temp;

    @Test
    void distPackageRunsBetaAcceptancePathEndToEnd() throws Exception {
        Path dist = Path.of("build/dist/j2ll").toAbsolutePath().normalize();
        Path cliJar = dist.resolve("j2ll.jar");
        Path exampleConfig = dist.resolve("docs/examples/minimal-config.json");
        assertTrue(Files.isRegularFile(cliJar), "expected dist CLI jar at " + cliJar);
        assertTrue(Files.isRegularFile(exampleConfig), "expected dist example config at " + exampleConfig);
        assertFalse(Files.exists(dist.resolve("reports")), "dist package must not include generated reports");
        assertFalse(Files.exists(dist.resolve("output")), "dist package must not include generated output JARs");
        assertFalse(Files.exists(dist.resolve("native")), "dist package must not include generated native artifacts");

        ProcessResult version = runCli(dist, "--version");
        ProcessResult help = runCli(dist, "--help");
        ProcessResult validate = runCli(dist, "--config", exampleConfig.toString(), "--validate");
        assertEquals(0, version.exitCode(), version.stderr());
        assertTrue(version.stdout().startsWith("j2ll "), version.stdout());
        assertEquals(0, help.exitCode(), help.stderr());
        assertTrue(help.stdout().contains(
                "j2ll [--config <config.json>] [--validate | --dry-run] [--debug]"), help.stdout());
        assertEquals(0, validate.exitCode(), validate.stderr());
        assertTrue(validate.stdout().contains("config=ok"), validate.stdout());

        Path inputJar = temp.resolve("acceptance-input.jar");
        writeJar(inputJar, Map.of(
                "example/Adder.class", AsmFixtureBuilder.classWithAddMethod("example/Adder"),
                "example/Main.class", exampleMain()));
        Path config = temp.resolve("acceptance-config.json");
        Files.writeString(config, Files.readString(exampleConfig)
                .replace("\"jarFile\": \"input.jar\"", "\"jarFile\": \"" + slash(inputJar) + "\"")
                .replace("\"seed\": null", "\"seed\": \"" + SECRET_SEED + "\""));

        ProcessResult dryRun = runCli(dist, "--config", config.toString(), "--dry-run");
        assertEquals(0, dryRun.exitCode(), dryRun.stderr());
        assertTrue(dryRun.stdout().contains("dryRunReport="), dryRun.stdout());
        Path dryRunReport = pathValue(dryRun.stdout(), "dryRunReport");
        Path dryWorkspace = dryRunReport.getParent().getParent();
        assertTrue(Files.isRegularFile(dryWorkspace.resolve("reports/index.json")));
        assertTrue(Files.isRegularFile(dryWorkspace.resolve("reports/summary.md")));
        assertTrue(Files.isRegularFile(dryWorkspace.resolve("reports/release-readiness.json")));
        assertFalse(Files.exists(dryWorkspace.resolve(inputJar.getFileName())));
        assertFalse(Files.exists(dryWorkspace.resolve("native")));

        ProcessResult build;
        try (AutoCloseable ignored = FakeManagedZig.installAndUse(dist)) {
            build = runCli(dist, "--config", config.toString());
        }
        assertEquals(0, build.exitCode(), build.stderr());
        assertTrue(build.stdout().contains("outputJar="), build.stdout());
        assertTrue(build.stdout().contains("reportsDir="), build.stdout());
        assertTrue(build.stdout().contains("reportIndex="), build.stdout());
        assertFalse(build.stderr().contains("\"diagnostics\""), build.stderr());

        Path outputJar = pathValue(build.stdout(), "outputJar");
        Path workspace = outputJar.getParent();
        assertTrue(Files.isRegularFile(outputJar), "expected output JAR at " + outputJar);
        var run = new JvmRunner().run(outputJar, "example.Main", List.of());
        assertEquals(0, run.exitCode(), run.stderr());
        assertEquals("42\n", run.stdout());
        assertReportsAndMetadataAreAcceptanceReady(workspace, outputJar);
    }

    private ProcessResult runCli(Path dist, String... arguments) throws Exception {
        Path cliJar = dist.resolve("j2ll.jar");
        assertTrue(cliJar.startsWith(Path.of("build/dist/j2ll").toAbsolutePath().normalize()),
                "acceptance must execute the distribution CLI jar, not the test classpath: " + cliJar);
        Path java = Path.of(System.getProperty("java.home"), "bin", isWindows() ? "java.exe" : "java");
        ArrayList<String> command = new ArrayList<>(List.of(
                java.toString(),
                "-Dj2ll.home=" + dist,
                "-jar",
                cliJar.toString()));
        command.addAll(List.of(arguments));
        Process process = new ProcessBuilder(command)
                .redirectErrorStream(false)
                .start();
        byte[] stdout = process.getInputStream().readAllBytes();
        byte[] stderr = process.getErrorStream().readAllBytes();
        int exitCode = process.waitFor();
        return new ProcessResult(
                exitCode,
                new String(stdout, StandardCharsets.UTF_8),
                new String(stderr, StandardCharsets.UTF_8));
    }

    private Path pathValue(String output, String key) {
        String prefix = key + "=";
        return output.lines()
                .filter(line -> line.startsWith(prefix))
                .map(line -> Path.of(line.substring(prefix.length())))
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing " + prefix + " in output:\n" + output));
    }

    private void assertReportsAndMetadataAreAcceptanceReady(Path workspace, Path outputJar) throws Exception {
        Path reports = workspace.resolve("reports");
        assertTrue(Files.isRegularFile(reports.resolve("index.json")));
        assertTrue(Files.isRegularFile(reports.resolve("summary.md")));
        assertTrue(Files.isRegularFile(reports.resolve("release-readiness.json")));
        String readiness = Files.readString(reports.resolve("release-readiness.json"));
        assertTrue(readiness.contains("\"status\": \"passed\""), readiness);
        assertTrue(readiness.contains("\"finalArtifactWritten\": true"), readiness);
        String summary = Files.readString(reports.resolve("summary.md"));
        assertTrue(summary.contains("## Native Targets"), summary);
        assertFalse(summary.contains(System.getProperty("user.home")), summary);
        assertFalse(summary.contains(System.getProperty("user.name")), summary);
        assertNoRawSeedInReports(reports);

        JsonObject index = JsonParser.parseString(Files.readString(reports.resolve("index.json"))).getAsJsonObject();
        assertTrue(index.getAsJsonArray("reports").asList().stream()
                .map(element -> element.getAsJsonObject().get("path").getAsString())
                .anyMatch(path -> path.equals("reports/release-readiness.json")));

        try (JarFile jar = new JarFile(outputJar.toFile(), false)) {
            assertTrue(jar.stream()
                    .noneMatch(entry -> entry.getName().toLowerCase(java.util.Locale.ROOT)
                            .startsWith("meta-inf/j2ll/")));
        }
    }

    private void assertNoRawSeedInReports(Path reports) throws Exception {
        try (var stream = Files.list(reports)) {
            for (Path report : stream.filter(Files::isRegularFile).toList()) {
                String text = Files.readString(report);
                assertFalse(text.contains(SECRET_SEED), report + " leaked raw protection seed");
            }
        }
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

    private byte[] exampleMain() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(V17, ACC_PUBLIC | ACC_SUPER, "example/Main", null, "java/lang/Object", null);
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
        main.visitMethodInsn(INVOKESTATIC, "example/Adder", "add", "(II)I", false);
        main.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(I)V", false);
        main.visitInsn(RETURN);
        main.visitMaxs(0, 0);
        main.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private String slash(Path path) {
        return path.toString().replace('\\', '/');
    }

    private boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
    }

    private record ProcessResult(int exitCode, String stdout, String stderr) {}
}
