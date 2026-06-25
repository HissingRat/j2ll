package xyz.melodysky.pipeline;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class IrPipelineCompilerTest {

    @Test
    public void testCompilesJarToLlvmThroughNewPipeline() throws Exception {
        Path jarPath = createFixtureJar();
        try {
            IrPipelineCompiler.BuildResult result = new IrPipelineCompiler().compile(jarPath);

            assertEquals(1, result.frontendResult().program().classes().size());
            assertEquals(1, result.transformedProgram().classes().size());
            assertEquals(2, result.frontendResult().classResults().get(0).irClass().methods().size());
            assertTrue(result.llvmText().contains("define i32 @\""));
            assertTrue(result.llvmText().contains("; class sample/MathOps"));
            assertTrue(result.llvmText().contains("; helper-meta "));
        } finally {
            Files.deleteIfExists(jarPath);
        }
    }

    @Test
    public void testWritesLlvmArtifactsToDirectory() throws Exception {
        Path jarPath = createFixtureJar();
        Path outputDirectory = Files.createTempDirectory("ir-pipeline-out-");
        try {
            IrPipelineCompiler.DirectoryBuildResult result = new IrPipelineCompiler().compileToDirectory(jarPath, outputDirectory);

            assertTrue(Files.exists(result.outputArtifacts().llvmFile()));
            assertEquals(outputDirectory.resolve("llvm-modules").resolve("program.ll"), result.outputArtifacts().llvmFile());
            assertEquals(outputDirectory.resolve("runtime"), result.outputArtifacts().runtimeDirectory());
            assertTrue(result.outputArtifacts().frontendSkipsFile() == null);
            assertTrue(result.outputArtifacts().frontendSkipsJsonFile() == null);
            assertTrue(Files.exists(result.outputArtifacts().runtimeStubFile()));
            assertEquals(result.outputArtifacts().runtimeDirectory().resolve("ir_runtime_stubs.c"),
                    result.outputArtifacts().runtimeStubFile());
            assertTrue(result.outputArtifacts().llvmModuleFiles().size() >= 2);
            for (Path moduleFile : result.outputArtifacts().llvmModuleFiles()) {
                assertTrue(Files.exists(moduleFile));
            }
            String llvm = Files.readString(result.outputArtifacts().llvmFile());
            String runtimeStubs = Files.readString(result.outputArtifacts().runtimeStubFile());
            assertTrue(llvm.contains("; class sample/MathOps"));
            assertTrue(Files.notExists(outputDirectory.resolve("frontend-skips.txt")));
            assertTrue(!runtimeStubs.isBlank());
        } finally {
            Files.deleteIfExists(jarPath);
            deleteRecursively(outputDirectory);
        }
    }

    @Test
    public void testWritesStructuredFrontendSkipReport() throws Exception {
        Path jarPath = createSkippedFixtureJar();
        Path outputDirectory = Files.createTempDirectory("ir-pipeline-skips-");
        try {
            IrPipelineCompiler.DirectoryBuildResult result = new IrPipelineCompiler().compileToDirectory(jarPath, outputDirectory);

            assertEquals(outputDirectory.resolve("frontend-skips.txt"), result.outputArtifacts().frontendSkipsFile());
            assertEquals(outputDirectory.resolve("frontend-skips.json"), result.outputArtifacts().frontendSkipsJsonFile());
            assertTrue(Files.exists(result.outputArtifacts().frontendSkipsFile()));
            assertTrue(Files.exists(result.outputArtifacts().frontendSkipsJsonFile()));
            String json = Files.readString(result.outputArtifacts().frontendSkipsJsonFile());
            assertTrue(json.contains("\"totalSkips\": 1"));
            assertTrue(json.contains("\"className\": \"sample/Skipped\""));
            assertTrue(json.contains("\"methodName\": \"dynamic\""));
            assertTrue(json.contains("\"category\": \"invokedynamic\""));
        } finally {
            Files.deleteIfExists(jarPath);
            deleteRecursively(outputDirectory);
        }
    }

    private Path createFixtureJar() throws IOException {
        Path jarPath = Files.createTempFile("ir-pipeline-", ".jar");
        try (JarOutputStream outputStream = new JarOutputStream(Files.newOutputStream(jarPath))) {
            writeClassEntry(outputStream, buildMathOpsClass());
        }
        return jarPath;
    }

    private Path createSkippedFixtureJar() throws IOException {
        Path jarPath = Files.createTempFile("ir-pipeline-skips-", ".jar");
        try (JarOutputStream outputStream = new JarOutputStream(Files.newOutputStream(jarPath))) {
            writeClassEntry(outputStream, buildSkippedClass());
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

        MethodNode callMix = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "callMix", "(II)I", null, null);
        callMix.maxLocals = 2;
        callMix.instructions.add(new VarInsnNode(Opcodes.ILOAD, 0));
        callMix.instructions.add(new VarInsnNode(Opcodes.ILOAD, 1));
        callMix.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "sample/MathOps", "mix", "(II)I", false));
        callMix.instructions.add(new InsnNode(Opcodes.IRETURN));

        classNode.methods.add(add);
        classNode.methods.add(callMix);
        return classNode;
    }

    private ClassNode buildSkippedClass() {
        ClassNode classNode = new ClassNode(Opcodes.ASM9);
        classNode.version = Opcodes.V21;
        classNode.access = Opcodes.ACC_PUBLIC;
        classNode.name = "sample/Skipped";
        classNode.superName = "java/lang/Object";

        MethodNode dynamic = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "dynamic", "()Ljava/lang/String;", null, null);
        dynamic.maxLocals = 0;
        dynamic.instructions.add(new InvokeDynamicInsnNode(
                "dynamic",
                "()Ljava/lang/String;",
                new Handle(
                        Opcodes.H_INVOKESTATIC,
                        "sample/Bootstrap",
                        "bootstrap",
                        "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;",
                        false
                )
        ));
        dynamic.instructions.add(new InsnNode(Opcodes.ARETURN));
        classNode.methods.add(dynamic);
        return classNode;
    }

    private void deleteRecursively(Path root) throws IOException {
        if (root == null || Files.notExists(root)) {
            return;
        }
        try (var stream = Files.walk(root)) {
            for (Path path : stream.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
