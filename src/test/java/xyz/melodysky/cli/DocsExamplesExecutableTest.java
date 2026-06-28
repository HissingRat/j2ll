package xyz.melodysky.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
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
import xyz.melodysky.testsupport.AsmFixtureBuilder;
import xyz.melodysky.testsupport.FakeManagedZig;
import xyz.melodysky.testsupport.JvmRunner;

class DocsExamplesExecutableTest implements Opcodes {
    @TempDir
    Path temp;

    @Test
    void minimalDocsExampleBuildsRunnableOutputAndReportsIndex() throws Exception {
        Path inputJar = temp.resolve("input.jar");
        writeJar(inputJar, Map.of(
                "example/Adder.class", AsmFixtureBuilder.classWithAddMethod("example/Adder"),
                "example/Main.class", exampleMain()));
        String configTemplate = Files.readString(Path.of("docs/examples/minimal-config.json"));
        Path config = temp.resolve("minimal-config.json");
        Files.writeString(config, configTemplate.replace("\"jarFile\": \"input.jar\"", "\"jarFile\": \"" + inputJar.toString().replace('\\', '/') + "\""));
        Path workspace = temp.resolve("workspace");

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int exit;
        try (AutoCloseable ignored = FakeManagedZig.installAndUse(temp.resolve("j2ll-home"))) {
            exit = J2llCli.run(
                    new String[] {"build", config.toString(), workspace.toString()},
                    new PrintStream(out, true, StandardCharsets.UTF_8),
                    new PrintStream(err, true, StandardCharsets.UTF_8));
        }

        assertEquals(0, exit, err.toString(StandardCharsets.UTF_8));
        assertTrue(out.toString(StandardCharsets.UTF_8).contains("reportIndex="), out.toString(StandardCharsets.UTF_8));
        Path outputJar = workspace.resolve("output/input.jar");
        var run = new JvmRunner().run(outputJar, "example.Main", List.of());
        assertEquals(0, run.exitCode(), run.stderr());
        assertEquals("42\n", run.stdout());
        assertTrue(Files.isRegularFile(workspace.resolve("reports/index.json")));
        assertTrue(Files.isRegularFile(workspace.resolve("reports/summary.md")));
        assertTrue(Files.readString(workspace.resolve("reports/release-readiness.json")).contains("\"betaProfilePassed\""));
    }

    @Test
    void sampleDocsKeepExecutableCommandAndExpectedOutputSnippets() throws Exception {
        String basic = Files.readString(Path.of("docs/samples/basic-cli-app.md"));
        String reflection = Files.readString(Path.of("docs/samples/reflection-service-app.md"));
        String gettingStarted = Files.readString(Path.of("docs/getting-started.md"));

        assertTrue(basic.contains("java -jar build/dist/j2ll/j2ll.jar build config/basic-cli-app.json"), basic);
        assertTrue(basic.contains("hello beta count=1 opt=beta"), basic);
        assertTrue(reflection.contains("java -jar build/dist/j2ll/j2ll.jar build config/reflection-service-app.json"), reflection);
        assertTrue(reflection.contains("beta:7"), reflection);
        assertTrue(gettingStarted.contains("bash ./gradlew distJ2ll"), gettingStarted);
        assertTrue(gettingStarted.contains("java -jar build/dist/j2ll/j2ll.jar --help"), gettingStarted);
        assertTrue(gettingStarted.contains("java -jar build/dist/j2ll/j2ll.jar validate docs/examples/minimal-config.json"), gettingStarted);
        assertTrue(gettingStarted.contains("bash ./gradlew betaAcceptance"), gettingStarted);
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
}
