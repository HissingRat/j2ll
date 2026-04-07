package xyz.melodysky.packaging;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class LoaderInitClassRewriterTest {

    @Test
    public void testCreatesClinitWhenMissing() {
        byte[] rewritten = new LoaderInitClassRewriter().injectLoaderCalls(buildClassBytes(false), "native0/Loader", 7);
        ClassNode classNode = readClass(rewritten);

        assertTrue(hasEnsureLoadedCall(classNode, "native0/Loader"));
        assertTrue(hasRegisterCall(classNode, "native0/Loader"));
        assertEquals(7, firstRegisteredClassIndex(classNode, "native0/Loader"));
    }

    @Test
    public void testPrependsEnsureLoadedToExistingClinit() {
        byte[] rewritten = new LoaderInitClassRewriter().injectLoaderCalls(buildClassBytes(true), "native0/Loader", 11);
        ClassNode classNode = readClass(rewritten);

        assertTrue(hasEnsureLoadedCall(classNode, "native0/Loader"));
        assertTrue(hasRegisterCall(classNode, "native0/Loader"));
        assertEquals(11, firstRegisteredClassIndex(classNode, "native0/Loader"));
    }

    @Test
    public void testRewriteKeepsFramesWithoutResolvingMissingTypes() {
        byte[] rewritten = new LoaderInitClassRewriter().injectLoaderCalls(
                buildClassBytesWithMissingFrameType(),
                "native0/Loader",
                3
        );
        ClassNode classNode = readClass(rewritten);

        assertTrue(hasEnsureLoadedCall(classNode, "native0/Loader"));
        assertTrue(hasRegisterCall(classNode, "native0/Loader"));
        assertEquals(3, firstRegisteredClassIndex(classNode, "native0/Loader"));
    }

    private byte[] buildClassBytes(boolean withClinit) {
        ClassNode classNode = new ClassNode(Opcodes.ASM9);
        classNode.version = Opcodes.V21;
        classNode.access = Opcodes.ACC_PUBLIC;
        classNode.name = "sample/Test";
        classNode.superName = "java/lang/Object";

        if (withClinit) {
            MethodNode clinit = new MethodNode(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
            clinit.instructions.add(new InsnNode(Opcodes.RETURN));
            classNode.methods.add(clinit);
        }

        ClassWriter classWriter = new ClassWriter(0);
        classNode.accept(classWriter);
        return classWriter.toByteArray();
    }

    private byte[] buildClassBytesWithMissingFrameType() {
        ClassWriter classWriter = new ClassWriter(0);
        classWriter.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "sample/Framed", null, "java/lang/Object", null);

        MethodVisitor clinit = classWriter.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
        clinit.visitCode();
        Label start = new Label();
        Label branchTarget = new Label();
        clinit.visitLabel(start);
        clinit.visitInsn(Opcodes.ICONST_0);
        clinit.visitJumpInsn(Opcodes.IFEQ, branchTarget);
        clinit.visitInsn(Opcodes.RETURN);
        clinit.visitLabel(branchTarget);
        clinit.visitFrame(Opcodes.F_SAME1, 0, null, 1, new Object[]{"net/minecraft/class_3414"});
        clinit.visitInsn(Opcodes.POP);
        clinit.visitInsn(Opcodes.RETURN);
        clinit.visitMaxs(1, 0);
        clinit.visitEnd();

        classWriter.visitEnd();
        return classWriter.toByteArray();
    }

    private ClassNode readClass(byte[] bytes) {
        ClassNode classNode = new ClassNode(Opcodes.ASM9);
        new ClassReader(bytes).accept(classNode, 0);
        return classNode;
    }

    private boolean hasEnsureLoadedCall(ClassNode classNode, String loaderInternalName) {
        return hasLoaderCall(classNode, loaderInternalName, "ensureLoaded");
    }

    private boolean hasRegisterCall(ClassNode classNode, String loaderInternalName) {
        return hasLoaderCall(classNode, loaderInternalName, "registerNativesForClass");
    }

    private boolean hasLoaderCall(ClassNode classNode, String loaderInternalName, String methodName) {
        for (MethodNode methodNode : classNode.methods) {
            if (!methodNode.name.equals("<clinit>")) {
                continue;
            }
            for (AbstractInsnNode instruction = methodNode.instructions.getFirst();
                 instruction != null;
                 instruction = instruction.getNext()) {
                if (instruction instanceof MethodInsnNode methodInsnNode
                        && methodInsnNode.owner.equals(loaderInternalName)
                        && methodInsnNode.name.equals(methodName)) {
                    return true;
                }
            }
        }
        return false;
    }

    private int firstRegisteredClassIndex(ClassNode classNode, String loaderInternalName) {
        for (MethodNode methodNode : classNode.methods) {
            if (!methodNode.name.equals("<clinit>")) {
                continue;
            }
            for (AbstractInsnNode instruction = methodNode.instructions.getFirst();
                 instruction != null;
                 instruction = instruction.getNext()) {
                if (instruction instanceof MethodInsnNode methodInsnNode
                        && methodInsnNode.owner.equals(loaderInternalName)
                        && methodInsnNode.name.equals("registerNativesForClass")) {
                    for (AbstractInsnNode cursor = instruction.getPrevious(); cursor != null; cursor = cursor.getPrevious()) {
                        if (cursor instanceof LdcInsnNode ldcInsnNode && ldcInsnNode.cst instanceof Integer value) {
                            return value;
                        }
                    }
                }
            }
        }
        throw new AssertionError("registerNativesForClass call not found");
    }
}
