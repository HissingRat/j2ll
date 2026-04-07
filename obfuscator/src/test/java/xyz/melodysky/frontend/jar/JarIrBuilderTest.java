package xyz.melodysky.frontend.jar;

import xyz.melodysky.filter.ClassMethodFilter;
import xyz.melodysky.filter.ClassMethodList;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class JarIrBuilderTest {

    @Test
    public void testBuildsProgramFromJarAndReportsSkippedMethods() throws Exception {
        Path jarPath = createFixtureJar();
        try {
            JarIrBuilder.BuildResult result = new JarIrBuilder().build(jarPath);

            assertEquals(2, result.program().classes().size());
            assertEquals("sample/MathOps", result.program().classes().get(0).reference().internalName());
            assertEquals(2, result.program().classes().get(0).methods().size());
            assertEquals("sample/Util", result.program().classes().get(1).reference().internalName());
            assertEquals(1, result.program().classes().get(1).methods().size());

            assertEquals(2, result.classResults().size());
            JarIrBuilder.ClassBuildResult mathOps = result.classResults().get(0);
            assertNotNull(mathOps.irClass());
            assertEquals(0, mathOps.skippedMethods().size());

            JarIrBuilder.ClassBuildResult util = result.classResults().get(1);
            assertNotNull(util.irClass());
            assertEquals(0, util.skippedMethods().size());
        } finally {
            Files.deleteIfExists(jarPath);
        }
    }

    @Test
    public void testAppliesWhiteListAndBlackListLikeLegacyPipeline() throws Exception {
        Path jarPath = createFixtureJar();
        try {
            ClassMethodFilter filter = new ClassMethodFilter(
                    ClassMethodList.parse(java.util.List.of("sample/MathOps#badRef!()Ljava/lang/String;")),
                    ClassMethodList.parse(java.util.List.of("sample/MathOps*"))
            );

            JarIrBuilder.BuildResult result = new JarIrBuilder().build(jarPath, filter);

            assertEquals(1, result.program().classes().size());
            assertEquals("sample/MathOps", result.program().classes().get(0).reference().internalName());
            assertEquals(1, result.program().classes().get(0).methods().size());
            assertEquals("add", result.program().classes().get(0).methods().get(0).name());
            assertEquals(1, result.classResults().size());
            assertTrue(result.classResults().get(0).skippedMethods().isEmpty());
        } finally {
            Files.deleteIfExists(jarPath);
        }
    }

    @Test
    public void testKeepsMethodsCallingProgramMethodsThatRemainInJava() throws Exception {
        Path jarPath = createCrossClassSkipFixtureJar();
        try {
            JarIrBuilder.BuildResult result = new JarIrBuilder().build(jarPath);

            assertEquals(2, result.classResults().size());

            JarIrBuilder.ClassBuildResult skipped = result.classResults().get(0);
            assertEquals("sample/Skipped", skipped.className());
            assertTrue(skipped.skippedMethods().isEmpty());
            assertNotNull(skipped.irClass());
            assertEquals(1, skipped.irClass().methods().size());
            assertEquals("problem", skipped.irClass().methods().get(0).name());

            JarIrBuilder.ClassBuildResult caller = result.classResults().get(1);
            assertEquals("sample/Caller", caller.className());
            assertTrue(caller.skippedMethods().isEmpty());
            assertNotNull(caller.irClass());
            assertEquals(1, caller.irClass().methods().size());
            assertEquals("call", caller.irClass().methods().get(0).name());

            assertEquals(2, result.program().classes().size());
            assertEquals("sample/Skipped", result.program().classes().get(0).reference().internalName());
            assertEquals("sample/Caller", result.program().classes().get(1).reference().internalName());
        } finally {
            Files.deleteIfExists(jarPath);
        }
    }

    @Test
    public void testDoesNotPropagateProgramSkipsIntoSpecialMethods() throws Exception {
        Path jarPath = Files.createTempFile("ir-special-caller-", ".jar");
        try (JarOutputStream outputStream = new JarOutputStream(Files.newOutputStream(jarPath))) {
            writeClassEntry(outputStream, buildSkippedClass());
            writeClassEntry(outputStream, buildSpecialCallerClass());
        }
        try {
            JarIrBuilder.BuildResult result = new JarIrBuilder().build(jarPath);

            assertEquals(2, result.classResults().size());

            JarIrBuilder.ClassBuildResult specialCaller = result.classResults().get(1);
            assertEquals("sample/SpecialCaller", specialCaller.className());
            assertTrue(specialCaller.skippedMethods().isEmpty());
            assertNotNull(specialCaller.irClass());
            assertEquals(1, specialCaller.irClass().methods().size());
            assertEquals("<clinit>", specialCaller.irClass().methods().get(0).name());
        } finally {
            Files.deleteIfExists(jarPath);
        }
    }

    private Path createFixtureJar() throws IOException {
        Path jarPath = Files.createTempFile("ir-frontend-", ".jar");
        try (JarOutputStream outputStream = new JarOutputStream(Files.newOutputStream(jarPath))) {
            writeClassEntry(outputStream, buildMathOpsClass());
            writeClassEntry(outputStream, buildUtilClass());
        }
        return jarPath;
    }

    private Path createCrossClassSkipFixtureJar() throws IOException {
        Path jarPath = Files.createTempFile("ir-cross-class-", ".jar");
        try (JarOutputStream outputStream = new JarOutputStream(Files.newOutputStream(jarPath))) {
            writeClassEntry(outputStream, buildSkippedClass());
            writeClassEntry(outputStream, buildCallerClass());
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

        MethodNode badRef = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "badRef", "()Ljava/lang/String;", null, null);
        badRef.maxLocals = 0;
        badRef.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "sample/Factory", "make", "()Ljava/lang/String;", false));
        badRef.instructions.add(new InsnNode(Opcodes.ARETURN));

        classNode.methods.add(add);
        classNode.methods.add(badRef);
        return classNode;
    }

    private ClassNode buildUtilClass() {
        ClassNode classNode = new ClassNode(Opcodes.ASM9);
        classNode.version = Opcodes.V21;
        classNode.access = Opcodes.ACC_PUBLIC;
        classNode.name = "sample/Util";
        classNode.superName = "java/lang/Object";

        MethodNode touch = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "touch", "()V", null, null);
        touch.maxLocals = 0;
        touch.instructions.add(new InsnNode(Opcodes.RETURN));

        classNode.methods.add(touch);
        return classNode;
    }

    private ClassNode buildSkippedClass() {
        ClassNode classNode = new ClassNode(Opcodes.ASM9);
        classNode.version = Opcodes.V21;
        classNode.access = Opcodes.ACC_PUBLIC;
        classNode.name = "sample/Skipped";
        classNode.superName = "java/lang/Object";
        classNode.interfaces.add("java/lang/Runnable");

        MethodNode problem = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "problem", "()V", null, null);
        org.objectweb.asm.tree.LabelNode start = new org.objectweb.asm.tree.LabelNode();
        org.objectweb.asm.tree.LabelNode end = new org.objectweb.asm.tree.LabelNode();
        org.objectweb.asm.tree.LabelNode handler = new org.objectweb.asm.tree.LabelNode();
        problem.instructions.add(start);
        problem.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
        problem.instructions.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/lang/Runnable", "run", "()V", true));
        problem.instructions.add(end);
        problem.instructions.add(new InsnNode(Opcodes.RETURN));
        problem.instructions.add(handler);
        problem.instructions.add(new InsnNode(Opcodes.POP));
        problem.instructions.add(new InsnNode(Opcodes.RETURN));
        problem.tryCatchBlocks.add(new org.objectweb.asm.tree.TryCatchBlockNode(start, end, handler, "java/lang/Exception"));

        classNode.methods.add(problem);
        return classNode;
    }

    private ClassNode buildCallerClass() {
        ClassNode classNode = new ClassNode(Opcodes.ASM9);
        classNode.version = Opcodes.V21;
        classNode.access = Opcodes.ACC_PUBLIC;
        classNode.name = "sample/Caller";
        classNode.superName = "java/lang/Object";

        MethodNode call = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "call", "()V", null, null);
        call.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "sample/Skipped", "problem", "()V", false));
        call.instructions.add(new InsnNode(Opcodes.RETURN));

        classNode.methods.add(call);
        return classNode;
    }

    private ClassNode buildSpecialCallerClass() {
        ClassNode classNode = new ClassNode(Opcodes.ASM9);
        classNode.version = Opcodes.V21;
        classNode.access = Opcodes.ACC_PUBLIC;
        classNode.name = "sample/SpecialCaller";
        classNode.superName = "java/lang/Object";

        MethodNode clinit = new MethodNode(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
        clinit.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "sample/Skipped", "problem", "()V", false));
        clinit.instructions.add(new InsnNode(Opcodes.RETURN));

        classNode.methods.add(clinit);
        return classNode;
    }
}
