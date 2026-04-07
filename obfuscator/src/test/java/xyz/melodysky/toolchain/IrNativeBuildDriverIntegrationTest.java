package xyz.melodysky.toolchain;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;
import xyz.melodysky.config.BuildTarget;
import xyz.melodysky.pipeline.IrPipelineCompiler;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertTrue;

public class IrNativeBuildDriverIntegrationTest {

    @Test
    public void testBuildsHostNativeLibraryFromIrPipelineArtifacts() throws Exception {
        Assumptions.assumeTrue(isCommandAvailable("zig"), "zig is required for native build integration test");

        BuildTarget hostTarget = resolveHostTarget();
        Assumptions.assumeTrue(hostTarget != null, "host OS is not supported for this integration test");

        Path jarPath = createFixtureJar();
        Path outputDirectory = Files.createTempDirectory("ir-native-build-");
        try {
            IrPipelineCompiler.BuildResult pipelineResult = new IrPipelineCompiler().compileToDirectory(jarPath, outputDirectory);
            IrNativeBuildDriver.BuildResult nativeBuild = new IrNativeBuildDriver(outputDirectory).build(
                    "zig",
                    pipelineResult.outputArtifacts().llvmModuleFiles(),
                    pipelineResult.outputArtifacts().runtimeStubFile(),
                    List.of(hostTarget)
            );

            assertTrue(Files.exists(nativeBuild.artifacts().getFirst().libraryFile()));
            assertTrue(Files.size(nativeBuild.artifacts().getFirst().libraryFile()) > 0);
            assertTrue(Files.readString(nativeBuild.artifacts().getFirst().logFile(), StandardCharsets.UTF_8).contains("zig cc"));
        } finally {
            Files.deleteIfExists(jarPath);
            deleteRecursively(outputDirectory);
        }
    }

    private boolean isCommandAvailable(String command) {
        try {
            Process process = new ProcessBuilder(command, "version")
                    .redirectErrorStream(true)
                    .start();
            process.getInputStream().readAllBytes();
            return process.waitFor() == 0;
        } catch (Exception ignored) {
            return false;
        }
    }

    private BuildTarget resolveHostTarget() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        boolean arm64 = arch.contains("aarch64") || arch.contains("arm64");

        if (os.contains("win")) {
            return arm64 ? BuildTarget.WINDOWS_ARM64 : BuildTarget.WINDOWS_X64;
        }
        if (os.contains("linux")) {
            return arm64 ? BuildTarget.LINUX_ARM64 : BuildTarget.LINUX_X64;
        }
        if (os.contains("mac")) {
            return arm64 ? BuildTarget.MACOS_ARM64 : BuildTarget.MACOS_X64;
        }
        return null;
    }

    private Path createFixtureJar() throws IOException {
        Path jarPath = Files.createTempFile("ir-native-build-", ".jar");
        try (JarOutputStream outputStream = new JarOutputStream(Files.newOutputStream(jarPath))) {
            writeClassEntry(outputStream, buildMathOpsClass());
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
