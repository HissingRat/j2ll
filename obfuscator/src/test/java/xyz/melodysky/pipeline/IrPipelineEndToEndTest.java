package xyz.melodysky.pipeline;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;
import xyz.melodysky.config.BuildTarget;
import xyz.melodysky.packaging.IrJarRepacker;
import xyz.melodysky.packaging.NativeRegistrationPlan;
import xyz.melodysky.packaging.NativeRegistrationPlanner;
import xyz.melodysky.runtime.IrRuntimeStubGenerator;
import xyz.melodysky.toolchain.IrNativeBuildDriver;

import java.io.IOException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class IrPipelineEndToEndTest {

    @Test
    public void testRunsNativeMethodFromRepackedJar() throws Exception {
        Assumptions.assumeTrue(isCommandAvailable("zig"), "zig is required for end-to-end IR pipeline test");
        BuildTarget hostTarget = resolveHostTarget();
        Assumptions.assumeTrue(hostTarget != null, "host OS is not supported for this integration test");

        Path inputJar = createFixtureJar();
        Path workspace = Files.createTempDirectory("ir-pipeline-e2e-");
        try {
            IrJarRepacker repacker = new IrJarRepacker();
            String nativeDir = repacker.planNativeDir(inputJar, "native0");
            IrPipelineCompiler.DirectoryBuildResult pipelineResult = new IrPipelineCompiler().compileToDirectory(inputJar, workspace);
            NativeRegistrationPlan registrationPlan = new NativeRegistrationPlanner()
                    .plan(inputJar, pipelineResult.requestedClasses());
            Files.writeString(
                    pipelineResult.outputArtifacts().runtimeStubFile(),
                    new IrRuntimeStubGenerator().generate(
                            Files.readString(pipelineResult.outputArtifacts().llvmFile(), StandardCharsets.UTF_8),
                            registrationPlan,
                            nativeDir + "/Loader"
                    )
            );
            IrNativeBuildDriver.BuildResult nativeBuild = new IrNativeBuildDriver(workspace).build(
                    "zig",
                    pipelineResult.outputArtifacts().llvmModuleFiles(),
                    pipelineResult.outputArtifacts().runtimeStubFile(),
                    List.of(hostTarget)
            );
            Path outputJar = workspace.resolve("output.jar");
            repacker.repack(
                    inputJar,
                    outputJar,
                    nativeBuild.artifacts(),
                    nativeDir,
                    null,
                    registrationPlan
            );

            try (URLClassLoader classLoader = new URLClassLoader(new URL[]{outputJar.toUri().toURL()}, ClassLoader.getSystemClassLoader())) {
                Class<?> mathOpsClass = Class.forName("sample.MathOps", true, classLoader);
                Method addMethod = mathOpsClass.getDeclaredMethod("add", int.class, int.class);
                Method callAddMethod = mathOpsClass.getDeclaredMethod("callAdd", int.class, int.class);
                Method makeAndGetMethod = mathOpsClass.getDeclaredMethod("makeAndGet", int.class);
                Method makeCtorValueMethod = mathOpsClass.getDeclaredMethod("makeCtorValue");
                Method setBaseMethod = mathOpsClass.getDeclaredMethod("setBase", int.class);
                Method getBaseMethod = mathOpsClass.getDeclaredMethod("getBase");
                assertEquals(11, addMethod.invoke(null, 4, 7));
                assertEquals(11, callAddMethod.invoke(null, 4, 7));
                assertEquals(9, makeAndGetMethod.invoke(null, 9));
                assertEquals(41, makeCtorValueMethod.invoke(null));
                setBaseMethod.invoke(null, 6);
                assertEquals(6, getBaseMethod.invoke(null));

                Object worker = mathOpsClass.getDeclaredConstructor().newInstance();
                Method callHelperMethod = mathOpsClass.getDeclaredMethod("callHelper", int.class);
                Method setValueMethod = mathOpsClass.getDeclaredMethod("setValue", int.class);
                Method getValueMethod = mathOpsClass.getDeclaredMethod("getValue");
                Method callVirtualMethod = mathOpsClass.getDeclaredMethod("callVirtual", int.class);
                assertEquals(8, callHelperMethod.invoke(worker, 7));
                setValueMethod.invoke(worker, 5);
                assertEquals(5, getValueMethod.invoke(worker));
                assertEquals(12, callVirtualMethod.invoke(worker, 7));
            }
        } finally {
            Files.deleteIfExists(inputJar);
            deleteRecursively(workspace);
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
        Path jarPath = Files.createTempFile("ir-e2e-", ".jar");
        try (JarOutputStream outputStream = new JarOutputStream(Files.newOutputStream(jarPath))) {
            writeClassEntry(outputStream, buildMathOpsClass());
        }
        return jarPath;
    }

    private void writeClassEntry(JarOutputStream outputStream, ClassNode classNode) throws IOException {
        outputStream.putNextEntry(new JarEntry(classNode.name + ".class"));
        ClassWriter classWriter = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
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
        classNode.fields.add(new FieldNode(Opcodes.ACC_PUBLIC, "value", "I", null, null));
        classNode.fields.add(new FieldNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "base", "I", null, null));

        MethodNode add = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "add", "(II)I", null, null);
        add.maxLocals = 2;
        add.instructions.add(new VarInsnNode(Opcodes.ILOAD, 0));
        add.instructions.add(new VarInsnNode(Opcodes.ILOAD, 1));
        add.instructions.add(new InsnNode(Opcodes.IADD));
        add.instructions.add(new InsnNode(Opcodes.IRETURN));

        MethodNode callAdd = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "callAdd", "(II)I", null, null);
        callAdd.maxLocals = 2;
        callAdd.instructions.add(new VarInsnNode(Opcodes.ILOAD, 0));
        callAdd.instructions.add(new VarInsnNode(Opcodes.ILOAD, 1));
        callAdd.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "sample/MathOps", "add", "(II)I", false));
        callAdd.instructions.add(new InsnNode(Opcodes.IRETURN));

        MethodNode makeAndGet = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "makeAndGet", "(I)I", null, null);
        makeAndGet.maxLocals = 1;
        makeAndGet.instructions.add(new TypeInsnNode(Opcodes.NEW, "sample/MathOps"));
        makeAndGet.instructions.add(new InsnNode(Opcodes.DUP));
        makeAndGet.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "sample/MathOps", "<init>", "()V", false));
        makeAndGet.instructions.add(new InsnNode(Opcodes.DUP));
        makeAndGet.instructions.add(new VarInsnNode(Opcodes.ILOAD, 0));
        makeAndGet.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "sample/MathOps", "setValue", "(I)V", false));
        makeAndGet.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "sample/MathOps", "getValue", "()I", false));
        makeAndGet.instructions.add(new InsnNode(Opcodes.IRETURN));

        MethodNode makeCtorValue = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "makeCtorValue", "()I", null, null);
        makeCtorValue.maxLocals = 0;
        makeCtorValue.instructions.add(new TypeInsnNode(Opcodes.NEW, "sample/MathOps"));
        makeCtorValue.instructions.add(new InsnNode(Opcodes.DUP));
        makeCtorValue.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "sample/MathOps", "<init>", "()V", false));
        makeCtorValue.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "sample/MathOps", "getValue", "()I", false));
        makeCtorValue.instructions.add(new InsnNode(Opcodes.IRETURN));

        MethodNode setBase = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "setBase", "(I)V", null, null);
        setBase.maxLocals = 1;
        setBase.instructions.add(new VarInsnNode(Opcodes.ILOAD, 0));
        setBase.instructions.add(new FieldInsnNode(Opcodes.PUTSTATIC, "sample/MathOps", "base", "I"));
        setBase.instructions.add(new InsnNode(Opcodes.RETURN));

        MethodNode getBase = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "getBase", "()I", null, null);
        getBase.maxLocals = 0;
        getBase.instructions.add(new FieldInsnNode(Opcodes.GETSTATIC, "sample/MathOps", "base", "I"));
        getBase.instructions.add(new InsnNode(Opcodes.IRETURN));

        MethodNode constructor = new MethodNode(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.maxLocals = 1;
        constructor.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        constructor.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false));
        constructor.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        constructor.instructions.add(new org.objectweb.asm.tree.IntInsnNode(Opcodes.BIPUSH, 41));
        constructor.instructions.add(new FieldInsnNode(Opcodes.PUTFIELD, "sample/MathOps", "value", "I"));
        constructor.instructions.add(new InsnNode(Opcodes.RETURN));

        MethodNode helper = new MethodNode(Opcodes.ACC_PRIVATE, "helper", "(I)I", null, null);
        helper.maxLocals = 2;
        helper.instructions.add(new VarInsnNode(Opcodes.ILOAD, 1));
        helper.instructions.add(new InsnNode(Opcodes.ICONST_1));
        helper.instructions.add(new InsnNode(Opcodes.IADD));
        helper.instructions.add(new InsnNode(Opcodes.IRETURN));

        MethodNode callHelper = new MethodNode(Opcodes.ACC_PUBLIC, "callHelper", "(I)I", null, null);
        callHelper.maxLocals = 2;
        callHelper.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        callHelper.instructions.add(new VarInsnNode(Opcodes.ILOAD, 1));
        callHelper.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "sample/MathOps", "helper", "(I)I", false));
        callHelper.instructions.add(new InsnNode(Opcodes.IRETURN));

        MethodNode setValue = new MethodNode(Opcodes.ACC_PUBLIC, "setValue", "(I)V", null, null);
        setValue.maxLocals = 2;
        setValue.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        setValue.instructions.add(new VarInsnNode(Opcodes.ILOAD, 1));
        setValue.instructions.add(new FieldInsnNode(Opcodes.PUTFIELD, "sample/MathOps", "value", "I"));
        setValue.instructions.add(new InsnNode(Opcodes.RETURN));

        MethodNode getValue = new MethodNode(Opcodes.ACC_PUBLIC, "getValue", "()I", null, null);
        getValue.maxLocals = 1;
        getValue.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        getValue.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, "sample/MathOps", "value", "I"));
        getValue.instructions.add(new InsnNode(Opcodes.IRETURN));

        MethodNode virtualPlus = new MethodNode(Opcodes.ACC_PUBLIC, "virtualPlus", "(I)I", null, null);
        virtualPlus.maxLocals = 2;
        virtualPlus.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        virtualPlus.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, "sample/MathOps", "value", "I"));
        virtualPlus.instructions.add(new VarInsnNode(Opcodes.ILOAD, 1));
        virtualPlus.instructions.add(new InsnNode(Opcodes.IADD));
        virtualPlus.instructions.add(new InsnNode(Opcodes.IRETURN));

        MethodNode callVirtual = new MethodNode(Opcodes.ACC_PUBLIC, "callVirtual", "(I)I", null, null);
        callVirtual.maxLocals = 2;
        callVirtual.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        callVirtual.instructions.add(new VarInsnNode(Opcodes.ILOAD, 1));
        callVirtual.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "sample/MathOps", "virtualPlus", "(I)I", false));
        callVirtual.instructions.add(new InsnNode(Opcodes.IRETURN));

        classNode.methods.add(constructor);
        classNode.methods.add(add);
        classNode.methods.add(callAdd);
        classNode.methods.add(makeAndGet);
        classNode.methods.add(makeCtorValue);
        classNode.methods.add(setBase);
        classNode.methods.add(getBase);
        classNode.methods.add(helper);
        classNode.methods.add(callHelper);
        classNode.methods.add(setValue);
        classNode.methods.add(getValue);
        classNode.methods.add(virtualPlus);
        classNode.methods.add(callVirtual);
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
