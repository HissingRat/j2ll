package xyz.melodysky.app;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;
import xyz.melodysky.config.Config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.*;

public class J2llApplicationTest {

    @Test
    public void testAnalyzeWritesJsonReportWithoutNativeBuildOrRepack() throws Exception {
        Path jarPath = createFixtureJar();
        Path outputDirectory = Files.createTempDirectory("j2ll-analyze-");
        try {
            Config config = new Config();
            config.jarFile = jarPath.toString();
            config.outputDirectory = outputDirectory.toString();
            config.whiteList = List.of("sample/MathOps*");
            config.blackList = List.of("sample/MathOps#blocked!()I");

            int exitCode = new J2llApplication(J2llApplicationTest.class).analyze(config);

            assertEquals(0, exitCode);
            Path buildDirectory = onlyBuildDirectory(outputDirectory);
            Path reportPath = buildDirectory.resolve("analysis-report.json");
            assertTrue(Files.exists(reportPath));
            assertTrue(Files.notExists(buildDirectory.resolve("native")));
            assertTrue(Files.notExists(buildDirectory.resolve(jarPath.getFileName())));

            String report = Files.readString(reportPath);
            assertTrue(report.contains("\"totalClasses\": 2"));
            assertTrue(report.contains("\"attemptableMethods\": 4"));
            assertTrue(report.contains("\"nativeLoweredMethods\": 1"));
            assertTrue(report.contains("\"keptAsJavaMethods\": 3"));
            assertTrue(report.contains("\"whiteListHitMethods\": 3"));
            assertTrue(report.contains("\"blackListHitMethods\": 1"));
        } finally {
            Files.deleteIfExists(jarPath);
            deleteRecursively(outputDirectory);
        }
    }

    private Path onlyBuildDirectory(Path outputDirectory) throws IOException {
        try (var stream = Files.list(outputDirectory)) {
            return stream.filter(Files::isDirectory).findFirst().orElseThrow();
        }
    }

    private Path createFixtureJar() throws IOException {
        Path jarPath = Files.createTempFile("j2ll-analyze-fixture-", ".jar");
        try (JarOutputStream outputStream = new JarOutputStream(Files.newOutputStream(jarPath))) {
            writeClassEntry(outputStream, buildMathOpsClass());
            writeClassEntry(outputStream, buildUtilClass());
        }
        return jarPath;
    }

    private void writeClassEntry(JarOutputStream outputStream, ClassNode classNode) throws IOException {
        outputStream.putNextEntry(new JarEntry(classNode.name + ".class"));
        ClassWriter classWriter = new ClassWriter(0);
        classNode.accept(classWriter);
        outputStream.write(classWriter.toByteArray());
        outputStream.closeEntry();
    }

    private ClassNode buildMathOpsClass() {
        ClassNode classNode = new ClassNode(Opcodes.ASM9);
        classNode.version = Opcodes.V21;
        classNode.access = Opcodes.ACC_PUBLIC;
        classNode.name = "sample/MathOps";
        classNode.superName = "java/lang/Object";

        MethodNode add = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "add", "(II)I", null, null);
        add.maxLocals = 2;
        add.instructions.add(new VarInsnNode(Opcodes.ILOAD, 0));
        add.instructions.add(new VarInsnNode(Opcodes.ILOAD, 1));
        add.instructions.add(new InsnNode(Opcodes.IADD));
        add.instructions.add(new InsnNode(Opcodes.IRETURN));

        MethodNode dynamic = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "dynamic", "()Ljava/lang/String;", null, null);
        dynamic.maxLocals = 0;
        dynamic.instructions.add(new InvokeDynamicInsnNode(
                "dyn",
                "()Ljava/lang/String;",
                new Handle(Opcodes.H_INVOKESTATIC, "example/Bootstrap", "bootstrap", "()V", false)
        ));
        dynamic.instructions.add(new InsnNode(Opcodes.ARETURN));

        MethodNode blocked = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "blocked", "()I", null, null);
        blocked.maxLocals = 0;
        blocked.instructions.add(new InsnNode(Opcodes.ICONST_1));
        blocked.instructions.add(new InsnNode(Opcodes.IRETURN));

        classNode.methods.add(add);
        classNode.methods.add(dynamic);
        classNode.methods.add(blocked);
        return classNode;
    }

    private ClassNode buildUtilClass() {
        ClassNode classNode = new ClassNode(Opcodes.ASM9);
        classNode.version = Opcodes.V21;
        classNode.access = Opcodes.ACC_PUBLIC;
        classNode.name = "sample/Util";
        classNode.superName = "java/lang/Object";

        MethodNode id = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "id", "(I)I", null, null);
        id.maxLocals = 1;
        id.instructions.add(new VarInsnNode(Opcodes.ILOAD, 0));
        id.instructions.add(new InsnNode(Opcodes.IRETURN));

        classNode.methods.add(id);
        return classNode;
    }

    private void deleteRecursively(Path rootDirectory) throws IOException {
        if (rootDirectory == null || Files.notExists(rootDirectory)) {
            return;
        }
        try (var stream = Files.walk(rootDirectory)) {
            List<Path> paths = stream.sorted(Comparator.reverseOrder()).toList();
            for (Path path : paths) {
                Files.deleteIfExists(path);
            }
        }
    }
}
