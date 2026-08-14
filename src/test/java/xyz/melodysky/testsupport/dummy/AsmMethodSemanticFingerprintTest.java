package xyz.melodysky.testsupport.dummy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.ConstantDynamic;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Label;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.LineNumberNode;
import org.objectweb.asm.tree.LookupSwitchInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.MultiANewArrayInsnNode;
import org.objectweb.asm.tree.TableSwitchInsnNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.VarInsnNode;

class AsmMethodSemanticFingerprintTest {
    @Test
    void ignoresDebugFramesLabelIdentityAndConstantPoolLayout() {
        MethodNode first = parsedMethod(classWithConstantPoolNoise(false));
        MethodNode second = parsedMethod(classWithConstantPoolNoise(true));
        second.instructions.insertBefore(
                second.instructions.getFirst(),
                new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
        LabelNode debugLabel = new LabelNode();
        second.instructions.insert(debugLabel);
        second.instructions.insert(new LineNumberNode(987, debugLabel));

        assertEquals(
                AsmMethodSemanticFingerprint.canonicalForm(first),
                AsmMethodSemanticFingerprint.canonicalForm(second));
        assertEquals(
                AsmMethodSemanticFingerprint.sha256(first),
                AsmMethodSemanticFingerprint.sha256(second));
    }

    @Test
    void fingerprintsTypedInstructionOperandsAndControlFlowTargets() {
        MethodNode baseline = richMethod();

        MethodNode changedConstant = richMethod();
        ((LdcInsnNode) executable(changedConstant, 1)).cst = "changed";
        assertDifferent(baseline, changedConstant);

        MethodNode changedFieldOwner = richMethod();
        ((FieldInsnNode) executable(changedFieldOwner, 2)).owner = "other/Owner";
        assertDifferent(baseline, changedFieldOwner);

        MethodNode changedCallDescriptor = richMethod();
        ((MethodInsnNode) executable(changedCallDescriptor, 3)).desc = "(J)V";
        assertDifferent(baseline, changedCallDescriptor);

        MethodNode changedJumpTarget = richMethod();
        ((JumpInsnNode) executable(changedJumpTarget, 4)).label = labelAtBoundary(changedJumpTarget, 8);
        assertDifferent(baseline, changedJumpTarget);

        MethodNode changedSwitchKey = richMethod();
        ((LookupSwitchInsnNode) executable(changedSwitchKey, 5)).keys.set(0, 99);
        assertDifferent(baseline, changedSwitchKey);

        MethodNode changedMultiArrayDimensions = richMethod();
        ((MultiANewArrayInsnNode) executable(changedMultiArrayDimensions, 7)).dims = 3;
        assertDifferent(baseline, changedMultiArrayDimensions);
    }

    @Test
    void fingerprintsInvokeDynamicBootstrapHandleAndConstantDynamicArguments() {
        MethodNode baseline = indyMethod("bootstrap.Owner", 0x80000000);
        MethodNode changedOwner = indyMethod("bootstrap.Other", 0x80000000);
        MethodNode changedRawFloatBits = indyMethod("bootstrap.Owner", 0x00000000);

        assertDifferent(baseline, changedOwner);
        assertDifferent(baseline, changedRawFloatBits);
    }

    @Test
    void fingerprintsTryCatchOrderingBoundsTypesAndMaxima() {
        MethodNode baseline = richMethod();

        MethodNode changedType = richMethod();
        changedType.tryCatchBlocks.get(0).type = "java/lang/RuntimeException";
        assertDifferent(baseline, changedType);

        MethodNode changedHandler = richMethod();
        changedHandler.tryCatchBlocks.get(0).handler = labelAtBoundary(changedHandler, 10);
        assertDifferent(baseline, changedHandler);

        MethodNode changedMaxStack = richMethod();
        changedMaxStack.maxStack++;
        assertDifferent(baseline, changedMaxStack);

        MethodNode changedMaxLocals = richMethod();
        changedMaxLocals.maxLocals++;
        assertDifferent(baseline, changedMaxLocals);
    }

    @Test
    void supportsCodeLessAbstractAndNativeMethodsAndFingerprintsMetadata() {
        MethodNode abstractMethod = new MethodNode(
                Opcodes.ASM9,
                Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT,
                "value",
                "(I)Ljava/lang/String;",
                "(I)TT;",
                new String[] {"java/io/IOException"});
        MethodNode equivalent = new MethodNode(
                Opcodes.ASM9,
                Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT,
                "value",
                "(I)Ljava/lang/String;",
                "(I)TT;",
                new String[] {"java/io/IOException"});
        assertEquals(
                AsmMethodSemanticFingerprint.canonicalForm(abstractMethod),
                AsmMethodSemanticFingerprint.canonicalForm(equivalent));

        equivalent.access = Opcodes.ACC_PUBLIC | Opcodes.ACC_NATIVE;
        assertDifferent(abstractMethod, equivalent);

        equivalent = new MethodNode(
                Opcodes.ASM9,
                Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT,
                "value",
                "(I)Ljava/lang/String;",
                "(I)TT;",
                new String[] {"java/lang/Exception"});
        assertDifferent(abstractMethod, equivalent);
    }

    private static MethodNode richMethod() {
        MethodNode method = new MethodNode(
                Opcodes.ASM9,
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "rich",
                "(I)V",
                null,
                new String[] {"java/lang/Exception"});
        LabelNode start = new LabelNode();
        LabelNode branch = new LabelNode();
        LabelNode caseOne = new LabelNode();
        LabelNode caseTwo = new LabelNode();
        LabelNode end = new LabelNode();
        LabelNode handler = new LabelNode();
        method.instructions.add(start);
        method.instructions.add(new VarInsnNode(Opcodes.ILOAD, 0));
        method.instructions.add(new LdcInsnNode("stable"));
        method.instructions.add(new FieldInsnNode(Opcodes.GETSTATIC, "pkg/Owner", "field", "I"));
        method.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "pkg/Owner", "consume", "(I)V", false));
        method.instructions.add(new JumpInsnNode(Opcodes.IFEQ, branch));
        method.instructions.add(new LookupSwitchInsnNode(end, new int[] {7, 11}, new LabelNode[] {caseOne, caseTwo}));
        method.instructions.add(branch);
        method.instructions.add(new TableSwitchInsnNode(1, 2, end, caseOne, caseTwo));
        method.instructions.add(new MultiANewArrayInsnNode("[[I", 2));
        method.instructions.add(caseOne);
        method.instructions.add(new InsnNode(Opcodes.POP));
        method.instructions.add(caseTwo);
        method.instructions.add(new InsnNode(Opcodes.RETURN));
        method.instructions.add(end);
        method.instructions.add(new InsnNode(Opcodes.RETURN));
        method.instructions.add(handler);
        method.instructions.add(new InsnNode(Opcodes.ATHROW));
        method.tryCatchBlocks.add(new TryCatchBlockNode(start, end, handler, "java/lang/Exception"));
        method.maxStack = 3;
        method.maxLocals = 1;
        return method;
    }

    private static MethodNode indyMethod(String bootstrapOwner, int floatBits) {
        MethodNode method = new MethodNode(
                Opcodes.ASM9,
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "indy",
                "()V",
                null,
                null);
        Handle bootstrap = new Handle(
                Opcodes.H_INVOKESTATIC,
                bootstrapOwner,
                "bootstrap",
                "()Ljava/lang/Object;",
                false);
        ConstantDynamic dynamic = new ConstantDynamic(
                "constant",
                "Ljava/lang/Object;",
                bootstrap,
                Type.getType("Ljava/lang/String;"),
                Float.intBitsToFloat(floatBits));
        method.instructions.add(new InvokeDynamicInsnNode("run", "()V", bootstrap, dynamic));
        method.instructions.add(new InsnNode(Opcodes.RETURN));
        method.maxStack = 1;
        method.maxLocals = 0;
        return method;
    }

    private static Object executable(MethodNode method, int index) {
        int executableIndex = 0;
        for (var instruction : method.instructions) {
            if (instruction.getOpcode() < 0) {
                continue;
            }
            if (executableIndex == index) {
                return instruction;
            }
            executableIndex++;
        }
        throw new IllegalArgumentException("no executable instruction at index " + index);
    }

    private static LabelNode labelAtBoundary(MethodNode method, int boundary) {
        int executableIndex = 0;
        for (var instruction : method.instructions) {
            if (instruction instanceof LabelNode label && executableIndex == boundary) {
                return label;
            }
            if (instruction.getOpcode() >= 0) {
                executableIndex++;
            }
        }
        throw new IllegalArgumentException("no label at executable boundary " + boundary);
    }

    private static byte[] classWithConstantPoolNoise(boolean noisy) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "sample/Fixture", null, "java/lang/Object", null);
        if (noisy) {
            writer.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, "noise", "Ljava/lang/String;", null, "noise")
                    .visitEnd();
        }
        var method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "value", "(I)I", null, null);
        method.visitCode();
        Label nonZero = new Label();
        Label end = new Label();
        method.visitVarInsn(Opcodes.ILOAD, 0);
        method.visitJumpInsn(Opcodes.IFNE, nonZero);
        method.visitLdcInsn(17);
        method.visitJumpInsn(Opcodes.GOTO, end);
        method.visitLabel(nonZero);
        method.visitLdcInsn(31);
        method.visitLabel(end);
        method.visitInsn(Opcodes.IRETURN);
        method.visitMaxs(1, 1);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static MethodNode parsedMethod(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node.methods.stream().filter(method -> method.name.equals("value")).findFirst().orElseThrow();
    }

    private static void assertDifferent(MethodNode first, MethodNode second) {
        assertNotEquals(
                AsmMethodSemanticFingerprint.canonicalForm(first),
                AsmMethodSemanticFingerprint.canonicalForm(second));
        assertNotEquals(
                AsmMethodSemanticFingerprint.sha256(first),
                AsmMethodSemanticFingerprint.sha256(second));
    }
}
