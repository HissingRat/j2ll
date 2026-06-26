package xyz.melodysky.testsupport;

import org.objectweb.asm.Handle;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.ConstantDynamic;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.RecordComponentVisitor;
import org.objectweb.asm.Type;

public final class AsmFixtureBuilder implements Opcodes {
    private AsmFixtureBuilder() {
    }

    public static byte[] minimalClass(String internalName) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(V17, ACC_PUBLIC | ACC_SUPER, internalName, null, "java/lang/Object", null);
        emitDefaultConstructor(writer);
        writer.visitEnd();
        return writer.toByteArray();
    }

    public static byte[] classWithIntMethod(String internalName, String methodName, int value) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(V17, ACC_PUBLIC | ACC_SUPER, internalName, null, "java/lang/Object", null);
        emitDefaultConstructor(writer);
        MethodVisitor method = writer.visitMethod(ACC_PUBLIC, methodName, "()I", null, null);
        method.visitCode();
        method.visitLdcInsn(value);
        method.visitInsn(IRETURN);
        method.visitMaxs(1, 1);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    public static byte[] classWithConditionalMethod(String internalName) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(V17, ACC_PUBLIC | ACC_SUPER, internalName, null, "java/lang/Object", null);
        emitDefaultConstructor(writer);

        MethodVisitor method = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "choose", "(I)I", null, null);
        org.objectweb.asm.Label zero = new org.objectweb.asm.Label();
        method.visitCode();
        method.visitVarInsn(ILOAD, 0);
        method.visitJumpInsn(IFEQ, zero);
        method.visitInsn(ICONST_1);
        method.visitInsn(IRETURN);
        method.visitLabel(zero);
        method.visitInsn(ICONST_0);
        method.visitInsn(IRETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();

        writer.visitEnd();
        return writer.toByteArray();
    }

    public static byte[] classWithIfMergeMethod(String internalName) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(V17, ACC_PUBLIC | ACC_SUPER, internalName, null, "java/lang/Object", null);
        emitDefaultConstructor(writer);

        MethodVisitor method = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "merged", "(I)I", null, null);
        org.objectweb.asm.Label zero = new org.objectweb.asm.Label();
        org.objectweb.asm.Label join = new org.objectweb.asm.Label();
        method.visitCode();
        method.visitVarInsn(ILOAD, 0);
        method.visitJumpInsn(IFEQ, zero);
        method.visitInsn(ICONST_2);
        method.visitVarInsn(ISTORE, 1);
        method.visitJumpInsn(GOTO, join);
        method.visitLabel(zero);
        method.visitInsn(ICONST_1);
        method.visitVarInsn(ISTORE, 1);
        method.visitLabel(join);
        method.visitVarInsn(ILOAD, 1);
        method.visitInsn(IRETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();

        writer.visitEnd();
        return writer.toByteArray();
    }

    public static byte[] classWithStackMergeMethod(String internalName) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(V17, ACC_PUBLIC | ACC_SUPER, internalName, null, "java/lang/Object", null);
        emitDefaultConstructor(writer);

        MethodVisitor method = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "stackMerged", "(I)I", null, null);
        org.objectweb.asm.Label zero = new org.objectweb.asm.Label();
        org.objectweb.asm.Label join = new org.objectweb.asm.Label();
        method.visitCode();
        method.visitVarInsn(ILOAD, 0);
        method.visitJumpInsn(IFEQ, zero);
        method.visitInsn(ICONST_2);
        method.visitJumpInsn(GOTO, join);
        method.visitLabel(zero);
        method.visitInsn(ICONST_1);
        method.visitLabel(join);
        method.visitInsn(IRETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();

        writer.visitEnd();
        return writer.toByteArray();
    }

    public static byte[] classWithSimpleLoopCounter(String internalName) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(V17, ACC_PUBLIC | ACC_SUPER, internalName, null, "java/lang/Object", null);
        emitDefaultConstructor(writer);

        MethodVisitor method = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "count", "(I)I", null, null);
        org.objectweb.asm.Label loop = new org.objectweb.asm.Label();
        org.objectweb.asm.Label done = new org.objectweb.asm.Label();
        method.visitCode();
        method.visitInsn(ICONST_0);
        method.visitVarInsn(ISTORE, 1);
        method.visitLabel(loop);
        method.visitVarInsn(ILOAD, 1);
        method.visitVarInsn(ILOAD, 0);
        method.visitJumpInsn(IF_ICMPGE, done);
        method.visitIincInsn(1, 1);
        method.visitJumpInsn(GOTO, loop);
        method.visitLabel(done);
        method.visitVarInsn(ILOAD, 1);
        method.visitInsn(IRETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();

        writer.visitEnd();
        return writer.toByteArray();
    }

    public static byte[] classWithSwitchStackMergeMethod(String internalName) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(V17, ACC_PUBLIC | ACC_SUPER, internalName, null, "java/lang/Object", null);
        emitDefaultConstructor(writer);

        MethodVisitor method = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "selectMerged", "(I)I", null, null);
        org.objectweb.asm.Label caseZero = new org.objectweb.asm.Label();
        org.objectweb.asm.Label caseOne = new org.objectweb.asm.Label();
        org.objectweb.asm.Label defaultCase = new org.objectweb.asm.Label();
        org.objectweb.asm.Label join = new org.objectweb.asm.Label();
        method.visitCode();
        method.visitVarInsn(ILOAD, 0);
        method.visitTableSwitchInsn(0, 1, defaultCase, caseZero, caseOne);
        method.visitLabel(caseZero);
        method.visitInsn(ICONST_0);
        method.visitJumpInsn(GOTO, join);
        method.visitLabel(caseOne);
        method.visitInsn(ICONST_1);
        method.visitJumpInsn(GOTO, join);
        method.visitLabel(defaultCase);
        method.visitInsn(ICONST_M1);
        method.visitLabel(join);
        method.visitInsn(IRETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();

        writer.visitEnd();
        return writer.toByteArray();
    }

    public static byte[] classWithBadStackHeightMerge(String internalName) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(V17, ACC_PUBLIC | ACC_SUPER, internalName, null, "java/lang/Object", null);
        emitDefaultConstructor(writer);

        MethodVisitor method = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "badStack", "(I)V", null, null);
        org.objectweb.asm.Label zero = new org.objectweb.asm.Label();
        org.objectweb.asm.Label join = new org.objectweb.asm.Label();
        method.visitCode();
        method.visitVarInsn(ILOAD, 0);
        method.visitJumpInsn(IFEQ, zero);
        method.visitInsn(ICONST_1);
        method.visitJumpInsn(GOTO, join);
        method.visitLabel(zero);
        method.visitLabel(join);
        method.visitInsn(RETURN);
        method.visitMaxs(1, 1);
        method.visitEnd();

        writer.visitEnd();
        return writer.toByteArray();
    }

    public static byte[] classWithBadStackTypeMerge(String internalName) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(V17, ACC_PUBLIC | ACC_SUPER, internalName, null, "java/lang/Object", null);
        emitDefaultConstructor(writer);

        MethodVisitor method = writer.visitMethod(
                ACC_PUBLIC | ACC_STATIC,
                "badType",
                "(I)Ljava/lang/Object;",
                null,
                null);
        org.objectweb.asm.Label zero = new org.objectweb.asm.Label();
        org.objectweb.asm.Label join = new org.objectweb.asm.Label();
        method.visitCode();
        method.visitVarInsn(ILOAD, 0);
        method.visitJumpInsn(IFEQ, zero);
        method.visitInsn(ICONST_1);
        method.visitJumpInsn(GOTO, join);
        method.visitLabel(zero);
        method.visitInsn(ACONST_NULL);
        method.visitLabel(join);
        method.visitInsn(ARETURN);
        method.visitMaxs(1, 1);
        method.visitEnd();

        writer.visitEnd();
        return writer.toByteArray();
    }

    public static byte[] classWithBadLocalSlotMerge(String internalName) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(V17, ACC_PUBLIC | ACC_SUPER, internalName, null, "java/lang/Object", null);
        emitDefaultConstructor(writer);

        MethodVisitor method = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "badLocal", "(I)V", null, null);
        org.objectweb.asm.Label zero = new org.objectweb.asm.Label();
        org.objectweb.asm.Label join = new org.objectweb.asm.Label();
        method.visitCode();
        method.visitVarInsn(ILOAD, 0);
        method.visitJumpInsn(IFEQ, zero);
        method.visitInsn(ICONST_1);
        method.visitVarInsn(ISTORE, 1);
        method.visitJumpInsn(GOTO, join);
        method.visitLabel(zero);
        method.visitLabel(join);
        method.visitInsn(RETURN);
        method.visitMaxs(1, 2);
        method.visitEnd();

        writer.visitEnd();
        return writer.toByteArray();
    }

    public static byte[] classWithAddMethod(String internalName) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(V17, ACC_PUBLIC | ACC_SUPER, internalName, null, "java/lang/Object", null);
        MethodVisitor method = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "add", "(II)I", null, null);
        method.visitCode();
        method.visitVarInsn(ILOAD, 0);
        method.visitVarInsn(ILOAD, 1);
        method.visitInsn(IADD);
        method.visitInsn(IRETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    public static byte[] classWithLongAddMethod(String internalName) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(V17, ACC_PUBLIC | ACC_SUPER, internalName, null, "java/lang/Object", null);
        MethodVisitor method = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "addLong", "(JJ)J", null, null);
        method.visitCode();
        method.visitVarInsn(LLOAD, 0);
        method.visitVarInsn(LLOAD, 2);
        method.visitInsn(LADD);
        method.visitInsn(LRETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    public static byte[] classWithFloatNegMethod(String internalName) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(V17, ACC_PUBLIC | ACC_SUPER, internalName, null, "java/lang/Object", null);
        MethodVisitor method = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "negFloat", "(F)F", null, null);
        method.visitCode();
        method.visitVarInsn(FLOAD, 0);
        method.visitInsn(FNEG);
        method.visitInsn(FRETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    public static byte[] classWithDoubleLdcMethod(String internalName) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(V17, ACC_PUBLIC | ACC_SUPER, internalName, null, "java/lang/Object", null);
        MethodVisitor method = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "doubleConst", "()D", null, null);
        method.visitCode();
        method.visitLdcInsn(2.5D);
        method.visitInsn(DRETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    public static byte[] classWithIntRemainderNegMethod(String internalName) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(V17, ACC_PUBLIC | ACC_SUPER, internalName, null, "java/lang/Object", null);
        MethodVisitor method = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "remNeg", "(II)I", null, null);
        method.visitCode();
        method.visitVarInsn(ILOAD, 0);
        method.visitVarInsn(ILOAD, 1);
        method.visitInsn(IREM);
        method.visitInsn(INEG);
        method.visitInsn(IRETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    public static byte[] classWithWideLocalIincMethod(String internalName) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(V17, ACC_PUBLIC | ACC_SUPER, internalName, null, "java/lang/Object", null);
        MethodVisitor method = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "wideIinc", "()I", null, null);
        method.visitCode();
        method.visitIntInsn(BIPUSH, 40);
        method.visitVarInsn(ISTORE, 300);
        method.visitIincInsn(300, 2);
        method.visitVarInsn(ILOAD, 300);
        method.visitInsn(IRETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    public static byte[] classWithStackPermutationMethods(String internalName) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(V17, ACC_PUBLIC | ACC_SUPER, internalName, null, "java/lang/Object", null);

        MethodVisitor stackInt = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "stackInt", "(II)I", null, null);
        stackInt.visitCode();
        stackInt.visitVarInsn(ILOAD, 0);
        stackInt.visitVarInsn(ILOAD, 1);
        stackInt.visitInsn(DUP_X1);
        stackInt.visitInsn(POP);
        stackInt.visitInsn(SWAP);
        stackInt.visitInsn(ISUB);
        stackInt.visitInsn(IRETURN);
        stackInt.visitMaxs(0, 0);
        stackInt.visitEnd();

        MethodVisitor dup2Int = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "dup2Int", "(II)I", null, null);
        dup2Int.visitCode();
        dup2Int.visitVarInsn(ILOAD, 0);
        dup2Int.visitVarInsn(ILOAD, 1);
        dup2Int.visitInsn(DUP2);
        dup2Int.visitInsn(IADD);
        dup2Int.visitInsn(IADD);
        dup2Int.visitInsn(IADD);
        dup2Int.visitInsn(IRETURN);
        dup2Int.visitMaxs(0, 0);
        dup2Int.visitEnd();

        MethodVisitor dupX2Long = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "dupX2Long", "(JI)I", null, null);
        dupX2Long.visitCode();
        dupX2Long.visitVarInsn(LLOAD, 0);
        dupX2Long.visitVarInsn(ILOAD, 2);
        dupX2Long.visitInsn(DUP_X2);
        dupX2Long.visitInsn(POP);
        dupX2Long.visitInsn(POP2);
        dupX2Long.visitInsn(IRETURN);
        dupX2Long.visitMaxs(0, 0);
        dupX2Long.visitEnd();

        MethodVisitor dup2X1Int = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "dup2X1Int", "(III)I", null, null);
        dup2X1Int.visitCode();
        dup2X1Int.visitVarInsn(ILOAD, 0);
        dup2X1Int.visitVarInsn(ILOAD, 1);
        dup2X1Int.visitVarInsn(ILOAD, 2);
        dup2X1Int.visitInsn(DUP2_X1);
        dup2X1Int.visitInsn(POP2);
        dup2X1Int.visitInsn(IADD);
        dup2X1Int.visitInsn(IADD);
        dup2X1Int.visitInsn(IRETURN);
        dup2X1Int.visitMaxs(0, 0);
        dup2X1Int.visitEnd();

        MethodVisitor dup2X2Int = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "dup2X2Int", "(IIII)I", null, null);
        dup2X2Int.visitCode();
        dup2X2Int.visitVarInsn(ILOAD, 0);
        dup2X2Int.visitVarInsn(ILOAD, 1);
        dup2X2Int.visitVarInsn(ILOAD, 2);
        dup2X2Int.visitVarInsn(ILOAD, 3);
        dup2X2Int.visitInsn(DUP2_X2);
        dup2X2Int.visitInsn(POP2);
        dup2X2Int.visitInsn(IADD);
        dup2X2Int.visitInsn(IADD);
        dup2X2Int.visitInsn(IADD);
        dup2X2Int.visitInsn(IRETURN);
        dup2X2Int.visitMaxs(0, 0);
        dup2X2Int.visitEnd();

        writer.visitEnd();
        return writer.toByteArray();
    }

    public static byte[] classWithBitwiseShiftMethod(String internalName) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(V17, ACC_PUBLIC | ACC_SUPER, internalName, null, "java/lang/Object", null);
        MethodVisitor method = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "bitShift", "(III)I", null, null);
        method.visitCode();
        method.visitVarInsn(ILOAD, 0);
        method.visitVarInsn(ILOAD, 1);
        method.visitInsn(IAND);
        method.visitVarInsn(ILOAD, 2);
        method.visitInsn(ISHL);
        method.visitVarInsn(ILOAD, 1);
        method.visitInsn(IXOR);
        method.visitVarInsn(ILOAD, 2);
        method.visitInsn(IUSHR);
        method.visitInsn(IRETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    public static byte[] classWithLongBitwiseShiftMethod(String internalName) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(V17, ACC_PUBLIC | ACC_SUPER, internalName, null, "java/lang/Object", null);
        MethodVisitor method = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "longBitShift", "(JI)J", null, null);
        method.visitCode();
        method.visitVarInsn(LLOAD, 0);
        method.visitVarInsn(ILOAD, 2);
        method.visitInsn(LSHR);
        method.visitVarInsn(LLOAD, 0);
        method.visitInsn(LOR);
        method.visitInsn(LRETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    public static byte[] classWithPrimitiveConversionMethods(String internalName) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(V17, ACC_PUBLIC | ACC_SUPER, internalName, null, "java/lang/Object", null);

        MethodVisitor narrow = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "narrow", "(J)I", null, null);
        narrow.visitCode();
        narrow.visitVarInsn(LLOAD, 0);
        narrow.visitInsn(L2I);
        narrow.visitInsn(I2B);
        narrow.visitInsn(IRETURN);
        narrow.visitMaxs(0, 0);
        narrow.visitEnd();

        MethodVisitor floatToInt = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "floatToInt", "(F)I", null, null);
        floatToInt.visitCode();
        floatToInt.visitVarInsn(FLOAD, 0);
        floatToInt.visitInsn(F2I);
        floatToInt.visitInsn(IRETURN);
        floatToInt.visitMaxs(0, 0);
        floatToInt.visitEnd();

        MethodVisitor floatToDouble = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "floatToDouble", "(F)D", null, null);
        floatToDouble.visitCode();
        floatToDouble.visitVarInsn(FLOAD, 0);
        floatToDouble.visitInsn(F2D);
        floatToDouble.visitInsn(DRETURN);
        floatToDouble.visitMaxs(0, 0);
        floatToDouble.visitEnd();

        writer.visitEnd();
        return writer.toByteArray();
    }

    public static byte[] classWithJvmComparisonMethods(String internalName) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(V17, ACC_PUBLIC | ACC_SUPER, internalName, null, "java/lang/Object", null);

        MethodVisitor longCmp = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "longCmp", "(JJ)I", null, null);
        longCmp.visitCode();
        longCmp.visitVarInsn(LLOAD, 0);
        longCmp.visitVarInsn(LLOAD, 2);
        longCmp.visitInsn(LCMP);
        longCmp.visitInsn(IRETURN);
        longCmp.visitMaxs(0, 0);
        longCmp.visitEnd();

        MethodVisitor floatCmp = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "floatCmp", "(FF)I", null, null);
        floatCmp.visitCode();
        floatCmp.visitVarInsn(FLOAD, 0);
        floatCmp.visitVarInsn(FLOAD, 1);
        floatCmp.visitInsn(FCMPL);
        floatCmp.visitInsn(IRETURN);
        floatCmp.visitMaxs(0, 0);
        floatCmp.visitEnd();

        writer.visitEnd();
        return writer.toByteArray();
    }

    public static byte[] classWithReferenceBranchMethods(String internalName) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(V17, ACC_PUBLIC | ACC_SUPER, internalName, null, "java/lang/Object", null);

        MethodVisitor same = writer.visitMethod(
                ACC_PUBLIC | ACC_STATIC,
                "same",
                "(Ljava/lang/Object;Ljava/lang/Object;)I",
                null,
                null);
        org.objectweb.asm.Label equal = new org.objectweb.asm.Label();
        same.visitCode();
        same.visitVarInsn(ALOAD, 0);
        same.visitVarInsn(ALOAD, 1);
        same.visitJumpInsn(IF_ACMPEQ, equal);
        same.visitInsn(ICONST_0);
        same.visitInsn(IRETURN);
        same.visitLabel(equal);
        same.visitInsn(ICONST_1);
        same.visitInsn(IRETURN);
        same.visitMaxs(0, 0);
        same.visitEnd();

        MethodVisitor isNull = writer.visitMethod(
                ACC_PUBLIC | ACC_STATIC,
                "isNull",
                "(Ljava/lang/Object;)I",
                null,
                null);
        org.objectweb.asm.Label nullCase = new org.objectweb.asm.Label();
        isNull.visitCode();
        isNull.visitVarInsn(ALOAD, 0);
        isNull.visitJumpInsn(IFNULL, nullCase);
        isNull.visitInsn(ICONST_0);
        isNull.visitInsn(IRETURN);
        isNull.visitLabel(nullCase);
        isNull.visitInsn(ICONST_1);
        isNull.visitInsn(IRETURN);
        isNull.visitMaxs(0, 0);
        isNull.visitEnd();

        writer.visitEnd();
        return writer.toByteArray();
    }

    public static byte[] classWithI2DMethod(String internalName) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(V17, ACC_PUBLIC | ACC_SUPER, internalName, null, "java/lang/Object", null);
        MethodVisitor method = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "toDouble", "(I)D", null, null);
        method.visitCode();
        method.visitVarInsn(ILOAD, 0);
        method.visitInsn(I2D);
        method.visitInsn(DRETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    public static byte[] classWithNullReturnMethod(String internalName) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(V17, ACC_PUBLIC | ACC_SUPER, internalName, null, "java/lang/Object", null);
        MethodVisitor method = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "nil", "()Ljava/lang/Object;", null, null);
        method.visitCode();
        method.visitInsn(ACONST_NULL);
        method.visitInsn(ARETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    public static byte[] classWithSymbolicLdcMethods(String internalName) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(V17, ACC_PUBLIC | ACC_SUPER, internalName, null, "java/lang/Object", null);

        MethodVisitor string = writer.visitMethod(
                ACC_PUBLIC | ACC_STATIC,
                "stringConst",
                "()Ljava/lang/String;",
                null,
                null);
        string.visitCode();
        string.visitLdcInsn("secret-value");
        string.visitInsn(ARETURN);
        string.visitMaxs(0, 0);
        string.visitEnd();

        MethodVisitor clazz = writer.visitMethod(
                ACC_PUBLIC | ACC_STATIC,
                "classConst",
                "()Ljava/lang/Class;",
                null,
                null);
        clazz.visitCode();
        clazz.visitLdcInsn(Type.getType("Ljava/lang/String;"));
        clazz.visitInsn(ARETURN);
        clazz.visitMaxs(0, 0);
        clazz.visitEnd();

        MethodVisitor methodType = writer.visitMethod(
                ACC_PUBLIC | ACC_STATIC,
                "methodTypeConst",
                "()Ljava/lang/invoke/MethodType;",
                null,
                null);
        methodType.visitCode();
        methodType.visitLdcInsn(Type.getMethodType("(I)Ljava/lang/String;"));
        methodType.visitInsn(ARETURN);
        methodType.visitMaxs(0, 0);
        methodType.visitEnd();

        MethodVisitor target = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "target", "()I", null, null);
        target.visitCode();
        target.visitInsn(ICONST_1);
        target.visitInsn(IRETURN);
        target.visitMaxs(0, 0);
        target.visitEnd();

        MethodVisitor methodHandle = writer.visitMethod(
                ACC_PUBLIC | ACC_STATIC,
                "methodHandleConst",
                "()Ljava/lang/invoke/MethodHandle;",
                null,
                null);
        methodHandle.visitCode();
        methodHandle.visitLdcInsn(new Handle(H_INVOKESTATIC, internalName, "target", "()I", false));
        methodHandle.visitInsn(ARETURN);
        methodHandle.visitMaxs(0, 0);
        methodHandle.visitEnd();

        writer.visitEnd();
        return writer.toByteArray();
    }

    public static byte[] classWithStaticFieldRead(String internalName) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(V17, ACC_PUBLIC | ACC_SUPER, internalName, null, "java/lang/Object", null);
        writer.visitField(ACC_PUBLIC | ACC_STATIC, "VALUE", "I", null, null).visitEnd();
        MethodVisitor method = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "getValue", "()I", null, null);
        method.visitCode();
        method.visitFieldInsn(GETSTATIC, internalName, "VALUE", "I");
        method.visitInsn(IRETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    public static byte[] classWithExternalStaticFieldRead(String internalName, String fieldOwner) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(V17, ACC_PUBLIC | ACC_SUPER, internalName, null, "java/lang/Object", null);
        MethodVisitor method = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "getValue", "()I", null, null);
        method.visitCode();
        method.visitFieldInsn(GETSTATIC, fieldOwner, "VALUE", "I");
        method.visitInsn(IRETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    public static byte[] classWithStaticFieldWrite(String internalName) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(V17, ACC_PUBLIC | ACC_SUPER, internalName, null, "java/lang/Object", null);
        writer.visitField(ACC_PUBLIC | ACC_STATIC, "VALUE", "I", null, null).visitEnd();
        MethodVisitor method = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "setValue", "(I)V", null, null);
        method.visitCode();
        method.visitVarInsn(ILOAD, 0);
        method.visitFieldInsn(PUTSTATIC, internalName, "VALUE", "I");
        method.visitInsn(RETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    public static byte[] classWithExternalStaticFieldWrite(String internalName, String fieldOwner) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(V17, ACC_PUBLIC | ACC_SUPER, internalName, null, "java/lang/Object", null);
        MethodVisitor method = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "setValue", "(I)V", null, null);
        method.visitCode();
        method.visitVarInsn(ILOAD, 0);
        method.visitFieldInsn(PUTSTATIC, fieldOwner, "VALUE", "I");
        method.visitInsn(RETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    public static byte[] classWithInstanceFieldRead(String internalName) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(V17, ACC_PUBLIC | ACC_SUPER, internalName, null, "java/lang/Object", null);
        writer.visitField(ACC_PUBLIC, "value", "I", null, null).visitEnd();
        MethodVisitor method = writer.visitMethod(ACC_PUBLIC, "read", "()I", null, null);
        method.visitCode();
        method.visitVarInsn(ALOAD, 0);
        method.visitFieldInsn(GETFIELD, internalName, "value", "I");
        method.visitInsn(IRETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    public static byte[] classWithStaticCall(String internalName) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(V17, ACC_PUBLIC | ACC_SUPER, internalName, null, "java/lang/Object", null);
        MethodVisitor value = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "value", "()I", null, null);
        value.visitCode();
        value.visitIntInsn(BIPUSH, 7);
        value.visitInsn(IRETURN);
        value.visitMaxs(0, 0);
        value.visitEnd();
        MethodVisitor call = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "call", "()I", null, null);
        call.visitCode();
        call.visitMethodInsn(INVOKESTATIC, internalName, "value", "()I", false);
        call.visitInsn(IRETURN);
        call.visitMaxs(0, 0);
        call.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    public static byte[] classWithExternalStaticCall(String internalName, String targetOwner) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(V17, ACC_PUBLIC | ACC_SUPER, internalName, null, "java/lang/Object", null);
        MethodVisitor call = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "call", "()I", null, null);
        call.visitCode();
        call.visitMethodInsn(INVOKESTATIC, targetOwner, "value", "()I", false);
        call.visitInsn(IRETURN);
        call.visitMaxs(0, 0);
        call.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    public static byte[] classWithSpecialCall(String internalName) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(V17, ACC_PUBLIC | ACC_SUPER, internalName, null, "java/lang/Object", null);
        MethodVisitor value = writer.visitMethod(ACC_PRIVATE, "value", "()I", null, null);
        value.visitCode();
        value.visitInsn(ICONST_3);
        value.visitInsn(IRETURN);
        value.visitMaxs(0, 0);
        value.visitEnd();
        MethodVisitor call = writer.visitMethod(ACC_PUBLIC, "callPrivate", "()I", null, null);
        call.visitCode();
        call.visitVarInsn(ALOAD, 0);
        call.visitMethodInsn(INVOKESPECIAL, internalName, "value", "()I", false);
        call.visitInsn(IRETURN);
        call.visitMaxs(0, 0);
        call.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    public static byte[] classWithGotoAndDeadCode(String internalName) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(V17, ACC_PUBLIC | ACC_SUPER, internalName, null, "java/lang/Object", null);
        emitDefaultConstructor(writer);

        MethodVisitor method = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "jump", "()I", null, null);
        org.objectweb.asm.Label live = new org.objectweb.asm.Label();
        method.visitCode();
        method.visitJumpInsn(GOTO, live);
        method.visitInsn(ICONST_5);
        method.visitInsn(IRETURN);
        method.visitLabel(live);
        method.visitInsn(ICONST_1);
        method.visitInsn(IRETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();

        writer.visitEnd();
        return writer.toByteArray();
    }

    public static byte[] classWithTableSwitchMethod(String internalName) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(V17, ACC_PUBLIC | ACC_SUPER, internalName, null, "java/lang/Object", null);
        emitDefaultConstructor(writer);

        MethodVisitor method = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "select", "(I)I", null, null);
        org.objectweb.asm.Label caseZero = new org.objectweb.asm.Label();
        org.objectweb.asm.Label caseOne = new org.objectweb.asm.Label();
        org.objectweb.asm.Label defaultCase = new org.objectweb.asm.Label();
        method.visitCode();
        method.visitVarInsn(ILOAD, 0);
        method.visitTableSwitchInsn(0, 1, defaultCase, caseZero, caseOne);
        method.visitLabel(caseZero);
        method.visitInsn(ICONST_0);
        method.visitInsn(IRETURN);
        method.visitLabel(caseOne);
        method.visitInsn(ICONST_1);
        method.visitInsn(IRETURN);
        method.visitLabel(defaultCase);
        method.visitInsn(ICONST_M1);
        method.visitInsn(IRETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();

        writer.visitEnd();
        return writer.toByteArray();
    }

    public static byte[] classWithLookupSwitchMethod(String internalName) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(V17, ACC_PUBLIC | ACC_SUPER, internalName, null, "java/lang/Object", null);
        emitDefaultConstructor(writer);

        MethodVisitor method = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "lookup", "(I)I", null, null);
        org.objectweb.asm.Label ten = new org.objectweb.asm.Label();
        org.objectweb.asm.Label twenty = new org.objectweb.asm.Label();
        org.objectweb.asm.Label defaultCase = new org.objectweb.asm.Label();
        method.visitCode();
        method.visitVarInsn(ILOAD, 0);
        method.visitLookupSwitchInsn(defaultCase, new int[] {10, 20}, new org.objectweb.asm.Label[] {ten, twenty});
        method.visitLabel(ten);
        method.visitIntInsn(BIPUSH, 10);
        method.visitInsn(IRETURN);
        method.visitLabel(twenty);
        method.visitIntInsn(BIPUSH, 20);
        method.visitInsn(IRETURN);
        method.visitLabel(defaultCase);
        method.visitInsn(ICONST_M1);
        method.visitInsn(IRETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();

        writer.visitEnd();
        return writer.toByteArray();
    }

    public static byte[] classWithTryCatchMethod(String internalName) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(V17, ACC_PUBLIC | ACC_SUPER, internalName, null, "java/lang/Object", null);
        emitDefaultConstructor(writer);

        MethodVisitor method = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "guarded", "()I", null, null);
        org.objectweb.asm.Label start = new org.objectweb.asm.Label();
        org.objectweb.asm.Label end = new org.objectweb.asm.Label();
        org.objectweb.asm.Label handler = new org.objectweb.asm.Label();
        method.visitTryCatchBlock(start, end, handler, "java/lang/RuntimeException");
        method.visitCode();
        method.visitLabel(start);
        method.visitInsn(ICONST_1);
        method.visitInsn(IRETURN);
        method.visitLabel(end);
        method.visitLabel(handler);
        method.visitVarInsn(ASTORE, 0);
        method.visitInsn(ICONST_2);
        method.visitInsn(IRETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();

        writer.visitEnd();
        return writer.toByteArray();
    }

    public static byte[] classWithAthrowMethod(String internalName) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(V17, ACC_PUBLIC | ACC_SUPER, internalName, null, "java/lang/Object", null);
        emitDefaultConstructor(writer);

        MethodVisitor method = writer.visitMethod(
                ACC_PUBLIC | ACC_STATIC,
                "raise",
                "(Ljava/lang/RuntimeException;)V",
                null,
                null);
        method.visitCode();
        method.visitVarInsn(ALOAD, 0);
        method.visitInsn(ATHROW);
        method.visitMaxs(0, 0);
        method.visitEnd();

        writer.visitEnd();
        return writer.toByteArray();
    }

    public static byte[] classWithCatchAllFinallyShape(String internalName) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(V17, ACC_PUBLIC | ACC_SUPER, internalName, null, "java/lang/Object", null);
        emitDefaultConstructor(writer);

        MethodVisitor method = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "cleanup", "()V", null, null);
        org.objectweb.asm.Label start = new org.objectweb.asm.Label();
        org.objectweb.asm.Label end = new org.objectweb.asm.Label();
        org.objectweb.asm.Label handler = new org.objectweb.asm.Label();
        method.visitTryCatchBlock(start, end, handler, null);
        method.visitCode();
        method.visitLabel(start);
        method.visitInsn(ICONST_1);
        method.visitInsn(POP);
        method.visitLabel(end);
        method.visitInsn(RETURN);
        method.visitLabel(handler);
        method.visitVarInsn(ASTORE, 0);
        method.visitVarInsn(ALOAD, 0);
        method.visitInsn(ATHROW);
        method.visitMaxs(0, 0);
        method.visitEnd();

        writer.visitEnd();
        return writer.toByteArray();
    }

    public static byte[] classWithFinallyCleanupShape(String internalName) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(V17, ACC_PUBLIC | ACC_SUPER, internalName, null, "java/lang/Object", null);

        MethodVisitor cleanup = writer.visitMethod(ACC_PRIVATE | ACC_STATIC, "cleanupMarker", "()V", null, null);
        cleanup.visitCode();
        cleanup.visitInsn(RETURN);
        cleanup.visitMaxs(0, 0);
        cleanup.visitEnd();

        MethodVisitor method = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "withCleanup", "()V", null, null);
        org.objectweb.asm.Label start = new org.objectweb.asm.Label();
        org.objectweb.asm.Label end = new org.objectweb.asm.Label();
        org.objectweb.asm.Label handler = new org.objectweb.asm.Label();
        method.visitTryCatchBlock(start, end, handler, null);
        method.visitCode();
        method.visitLabel(start);
        method.visitInsn(ICONST_1);
        method.visitInsn(POP);
        method.visitLabel(end);
        method.visitMethodInsn(INVOKESTATIC, internalName, "cleanupMarker", "()V", false);
        method.visitInsn(RETURN);
        method.visitLabel(handler);
        method.visitVarInsn(ASTORE, 0);
        method.visitMethodInsn(INVOKESTATIC, internalName, "cleanupMarker", "()V", false);
        method.visitVarInsn(ALOAD, 0);
        method.visitInsn(ATHROW);
        method.visitMaxs(0, 0);
        method.visitEnd();

        writer.visitEnd();
        return writer.toByteArray();
    }

    public static byte[] classWithUnsupportedMultiExitFinallyShape(String internalName) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(V17, ACC_PUBLIC | ACC_SUPER, internalName, null, "java/lang/Object", null);
        emitDefaultConstructor(writer);

        MethodVisitor method = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "badFinally", "()V", null, null);
        org.objectweb.asm.Label start = new org.objectweb.asm.Label();
        org.objectweb.asm.Label end = new org.objectweb.asm.Label();
        org.objectweb.asm.Label handler = new org.objectweb.asm.Label();
        method.visitTryCatchBlock(start, end, handler, null);
        method.visitCode();
        method.visitLabel(start);
        method.visitInsn(ICONST_1);
        method.visitInsn(POP);
        method.visitLabel(end);
        method.visitInsn(RETURN);
        method.visitLabel(handler);
        method.visitVarInsn(ASTORE, 0);
        method.visitInsn(RETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();

        writer.visitEnd();
        return writer.toByteArray();
    }

    public static byte[] classWithIntDivideMethod(String internalName) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(V17, ACC_PUBLIC | ACC_SUPER, internalName, null, "java/lang/Object", null);
        emitDefaultConstructor(writer);

        MethodVisitor method = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "divide", "(II)I", null, null);
        method.visitCode();
        method.visitVarInsn(ILOAD, 0);
        method.visitVarInsn(ILOAD, 1);
        method.visitInsn(IDIV);
        method.visitInsn(IRETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();

        writer.visitEnd();
        return writer.toByteArray();
    }

    public static byte[] classWithMonitorBlockMethod(String internalName) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(V17, ACC_PUBLIC | ACC_SUPER, internalName, null, "java/lang/Object", null);
        emitDefaultConstructor(writer);

        MethodVisitor method = writer.visitMethod(
                ACC_PUBLIC | ACC_STATIC,
                "locked",
                "(Ljava/lang/Object;)V",
                null,
                null);
        method.visitCode();
        method.visitVarInsn(ALOAD, 0);
        method.visitInsn(MONITORENTER);
        method.visitVarInsn(ALOAD, 0);
        method.visitInsn(MONITOREXIT);
        method.visitInsn(RETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();

        writer.visitEnd();
        return writer.toByteArray();
    }

    public static byte[] classWithSynchronizedMethod(String internalName) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(V17, ACC_PUBLIC | ACC_SUPER, internalName, null, "java/lang/Object", null);
        emitDefaultConstructor(writer);

        MethodVisitor method = writer.visitMethod(
                ACC_PUBLIC | ACC_STATIC | ACC_SYNCHRONIZED,
                "sync",
                "()V",
                null,
                null);
        method.visitCode();
        method.visitInsn(RETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();

        writer.visitEnd();
        return writer.toByteArray();
    }

    public static byte[] classWithSynchronizedInstanceMethod(String internalName) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(V17, ACC_PUBLIC | ACC_SUPER, internalName, null, "java/lang/Object", null);
        emitDefaultConstructor(writer);

        MethodVisitor method = writer.visitMethod(
                ACC_PUBLIC | ACC_SYNCHRONIZED,
                "syncInstance",
                "()V",
                null,
                null);
        method.visitCode();
        method.visitInsn(RETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();

        writer.visitEnd();
        return writer.toByteArray();
    }

    public static byte[] classWithSynchronizedThrowMethod(String internalName) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(V17, ACC_PUBLIC | ACC_SUPER, internalName, null, "java/lang/Object", null);
        emitDefaultConstructor(writer);

        MethodVisitor method = writer.visitMethod(
                ACC_PUBLIC | ACC_SYNCHRONIZED,
                "syncThrow",
                "(Ljava/lang/RuntimeException;)V",
                null,
                null);
        method.visitCode();
        method.visitVarInsn(ALOAD, 1);
        method.visitInsn(ATHROW);
        method.visitMaxs(0, 0);
        method.visitEnd();

        writer.visitEnd();
        return writer.toByteArray();
    }

    public static byte[] classWithNestedMonitorBlockMethod(String internalName) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(V17, ACC_PUBLIC | ACC_SUPER, internalName, null, "java/lang/Object", null);
        emitDefaultConstructor(writer);

        MethodVisitor method = writer.visitMethod(
                ACC_PUBLIC | ACC_STATIC,
                "lockedNested",
                "(Ljava/lang/Object;Ljava/lang/Object;)V",
                null,
                null);
        method.visitCode();
        method.visitVarInsn(ALOAD, 0);
        method.visitInsn(MONITORENTER);
        method.visitVarInsn(ALOAD, 1);
        method.visitInsn(MONITORENTER);
        method.visitVarInsn(ALOAD, 1);
        method.visitInsn(MONITOREXIT);
        method.visitVarInsn(ALOAD, 0);
        method.visitInsn(MONITOREXIT);
        method.visitInsn(RETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();

        writer.visitEnd();
        return writer.toByteArray();
    }

    public static byte[] classWithSynchronizedExceptionalUnlockShape(String internalName) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(V17, ACC_PUBLIC | ACC_SUPER, internalName, null, "java/lang/Object", null);
        emitDefaultConstructor(writer);

        MethodVisitor method = writer.visitMethod(
                ACC_PUBLIC | ACC_STATIC,
                "lockedExceptional",
                "(Ljava/lang/Object;)V",
                null,
                null);
        org.objectweb.asm.Label start = new org.objectweb.asm.Label();
        org.objectweb.asm.Label end = new org.objectweb.asm.Label();
        org.objectweb.asm.Label handler = new org.objectweb.asm.Label();
        method.visitTryCatchBlock(start, end, handler, null);
        method.visitCode();
        method.visitVarInsn(ALOAD, 0);
        method.visitInsn(MONITORENTER);
        method.visitLabel(start);
        method.visitInsn(ICONST_1);
        method.visitInsn(POP);
        method.visitLabel(end);
        method.visitVarInsn(ALOAD, 0);
        method.visitInsn(MONITOREXIT);
        method.visitInsn(RETURN);
        method.visitLabel(handler);
        method.visitVarInsn(ASTORE, 1);
        method.visitVarInsn(ALOAD, 0);
        method.visitInsn(MONITOREXIT);
        method.visitVarInsn(ALOAD, 1);
        method.visitInsn(ATHROW);
        method.visitMaxs(0, 0);
        method.visitEnd();

        writer.visitEnd();
        return writer.toByteArray();
    }

    public static byte[] classWithVolatileFieldMethods(String internalName) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(V17, ACC_PUBLIC | ACC_SUPER, internalName, null, "java/lang/Object", null);
        writer.visitField(ACC_PRIVATE | ACC_VOLATILE, "value", "I", null, null).visitEnd();
        emitDefaultConstructor(writer);

        MethodVisitor read = writer.visitMethod(ACC_PUBLIC, "read", "()I", null, null);
        read.visitCode();
        read.visitVarInsn(ALOAD, 0);
        read.visitFieldInsn(GETFIELD, internalName, "value", "I");
        read.visitInsn(IRETURN);
        read.visitMaxs(0, 0);
        read.visitEnd();

        MethodVisitor write = writer.visitMethod(ACC_PUBLIC, "write", "(I)V", null, null);
        write.visitCode();
        write.visitVarInsn(ALOAD, 0);
        write.visitVarInsn(ILOAD, 1);
        write.visitFieldInsn(PUTFIELD, internalName, "value", "I");
        write.visitInsn(RETURN);
        write.visitMaxs(0, 0);
        write.visitEnd();

        writer.visitEnd();
        return writer.toByteArray();
    }

    public static byte[] classWithFinalFieldConstructor(String internalName) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(V17, ACC_PUBLIC | ACC_SUPER, internalName, null, "java/lang/Object", null);
        writer.visitField(ACC_PRIVATE | ACC_FINAL, "value", "I", null, null).visitEnd();

        MethodVisitor init = writer.visitMethod(ACC_PUBLIC, "<init>", "(I)V", null, null);
        init.visitCode();
        init.visitVarInsn(ALOAD, 0);
        init.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitVarInsn(ALOAD, 0);
        init.visitVarInsn(ILOAD, 1);
        init.visitFieldInsn(PUTFIELD, internalName, "value", "I");
        init.visitInsn(RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();

        writer.visitEnd();
        return writer.toByteArray();
    }

    public static byte[] classWithThreadStartJoinMethod(String internalName) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(V17, ACC_PUBLIC | ACC_SUPER, internalName, null, "java/lang/Object", null);
        emitDefaultConstructor(writer);

        MethodVisitor method = writer.visitMethod(
                ACC_PUBLIC | ACC_STATIC,
                "runThread",
                "(Ljava/lang/Thread;)V",
                null,
                new String[] {"java/lang/InterruptedException"});
        method.visitCode();
        method.visitVarInsn(ALOAD, 0);
        method.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Thread", "start", "()V", false);
        method.visitVarInsn(ALOAD, 0);
        method.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Thread", "join", "()V", false);
        method.visitInsn(RETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();

        writer.visitEnd();
        return writer.toByteArray();
    }

    public static byte[] classWithJdkStringMethods(String internalName) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(V17, ACC_PUBLIC | ACC_SUPER, internalName, null, "java/lang/Object", null);
        emitDefaultConstructor(writer);

        MethodVisitor method = writer.visitMethod(
                ACC_PUBLIC | ACC_STATIC,
                "stringOps",
                "(Ljava/lang/String;Ljava/lang/Object;)I",
                null,
                null);
        method.visitCode();
        method.visitVarInsn(ALOAD, 0);
        method.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "length", "()I", false);
        method.visitVarInsn(ALOAD, 0);
        method.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "isEmpty", "()Z", false);
        method.visitInsn(IADD);
        method.visitVarInsn(ALOAD, 0);
        method.visitInsn(ICONST_0);
        method.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
        method.visitInsn(IADD);
        method.visitVarInsn(ALOAD, 0);
        method.visitVarInsn(ALOAD, 1);
        method.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "equals", "(Ljava/lang/Object;)Z", false);
        method.visitInsn(IADD);
        method.visitInsn(IRETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();

        writer.visitEnd();
        return writer.toByteArray();
    }

    public static byte[] classWithJdkStringBuilderMethods(String internalName) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(V17, ACC_PUBLIC | ACC_SUPER, internalName, null, "java/lang/Object", null);
        emitDefaultConstructor(writer);

        MethodVisitor method = writer.visitMethod(
                ACC_PUBLIC | ACC_STATIC,
                "build",
                "(Ljava/lang/String;I)Ljava/lang/String;",
                null,
                null);
        method.visitCode();
        method.visitTypeInsn(NEW, "java/lang/StringBuilder");
        method.visitInsn(DUP);
        method.visitMethodInsn(INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "()V", false);
        method.visitVarInsn(ALOAD, 0);
        method.visitMethodInsn(
                INVOKEVIRTUAL,
                "java/lang/StringBuilder",
                "append",
                "(Ljava/lang/String;)Ljava/lang/StringBuilder;",
                false);
        method.visitVarInsn(ILOAD, 1);
        method.visitMethodInsn(
                INVOKEVIRTUAL,
                "java/lang/StringBuilder",
                "append",
                "(I)Ljava/lang/StringBuilder;",
                false);
        method.visitMethodInsn(INVOKEVIRTUAL, "java/lang/StringBuilder", "toString", "()Ljava/lang/String;", false);
        method.visitInsn(ARETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();

        writer.visitEnd();
        return writer.toByteArray();
    }

    public static byte[] classWithJdkSystemArraycopy(String internalName) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(V17, ACC_PUBLIC | ACC_SUPER, internalName, null, "java/lang/Object", null);
        emitDefaultConstructor(writer);

        MethodVisitor method = writer.visitMethod(
                ACC_PUBLIC | ACC_STATIC,
                "copy",
                "(Ljava/lang/Object;Ljava/lang/Object;)V",
                null,
                null);
        method.visitCode();
        method.visitVarInsn(ALOAD, 0);
        method.visitInsn(ICONST_0);
        method.visitVarInsn(ALOAD, 1);
        method.visitInsn(ICONST_0);
        method.visitInsn(ICONST_1);
        method.visitMethodInsn(
                INVOKESTATIC,
                "java/lang/System",
                "arraycopy",
                "(Ljava/lang/Object;ILjava/lang/Object;II)V",
                false);
        method.visitInsn(RETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();

        writer.visitEnd();
        return writer.toByteArray();
    }

    public static byte[] classWithJdkMathAndBoxing(String internalName) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(V17, ACC_PUBLIC | ACC_SUPER, internalName, null, "java/lang/Object", null);
        emitDefaultConstructor(writer);

        MethodVisitor math = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "math", "(II)I", null, null);
        math.visitCode();
        math.visitVarInsn(ILOAD, 0);
        math.visitMethodInsn(INVOKESTATIC, "java/lang/Math", "abs", "(I)I", false);
        math.visitVarInsn(ILOAD, 1);
        math.visitMethodInsn(INVOKESTATIC, "java/lang/Math", "max", "(II)I", false);
        math.visitInsn(IRETURN);
        math.visitMaxs(0, 0);
        math.visitEnd();

        MethodVisitor box = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "boxInt", "(I)Ljava/lang/Integer;", null, null);
        box.visitCode();
        box.visitVarInsn(ILOAD, 0);
        box.visitMethodInsn(INVOKESTATIC, "java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;", false);
        box.visitInsn(ARETURN);
        box.visitMaxs(0, 0);
        box.visitEnd();

        MethodVisitor unbox = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "unboxLong", "(Ljava/lang/Long;)J", null, null);
        unbox.visitCode();
        unbox.visitVarInsn(ALOAD, 0);
        unbox.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Long", "longValue", "()J", false);
        unbox.visitInsn(LRETURN);
        unbox.visitMaxs(0, 0);
        unbox.visitEnd();

        writer.visitEnd();
        return writer.toByteArray();
    }

    public static byte[] classWithUnsupportedJdkStringCall(String internalName) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(V17, ACC_PUBLIC | ACC_SUPER, internalName, null, "java/lang/Object", null);
        emitDefaultConstructor(writer);

        MethodVisitor method = writer.visitMethod(
                ACC_PUBLIC | ACC_STATIC,
                "substring",
                "(Ljava/lang/String;)Ljava/lang/String;",
                null,
                null);
        method.visitCode();
        method.visitVarInsn(ALOAD, 0);
        method.visitInsn(ICONST_1);
        method.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "substring", "(I)Ljava/lang/String;", false);
        method.visitInsn(ARETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();

        writer.visitEnd();
        return writer.toByteArray();
    }

    public static byte[] classWithVirtualCall(String internalName, String receiverType) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(V17, ACC_PUBLIC | ACC_SUPER, internalName, null, "java/lang/Object", null);
        MethodVisitor method = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "call", "(L" + receiverType + ";)V", null, null);
        method.visitCode();
        method.visitVarInsn(ALOAD, 0);
        method.visitMethodInsn(INVOKEVIRTUAL, receiverType, "run", "()V", false);
        method.visitInsn(RETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    public static byte[] classWithAllocation(String internalName, String allocatedType) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(V17, ACC_PUBLIC | ACC_SUPER, internalName, null, "java/lang/Object", null);
        MethodVisitor method = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "make", "()V", null, null);
        method.visitCode();
        method.visitTypeInsn(NEW, allocatedType);
        method.visitInsn(DUP);
        method.visitMethodInsn(INVOKESPECIAL, allocatedType, "<init>", "()V", false);
        method.visitInsn(POP);
        method.visitInsn(RETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    public static byte[] classWithReferenceArrayAllocation(String internalName, String elementType) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(V17, ACC_PUBLIC | ACC_SUPER, internalName, null, "java/lang/Object", null);
        MethodVisitor method = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "array", "()V", null, null);
        method.visitCode();
        method.visitInsn(ICONST_1);
        method.visitTypeInsn(ANEWARRAY, elementType);
        method.visitInsn(POP);
        method.visitInsn(RETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    public static byte[] classWithArrayOperationMethods(String internalName) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(V17, ACC_PUBLIC | ACC_SUPER, internalName, null, "java/lang/Object", null);

        MethodVisitor primitiveArray = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "makeInts", "(I)[I", null, null);
        primitiveArray.visitCode();
        primitiveArray.visitVarInsn(ILOAD, 0);
        primitiveArray.visitIntInsn(NEWARRAY, T_INT);
        primitiveArray.visitInsn(ARETURN);
        primitiveArray.visitMaxs(0, 0);
        primitiveArray.visitEnd();

        MethodVisitor firstPlusLength = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "firstPlusLength", "([I)I", null, null);
        firstPlusLength.visitCode();
        firstPlusLength.visitVarInsn(ALOAD, 0);
        firstPlusLength.visitInsn(ARRAYLENGTH);
        firstPlusLength.visitVarInsn(ALOAD, 0);
        firstPlusLength.visitInsn(ICONST_0);
        firstPlusLength.visitInsn(IALOAD);
        firstPlusLength.visitInsn(IADD);
        firstPlusLength.visitInsn(IRETURN);
        firstPlusLength.visitMaxs(0, 0);
        firstPlusLength.visitEnd();

        MethodVisitor putRef = writer.visitMethod(
                ACC_PUBLIC | ACC_STATIC,
                "putRef",
                "([Ljava/lang/Object;Ljava/lang/Object;)V",
                null,
                null);
        putRef.visitCode();
        putRef.visitVarInsn(ALOAD, 0);
        putRef.visitInsn(ICONST_0);
        putRef.visitVarInsn(ALOAD, 1);
        putRef.visitInsn(AASTORE);
        putRef.visitInsn(RETURN);
        putRef.visitMaxs(0, 0);
        putRef.visitEnd();

        MethodVisitor multi = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "multi", "(II)[[I", null, null);
        multi.visitCode();
        multi.visitVarInsn(ILOAD, 0);
        multi.visitVarInsn(ILOAD, 1);
        multi.visitMultiANewArrayInsn("[[I", 2);
        multi.visitInsn(ARETURN);
        multi.visitMaxs(0, 0);
        multi.visitEnd();

        writer.visitEnd();
        return writer.toByteArray();
    }

    public static byte[] classWithTypeOperationMethods(String internalName) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(V17, ACC_PUBLIC | ACC_SUPER, internalName, null, "java/lang/Object", null);

        MethodVisitor cast = writer.visitMethod(
                ACC_PUBLIC | ACC_STATIC,
                "castString",
                "(Ljava/lang/Object;)Ljava/lang/String;",
                null,
                null);
        cast.visitCode();
        cast.visitVarInsn(ALOAD, 0);
        cast.visitTypeInsn(CHECKCAST, "java/lang/String");
        cast.visitInsn(ARETURN);
        cast.visitMaxs(0, 0);
        cast.visitEnd();

        MethodVisitor instanceOf = writer.visitMethod(
                ACC_PUBLIC | ACC_STATIC,
                "isString",
                "(Ljava/lang/Object;)I",
                null,
                null);
        instanceOf.visitCode();
        instanceOf.visitVarInsn(ALOAD, 0);
        instanceOf.visitTypeInsn(INSTANCEOF, "java/lang/String");
        instanceOf.visitInsn(IRETURN);
        instanceOf.visitMaxs(0, 0);
        instanceOf.visitEnd();

        writer.visitEnd();
        return writer.toByteArray();
    }

    public static byte[] classWithInterfaceCall(String internalName, String interfaceType) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(V17, ACC_PUBLIC | ACC_SUPER, internalName, null, "java/lang/Object", null);
        MethodVisitor method = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "call", "(L" + interfaceType + ";)V", null, null);
        method.visitCode();
        method.visitVarInsn(ALOAD, 0);
        method.visitMethodInsn(INVOKEINTERFACE, interfaceType, "run", "()V", true);
        method.visitInsn(RETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    public static byte[] classWithInvokeDynamic(String internalName) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(V17, ACC_PUBLIC | ACC_SUPER, internalName, null, "java/lang/Object", null);
        MethodVisitor method = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "dynamic", "()I", null, null);
        method.visitCode();
        method.visitInvokeDynamicInsn(
                "value",
                "()I",
                new Handle(
                        H_INVOKESTATIC,
                        "java/lang/invoke/ConstantBootstraps",
                        "nullConstant",
                        "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/Class;)Ljava/lang/Object;",
                        false));
        method.visitInsn(IRETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    public static byte[] classWithStringConcatMakeConcat(String internalName) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(V17, ACC_PUBLIC | ACC_SUPER, internalName, null, "java/lang/Object", null);
        MethodVisitor method = writer.visitMethod(
                ACC_PUBLIC | ACC_STATIC,
                "concat",
                "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;",
                null,
                null);
        method.visitCode();
        method.visitVarInsn(ALOAD, 0);
        method.visitVarInsn(ALOAD, 1);
        method.visitInvokeDynamicInsn(
                "makeConcat",
                "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;",
                stringConcatFactoryBootstrap("makeConcat"));
        method.visitInsn(ARETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    public static byte[] classWithStringConcatWithConstants(String internalName) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(V17, ACC_PUBLIC | ACC_SUPER, internalName, null, "java/lang/Object", null);
        MethodVisitor method = writer.visitMethod(
                ACC_PUBLIC | ACC_STATIC,
                "concatRecipe",
                "(Ljava/lang/String;I)Ljava/lang/String;",
                null,
                null);
        method.visitCode();
        method.visitVarInsn(ALOAD, 0);
        method.visitVarInsn(ILOAD, 1);
        method.visitInvokeDynamicInsn(
                "makeConcatWithConstants",
                "(Ljava/lang/String;I)Ljava/lang/String;",
                stringConcatFactoryBootstrap("makeConcatWithConstants"),
                "value=\u0001:\u0001");
        method.visitInsn(ARETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    public static byte[] classWithObjectStringConcat(String internalName) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(V17, ACC_PUBLIC | ACC_SUPER, internalName, null, "java/lang/Object", null);
        MethodVisitor method = writer.visitMethod(
                ACC_PUBLIC | ACC_STATIC,
                "concatObject",
                "(Ljava/lang/Object;)Ljava/lang/String;",
                null,
                null);
        method.visitCode();
        method.visitVarInsn(ALOAD, 0);
        method.visitInvokeDynamicInsn(
                "makeConcat",
                "(Ljava/lang/Object;)Ljava/lang/String;",
                stringConcatFactoryBootstrap("makeConcat"));
        method.visitInsn(ARETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    public static byte[] classWithUnsupportedStringConcatRecipe(String internalName) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(V17, ACC_PUBLIC | ACC_SUPER, internalName, null, "java/lang/Object", null);
        MethodVisitor method = writer.visitMethod(
                ACC_PUBLIC | ACC_STATIC,
                "concatUnsupported",
                "()Ljava/lang/String;",
                null,
                null);
        method.visitCode();
        method.visitInvokeDynamicInsn(
                "makeConcatWithConstants",
                "()Ljava/lang/String;",
                stringConcatFactoryBootstrap("makeConcatWithConstants"),
                "\u0002",
                Type.getType("Ljava/lang/String;"));
        method.visitInsn(ARETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static Handle stringConcatFactoryBootstrap(String name) {
        String descriptor = name.equals("makeConcat")
                ? "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;"
                : "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/invoke/CallSite;";
        return new Handle(
                H_INVOKESTATIC,
                "java/lang/invoke/StringConcatFactory",
                name,
                descriptor,
                false);
    }

    public static byte[] classWithLambdaMetafactoryMethods(String internalName) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(V17, ACC_PUBLIC | ACC_SUPER, internalName, null, "java/lang/Object", null);

        MethodVisitor targetRun = writer.visitMethod(ACC_PRIVATE | ACC_STATIC, "targetRun", "()V", null, null);
        targetRun.visitCode();
        targetRun.visitInsn(RETURN);
        targetRun.visitMaxs(0, 0);
        targetRun.visitEnd();

        MethodVisitor capturedTarget = writer.visitMethod(
                ACC_PRIVATE | ACC_STATIC,
                "captured",
                "(Ljava/lang/String;)Ljava/lang/String;",
                null,
                null);
        capturedTarget.visitCode();
        capturedTarget.visitVarInsn(ALOAD, 0);
        capturedTarget.visitInsn(ARETURN);
        capturedTarget.visitMaxs(0, 0);
        capturedTarget.visitEnd();

        MethodVisitor makeTarget = writer.visitMethod(
                ACC_PRIVATE | ACC_STATIC,
                "makeString",
                "()Ljava/lang/String;",
                null,
                null);
        makeTarget.visitCode();
        makeTarget.visitLdcInsn("value");
        makeTarget.visitInsn(ARETURN);
        makeTarget.visitMaxs(0, 0);
        makeTarget.visitEnd();

        MethodVisitor nonCapturing = writer.visitMethod(
                ACC_PUBLIC | ACC_STATIC,
                "nonCapturing",
                "()Ljava/lang/Runnable;",
                null,
                null);
        nonCapturing.visitCode();
        nonCapturing.visitInvokeDynamicInsn(
                "run",
                "()Ljava/lang/Runnable;",
                lambdaMetafactoryBootstrap("metafactory"),
                Type.getMethodType("()V"),
                new Handle(H_INVOKESTATIC, internalName, "targetRun", "()V", false),
                Type.getMethodType("()V"));
        nonCapturing.visitInsn(ARETURN);
        nonCapturing.visitMaxs(0, 0);
        nonCapturing.visitEnd();

        MethodVisitor capturing = writer.visitMethod(
                ACC_PUBLIC | ACC_STATIC,
                "capturing",
                "(Ljava/lang/String;)Ljava/util/function/Supplier;",
                null,
                null);
        capturing.visitCode();
        capturing.visitVarInsn(ALOAD, 0);
        capturing.visitInvokeDynamicInsn(
                "get",
                "(Ljava/lang/String;)Ljava/util/function/Supplier;",
                lambdaMetafactoryBootstrap("metafactory"),
                Type.getMethodType("()Ljava/lang/Object;"),
                new Handle(H_INVOKESTATIC, internalName, "captured", "(Ljava/lang/String;)Ljava/lang/String;", false),
                Type.getMethodType("()Ljava/lang/String;"));
        capturing.visitInsn(ARETURN);
        capturing.visitMaxs(0, 0);
        capturing.visitEnd();

        MethodVisitor staticRef = writer.visitMethod(
                ACC_PUBLIC | ACC_STATIC,
                "staticReference",
                "()Ljava/util/function/Supplier;",
                null,
                null);
        staticRef.visitCode();
        staticRef.visitInvokeDynamicInsn(
                "get",
                "()Ljava/util/function/Supplier;",
                lambdaMetafactoryBootstrap("metafactory"),
                Type.getMethodType("()Ljava/lang/Object;"),
                new Handle(H_INVOKESTATIC, internalName, "makeString", "()Ljava/lang/String;", false),
                Type.getMethodType("()Ljava/lang/String;"));
        staticRef.visitInsn(ARETURN);
        staticRef.visitMaxs(0, 0);
        staticRef.visitEnd();

        MethodVisitor instanceRef = writer.visitMethod(
                ACC_PUBLIC | ACC_STATIC,
                "instanceReference",
                "(Ljava/lang/String;)Ljava/util/function/Supplier;",
                null,
                null);
        instanceRef.visitCode();
        instanceRef.visitVarInsn(ALOAD, 0);
        instanceRef.visitInvokeDynamicInsn(
                "get",
                "(Ljava/lang/String;)Ljava/util/function/Supplier;",
                lambdaMetafactoryBootstrap("metafactory"),
                Type.getMethodType("()Ljava/lang/Object;"),
                new Handle(H_INVOKEVIRTUAL, "java/lang/String", "trim", "()Ljava/lang/String;", false),
                Type.getMethodType("()Ljava/lang/String;"));
        instanceRef.visitInsn(ARETURN);
        instanceRef.visitMaxs(0, 0);
        instanceRef.visitEnd();

        MethodVisitor constructorRef = writer.visitMethod(
                ACC_PUBLIC | ACC_STATIC,
                "constructorReference",
                "()Ljava/util/function/Supplier;",
                null,
                null);
        constructorRef.visitCode();
        constructorRef.visitInvokeDynamicInsn(
                "get",
                "()Ljava/util/function/Supplier;",
                lambdaMetafactoryBootstrap("metafactory"),
                Type.getMethodType("()Ljava/lang/Object;"),
                new Handle(H_NEWINVOKESPECIAL, "java/lang/Object", "<init>", "()V", false),
                Type.getMethodType("()Ljava/lang/Object;"));
        constructorRef.visitInsn(ARETURN);
        constructorRef.visitMaxs(0, 0);
        constructorRef.visitEnd();

        MethodVisitor unsupported = writer.visitMethod(
                ACC_PUBLIC | ACC_STATIC,
                "alt",
                "()Ljava/lang/Runnable;",
                null,
                null);
        unsupported.visitCode();
        unsupported.visitInvokeDynamicInsn(
                "run",
                "()Ljava/lang/Runnable;",
                lambdaMetafactoryBootstrap("altMetafactory"),
                Type.getMethodType("()V"),
                new Handle(H_INVOKESTATIC, internalName, "targetRun", "()V", false),
                Type.getMethodType("()V"),
                8);
        unsupported.visitInsn(ARETURN);
        unsupported.visitMaxs(0, 0);
        unsupported.visitEnd();

        writer.visitEnd();
        return writer.toByteArray();
    }

    public static byte[] classWithAltMetafactoryLambda(String internalName) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(V17, ACC_PUBLIC | ACC_SUPER, internalName, null, "java/lang/Object", null);

        MethodVisitor targetRun = writer.visitMethod(ACC_PRIVATE | ACC_STATIC, "targetRun", "()V", null, null);
        targetRun.visitCode();
        targetRun.visitInsn(RETURN);
        targetRun.visitMaxs(0, 0);
        targetRun.visitEnd();

        MethodVisitor alt = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "altCommon", "()Ljava/lang/Runnable;", null, null);
        alt.visitCode();
        alt.visitInvokeDynamicInsn(
                "run",
                "()Ljava/lang/Runnable;",
                lambdaMetafactoryBootstrap("altMetafactory"),
                Type.getMethodType("()V"),
                new Handle(H_INVOKESTATIC, internalName, "targetRun", "()V", false),
                Type.getMethodType("()V"),
                1 | 2 | 4,
                1,
                Type.getObjectType("java/io/Serializable"),
                1,
                Type.getMethodType("()V"));
        alt.visitInsn(ARETURN);
        alt.visitMaxs(0, 0);
        alt.visitEnd();

        writer.visitEnd();
        return writer.toByteArray();
    }

    public static byte[] classWithMethodHandleInvokeExact(String internalName) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(V17, ACC_PUBLIC | ACC_SUPER, internalName, null, "java/lang/Object", null);

        MethodVisitor target = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "target", "()I", null, null);
        target.visitCode();
        target.visitIntInsn(BIPUSH, 9);
        target.visitInsn(IRETURN);
        target.visitMaxs(0, 0);
        target.visitEnd();

        MethodVisitor direct = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "direct", "()I", null, null);
        direct.visitCode();
        direct.visitLdcInsn(new Handle(H_INVOKESTATIC, internalName, "target", "()I", false));
        direct.visitMethodInsn(INVOKEVIRTUAL, "java/lang/invoke/MethodHandle", "invokeExact", "()I", false);
        direct.visitInsn(IRETURN);
        direct.visitMaxs(0, 0);
        direct.visitEnd();

        MethodVisitor dynamic = writer.visitMethod(
                ACC_PUBLIC | ACC_STATIC,
                "dynamic",
                "(Ljava/lang/invoke/MethodHandle;)I",
                null,
                null);
        dynamic.visitCode();
        dynamic.visitVarInsn(ALOAD, 0);
        dynamic.visitMethodInsn(INVOKEVIRTUAL, "java/lang/invoke/MethodHandle", "invokeExact", "()I", false);
        dynamic.visitInsn(IRETURN);
        dynamic.visitMaxs(0, 0);
        dynamic.visitEnd();

        writer.visitEnd();
        return writer.toByteArray();
    }

    public static byte[] classWithConstantDynamicMethods(String internalName) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(V17, ACC_PUBLIC | ACC_SUPER, internalName, null, "java/lang/Object", null);

        Handle nullConstantBootstrap = new Handle(
                H_INVOKESTATIC,
                "java/lang/invoke/ConstantBootstraps",
                "nullConstant",
                "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/Class;)Ljava/lang/Object;",
                false);
        MethodVisitor supported = writer.visitMethod(
                ACC_PUBLIC | ACC_STATIC,
                "supported",
                "()Ljava/lang/Object;",
                null,
                null);
        supported.visitCode();
        supported.visitLdcInsn(new ConstantDynamic(
                "NULL_VALUE",
                "Ljava/lang/Object;",
                nullConstantBootstrap));
        supported.visitInsn(ARETURN);
        supported.visitMaxs(0, 0);
        supported.visitEnd();

        Handle unsupportedBootstrap = new Handle(
                H_INVOKESTATIC,
                "pkg/Bootstrap",
                "dynamic",
                "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/Class;)Ljava/lang/Object;",
                false);
        MethodVisitor unsupported = writer.visitMethod(
                ACC_PUBLIC | ACC_STATIC,
                "unsupported",
                "()Ljava/lang/Object;",
                null,
                null);
        unsupported.visitCode();
        unsupported.visitLdcInsn(new ConstantDynamic(
                "DYNAMIC_VALUE",
                "Ljava/lang/Object;",
                unsupportedBootstrap));
        unsupported.visitInsn(ARETURN);
        unsupported.visitMaxs(0, 0);
        unsupported.visitEnd();

        writer.visitEnd();
        return writer.toByteArray();
    }

    public static byte[] classWithUnsafeMethods(String internalName) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(V17, ACC_PUBLIC | ACC_SUPER, internalName, null, "java/lang/Object", null);

        MethodVisitor objectOffset = writer.visitMethod(
                ACC_PUBLIC | ACC_STATIC,
                "objectOffset",
                "(Lsun/misc/Unsafe;Ljava/lang/reflect/Field;)J",
                null,
                null);
        objectOffset.visitCode();
        objectOffset.visitVarInsn(ALOAD, 0);
        objectOffset.visitVarInsn(ALOAD, 1);
        objectOffset.visitMethodInsn(INVOKEVIRTUAL, "sun/misc/Unsafe", "objectFieldOffset", "(Ljava/lang/reflect/Field;)J", false);
        objectOffset.visitInsn(LRETURN);
        objectOffset.visitMaxs(0, 0);
        objectOffset.visitEnd();

        MethodVisitor staticOffset = writer.visitMethod(
                ACC_PUBLIC | ACC_STATIC,
                "staticOffset",
                "(Lsun/misc/Unsafe;Ljava/lang/reflect/Field;)J",
                null,
                null);
        staticOffset.visitCode();
        staticOffset.visitVarInsn(ALOAD, 0);
        staticOffset.visitVarInsn(ALOAD, 1);
        staticOffset.visitMethodInsn(INVOKEVIRTUAL, "sun/misc/Unsafe", "staticFieldOffset", "(Ljava/lang/reflect/Field;)J", false);
        staticOffset.visitInsn(LRETURN);
        staticOffset.visitMaxs(0, 0);
        staticOffset.visitEnd();

        MethodVisitor arrayBase = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "arrayBase", "(Lsun/misc/Unsafe;)I", null, null);
        arrayBase.visitCode();
        arrayBase.visitVarInsn(ALOAD, 0);
        arrayBase.visitLdcInsn(Type.getType("[I"));
        arrayBase.visitMethodInsn(INVOKEVIRTUAL, "sun/misc/Unsafe", "arrayBaseOffset", "(Ljava/lang/Class;)I", false);
        arrayBase.visitInsn(IRETURN);
        arrayBase.visitMaxs(0, 0);
        arrayBase.visitEnd();

        MethodVisitor arrayScale = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "arrayScale", "(Lsun/misc/Unsafe;)I", null, null);
        arrayScale.visitCode();
        arrayScale.visitVarInsn(ALOAD, 0);
        arrayScale.visitLdcInsn(Type.getType("[I"));
        arrayScale.visitMethodInsn(INVOKEVIRTUAL, "sun/misc/Unsafe", "arrayIndexScale", "(Ljava/lang/Class;)I", false);
        arrayScale.visitInsn(IRETURN);
        arrayScale.visitMaxs(0, 0);
        arrayScale.visitEnd();

        MethodVisitor getInt = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "getInt", "(Lsun/misc/Unsafe;Ljava/lang/Object;J)I", null, null);
        getInt.visitCode();
        getInt.visitVarInsn(ALOAD, 0);
        getInt.visitVarInsn(ALOAD, 1);
        getInt.visitVarInsn(LLOAD, 2);
        getInt.visitMethodInsn(INVOKEVIRTUAL, "sun/misc/Unsafe", "getInt", "(Ljava/lang/Object;J)I", false);
        getInt.visitInsn(IRETURN);
        getInt.visitMaxs(0, 0);
        getInt.visitEnd();

        MethodVisitor putObject = writer.visitMethod(
                ACC_PUBLIC | ACC_STATIC,
                "putObject",
                "(Lsun/misc/Unsafe;Ljava/lang/Object;JLjava/lang/Object;)V",
                null,
                null);
        putObject.visitCode();
        putObject.visitVarInsn(ALOAD, 0);
        putObject.visitVarInsn(ALOAD, 1);
        putObject.visitVarInsn(LLOAD, 2);
        putObject.visitVarInsn(ALOAD, 4);
        putObject.visitMethodInsn(INVOKEVIRTUAL, "sun/misc/Unsafe", "putObject", "(Ljava/lang/Object;JLjava/lang/Object;)V", false);
        putObject.visitInsn(RETURN);
        putObject.visitMaxs(0, 0);
        putObject.visitEnd();

        MethodVisitor getVolatile = writer.visitMethod(
                ACC_PUBLIC | ACC_STATIC,
                "getVolatile",
                "(Lsun/misc/Unsafe;Ljava/lang/Object;J)I",
                null,
                null);
        getVolatile.visitCode();
        getVolatile.visitVarInsn(ALOAD, 0);
        getVolatile.visitVarInsn(ALOAD, 1);
        getVolatile.visitVarInsn(LLOAD, 2);
        getVolatile.visitMethodInsn(INVOKEVIRTUAL, "sun/misc/Unsafe", "getIntVolatile", "(Ljava/lang/Object;J)I", false);
        getVolatile.visitInsn(IRETURN);
        getVolatile.visitMaxs(0, 0);
        getVolatile.visitEnd();

        MethodVisitor cas = writer.visitMethod(
                ACC_PUBLIC | ACC_STATIC,
                "cas",
                "(Lsun/misc/Unsafe;Ljava/lang/Object;JII)Z",
                null,
                null);
        cas.visitCode();
        cas.visitVarInsn(ALOAD, 0);
        cas.visitVarInsn(ALOAD, 1);
        cas.visitVarInsn(LLOAD, 2);
        cas.visitVarInsn(ILOAD, 4);
        cas.visitVarInsn(ILOAD, 5);
        cas.visitMethodInsn(INVOKEVIRTUAL, "sun/misc/Unsafe", "compareAndSwapInt", "(Ljava/lang/Object;JII)Z", false);
        cas.visitInsn(IRETURN);
        cas.visitMaxs(0, 0);
        cas.visitEnd();

        MethodVisitor allocate = writer.visitMethod(
                ACC_PUBLIC | ACC_STATIC,
                "allocate",
                "(Lsun/misc/Unsafe;Ljava/lang/Class;)Ljava/lang/Object;",
                null,
                null);
        allocate.visitCode();
        allocate.visitVarInsn(ALOAD, 0);
        allocate.visitVarInsn(ALOAD, 1);
        allocate.visitMethodInsn(INVOKEVIRTUAL, "sun/misc/Unsafe", "allocateInstance", "(Ljava/lang/Class;)Ljava/lang/Object;", false);
        allocate.visitInsn(ARETURN);
        allocate.visitMaxs(0, 0);
        allocate.visitEnd();

        MethodVisitor unsupported = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "unsupported", "(Lsun/misc/Unsafe;J)B", null, null);
        unsupported.visitCode();
        unsupported.visitVarInsn(ALOAD, 0);
        unsupported.visitVarInsn(LLOAD, 1);
        unsupported.visitMethodInsn(INVOKEVIRTUAL, "sun/misc/Unsafe", "getByte", "(J)B", false);
        unsupported.visitInsn(IRETURN);
        unsupported.visitMaxs(0, 0);
        unsupported.visitEnd();

        writer.visitEnd();
        return writer.toByteArray();
    }

    public static byte[] classWithVarHandleMethods(String internalName) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(V17, ACC_PUBLIC | ACC_SUPER, internalName, null, "java/lang/Object", null);

        MethodVisitor get = writer.visitMethod(
                ACC_PUBLIC | ACC_STATIC,
                "get",
                "(Ljava/lang/invoke/VarHandle;Ljava/lang/Object;)Ljava/lang/Object;",
                null,
                null);
        get.visitCode();
        get.visitVarInsn(ALOAD, 0);
        get.visitVarInsn(ALOAD, 1);
        get.visitMethodInsn(INVOKEVIRTUAL, "java/lang/invoke/VarHandle", "get", "(Ljava/lang/Object;)Ljava/lang/Object;", false);
        get.visitInsn(ARETURN);
        get.visitMaxs(0, 0);
        get.visitEnd();

        MethodVisitor set = writer.visitMethod(
                ACC_PUBLIC | ACC_STATIC,
                "set",
                "(Ljava/lang/invoke/VarHandle;Ljava/lang/Object;Ljava/lang/Object;)V",
                null,
                null);
        set.visitCode();
        set.visitVarInsn(ALOAD, 0);
        set.visitVarInsn(ALOAD, 1);
        set.visitVarInsn(ALOAD, 2);
        set.visitMethodInsn(INVOKEVIRTUAL, "java/lang/invoke/VarHandle", "set", "(Ljava/lang/Object;Ljava/lang/Object;)V", false);
        set.visitInsn(RETURN);
        set.visitMaxs(0, 0);
        set.visitEnd();

        MethodVisitor getVolatile = writer.visitMethod(
                ACC_PUBLIC | ACC_STATIC,
                "getVolatile",
                "(Ljava/lang/invoke/VarHandle;Ljava/lang/Object;)Ljava/lang/Object;",
                null,
                null);
        getVolatile.visitCode();
        getVolatile.visitVarInsn(ALOAD, 0);
        getVolatile.visitVarInsn(ALOAD, 1);
        getVolatile.visitMethodInsn(INVOKEVIRTUAL, "java/lang/invoke/VarHandle", "getVolatile", "(Ljava/lang/Object;)Ljava/lang/Object;", false);
        getVolatile.visitInsn(ARETURN);
        getVolatile.visitMaxs(0, 0);
        getVolatile.visitEnd();

        MethodVisitor compareAndSet = writer.visitMethod(
                ACC_PUBLIC | ACC_STATIC,
                "compareAndSet",
                "(Ljava/lang/invoke/VarHandle;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Z",
                null,
                null);
        compareAndSet.visitCode();
        compareAndSet.visitVarInsn(ALOAD, 0);
        compareAndSet.visitVarInsn(ALOAD, 1);
        compareAndSet.visitVarInsn(ALOAD, 2);
        compareAndSet.visitVarInsn(ALOAD, 3);
        compareAndSet.visitMethodInsn(
                INVOKEVIRTUAL,
                "java/lang/invoke/VarHandle",
                "compareAndSet",
                "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Z",
                false);
        compareAndSet.visitInsn(IRETURN);
        compareAndSet.visitMaxs(0, 0);
        compareAndSet.visitEnd();

        writer.visitEnd();
        return writer.toByteArray();
    }

    public static byte[] classWithTypedIntVarHandleMethods(String internalName, String targetInternalName) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(V17, ACC_PUBLIC | ACC_SUPER, internalName, null, "java/lang/Object", null);

        MethodVisitor get = writer.visitMethod(
                ACC_PUBLIC | ACC_STATIC,
                "getInt",
                "(Ljava/lang/invoke/VarHandle;L" + targetInternalName + ";)I",
                null,
                null);
        get.visitCode();
        get.visitVarInsn(ALOAD, 0);
        get.visitVarInsn(ALOAD, 1);
        get.visitMethodInsn(INVOKEVIRTUAL, "java/lang/invoke/VarHandle", "get", "(L" + targetInternalName + ";)I", false);
        get.visitInsn(IRETURN);
        get.visitMaxs(0, 0);
        get.visitEnd();

        MethodVisitor set = writer.visitMethod(
                ACC_PUBLIC | ACC_STATIC,
                "setInt",
                "(Ljava/lang/invoke/VarHandle;L" + targetInternalName + ";I)V",
                null,
                null);
        set.visitCode();
        set.visitVarInsn(ALOAD, 0);
        set.visitVarInsn(ALOAD, 1);
        set.visitVarInsn(ILOAD, 2);
        set.visitMethodInsn(INVOKEVIRTUAL, "java/lang/invoke/VarHandle", "set", "(L" + targetInternalName + ";I)V", false);
        set.visitInsn(RETURN);
        set.visitMaxs(0, 0);
        set.visitEnd();

        MethodVisitor getVolatile = writer.visitMethod(
                ACC_PUBLIC | ACC_STATIC,
                "getVolatileInt",
                "(Ljava/lang/invoke/VarHandle;L" + targetInternalName + ";)I",
                null,
                null);
        getVolatile.visitCode();
        getVolatile.visitVarInsn(ALOAD, 0);
        getVolatile.visitVarInsn(ALOAD, 1);
        getVolatile.visitMethodInsn(INVOKEVIRTUAL, "java/lang/invoke/VarHandle", "getVolatile", "(L" + targetInternalName + ";)I", false);
        getVolatile.visitInsn(IRETURN);
        getVolatile.visitMaxs(0, 0);
        getVolatile.visitEnd();

        MethodVisitor setVolatile = writer.visitMethod(
                ACC_PUBLIC | ACC_STATIC,
                "setVolatileInt",
                "(Ljava/lang/invoke/VarHandle;L" + targetInternalName + ";I)V",
                null,
                null);
        setVolatile.visitCode();
        setVolatile.visitVarInsn(ALOAD, 0);
        setVolatile.visitVarInsn(ALOAD, 1);
        setVolatile.visitVarInsn(ILOAD, 2);
        setVolatile.visitMethodInsn(INVOKEVIRTUAL, "java/lang/invoke/VarHandle", "setVolatile", "(L" + targetInternalName + ";I)V", false);
        setVolatile.visitInsn(RETURN);
        setVolatile.visitMaxs(0, 0);
        setVolatile.visitEnd();

        MethodVisitor compareAndSet = writer.visitMethod(
                ACC_PUBLIC | ACC_STATIC,
                "compareAndSetInt",
                "(Ljava/lang/invoke/VarHandle;L" + targetInternalName + ";II)Z",
                null,
                null);
        compareAndSet.visitCode();
        compareAndSet.visitVarInsn(ALOAD, 0);
        compareAndSet.visitVarInsn(ALOAD, 1);
        compareAndSet.visitVarInsn(ILOAD, 2);
        compareAndSet.visitVarInsn(ILOAD, 3);
        compareAndSet.visitMethodInsn(
                INVOKEVIRTUAL,
                "java/lang/invoke/VarHandle",
                "compareAndSet",
                "(L" + targetInternalName + ";II)Z",
                false);
        compareAndSet.visitInsn(IRETURN);
        compareAndSet.visitMaxs(0, 0);
        compareAndSet.visitEnd();

        writer.visitEnd();
        return writer.toByteArray();
    }

    private static Handle lambdaMetafactoryBootstrap(String name) {
        String descriptor = name.equals("metafactory")
                ? "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;"
                : "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;[Ljava/lang/Object;)Ljava/lang/invoke/CallSite;";
        return new Handle(
                H_INVOKESTATIC,
                "java/lang/invoke/LambdaMetafactory",
                name,
                descriptor,
                false);
    }

    public static byte[] classWithClassInitializer(String internalName) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(V17, ACC_PUBLIC | ACC_SUPER, internalName, null, "java/lang/Object", null);
        MethodVisitor clinit = writer.visitMethod(ACC_STATIC, "<clinit>", "()V", null, null);
        clinit.visitCode();
        clinit.visitInsn(RETURN);
        clinit.visitMaxs(0, 0);
        clinit.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    public static byte[] classWithSelfStaticWriteInClassInitializer(String internalName) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(V17, ACC_PUBLIC | ACC_SUPER, internalName, null, "java/lang/Object", null);
        writer.visitField(ACC_PUBLIC | ACC_STATIC, "VALUE", "I", null, null).visitEnd();
        MethodVisitor clinit = writer.visitMethod(ACC_STATIC, "<clinit>", "()V", null, null);
        clinit.visitCode();
        clinit.visitInsn(ICONST_1);
        clinit.visitFieldInsn(PUTSTATIC, internalName, "VALUE", "I");
        clinit.visitInsn(RETURN);
        clinit.visitMaxs(0, 0);
        clinit.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    public static byte[] classWithThrowingClassInitializer(String internalName) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(V17, ACC_PUBLIC | ACC_SUPER, internalName, null, "java/lang/Object", null);
        MethodVisitor clinit = writer.visitMethod(ACC_STATIC, "<clinit>", "()V", null, null);
        clinit.visitCode();
        clinit.visitTypeInsn(NEW, "java/lang/RuntimeException");
        clinit.visitInsn(DUP);
        clinit.visitMethodInsn(INVOKESPECIAL, "java/lang/RuntimeException", "<init>", "()V", false);
        clinit.visitInsn(ATHROW);
        clinit.visitMaxs(0, 0);
        clinit.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    public static byte[] interfaceWithAbstractAndDefault(String internalName) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(V17, ACC_PUBLIC | ACC_ABSTRACT | ACC_INTERFACE, internalName, null, "java/lang/Object", null);

        MethodVisitor abstractMethod = writer.visitMethod(ACC_PUBLIC | ACC_ABSTRACT, "call", "()V", null, null);
        abstractMethod.visitEnd();

        MethodVisitor defaultMethod = writer.visitMethod(ACC_PUBLIC, "answer", "()I", null, null);
        defaultMethod.visitCode();
        defaultMethod.visitIntInsn(BIPUSH, 7);
        defaultMethod.visitInsn(IRETURN);
        defaultMethod.visitMaxs(1, 1);
        defaultMethod.visitEnd();

        writer.visitEnd();
        return writer.toByteArray();
    }

    public static byte[] annotationClass(String internalName) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(
                V17,
                ACC_PUBLIC | ACC_ABSTRACT | ACC_INTERFACE | ACC_ANNOTATION,
                internalName,
                null,
                "java/lang/Object",
                new String[] {"java/lang/annotation/Annotation"});
        MethodVisitor value = writer.visitMethod(
                ACC_PUBLIC | ACC_ABSTRACT,
                "value",
                "()Ljava/lang/String;",
                null,
                null);
        value.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    public static byte[] classHeader(String internalName, String superName, String[] interfaces, int access) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(V17, access, internalName, null, superName, interfaces);
        writer.visitEnd();
        return writer.toByteArray();
    }

    public static byte[] interfaceHeader(String internalName, String[] interfaces) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(
                V17,
                ACC_PUBLIC | ACC_ABSTRACT | ACC_INTERFACE,
                internalName,
                null,
                "java/lang/Object",
                interfaces);
        writer.visitEnd();
        return writer.toByteArray();
    }

    public static byte[] classWithVoidMethod(
            String internalName,
            String superName,
            String[] interfaces,
            int classAccess,
            String methodName,
            int methodAccess) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(V17, classAccess, internalName, null, superName, interfaces);
        MethodVisitor method = writer.visitMethod(methodAccess, methodName, "()V", null, null);
        if ((methodAccess & (ACC_ABSTRACT | ACC_NATIVE)) == 0) {
            method.visitCode();
            method.visitInsn(RETURN);
            method.visitMaxs(0, 0);
        }
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    public static byte[] classWithMetadataOrdinary(String internalName) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(V17, ACC_PUBLIC | ACC_SUPER, internalName, null, "java/lang/Object", new String[] {"java/io/Serializable"});
        writer.visitField(ACC_PRIVATE, "value", "I", null, null).visitEnd();
        emitDefaultConstructor(writer);

        MethodVisitor method = writer.visitMethod(ACC_PUBLIC, "value", "()I", null, null);
        method.visitCode();
        method.visitVarInsn(ALOAD, 0);
        method.visitFieldInsn(GETFIELD, internalName, "value", "I");
        method.visitInsn(IRETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();

        writer.visitEnd();
        return writer.toByteArray();
    }

    public static byte[] classWithGenericSignature(String internalName) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(
                V17,
                ACC_PUBLIC | ACC_SUPER,
                internalName,
                "<T:Ljava/lang/Object;>Ljava/lang/Object;",
                "java/lang/Object",
                null);
        writer.visitField(ACC_PRIVATE, "value", "Ljava/lang/Object;", "TT;", null).visitEnd();

        MethodVisitor method = writer.visitMethod(
                ACC_PUBLIC | ACC_STATIC,
                "identity",
                "(Ljava/lang/Object;)Ljava/lang/Object;",
                "<T:Ljava/lang/Object;>(TT;)TT;",
                null);
        method.visitCode();
        method.visitVarInsn(ALOAD, 0);
        method.visitInsn(ARETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();

        writer.visitEnd();
        return writer.toByteArray();
    }

    public static byte[] classWithRuntimeAnnotations(String internalName) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(V17, ACC_PUBLIC | ACC_SUPER, internalName, null, "java/lang/Object", null);
        AnnotationVisitor visibleClass = writer.visitAnnotation("Ltest/Visible;", true);
        visibleClass.visit("value", "class");
        visibleClass.visitEnd();
        AnnotationVisitor invisibleClass = writer.visitAnnotation("Ltest/Invisible;", false);
        invisibleClass.visit("value", "hidden");
        invisibleClass.visitEnd();

        FieldVisitor field = writer.visitField(ACC_PUBLIC, "name", "Ljava/lang/String;", null, null);
        AnnotationVisitor fieldAnnotation = field.visitAnnotation("Ltest/FieldVisible;", true);
        fieldAnnotation.visit("value", "field");
        fieldAnnotation.visitEnd();
        field.visitEnd();

        MethodVisitor method = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "annotated", "()V", null, null);
        AnnotationVisitor methodAnnotation = method.visitAnnotation("Ltest/MethodInvisible;", false);
        methodAnnotation.visit("value", "method");
        methodAnnotation.visitEnd();
        method.visitCode();
        method.visitInsn(RETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();

        writer.visitEnd();
        return writer.toByteArray();
    }

    public static byte[] classWithInnerAndNestHost(String hostInternalName, String memberInternalName) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(V17, ACC_PUBLIC | ACC_SUPER, hostInternalName, null, "java/lang/Object", null);
        writer.visitNestMember(memberInternalName);
        writer.visitInnerClass(memberInternalName, hostInternalName, "Inner", ACC_PUBLIC | ACC_STATIC);
        emitDefaultConstructor(writer);
        writer.visitEnd();
        return writer.toByteArray();
    }

    public static byte[] classWithInnerAndNestMember(String memberInternalName, String hostInternalName) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(V17, ACC_PUBLIC | ACC_SUPER, memberInternalName, null, "java/lang/Object", null);
        writer.visitNestHost(hostInternalName);
        writer.visitOuterClass(hostInternalName, null, null);
        writer.visitInnerClass(memberInternalName, hostInternalName, "Inner", ACC_PUBLIC | ACC_STATIC);
        emitDefaultConstructor(writer);
        writer.visitEnd();
        return writer.toByteArray();
    }

    public static byte[] recordClassWithMetadata(String internalName) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(V17, ACC_PUBLIC | ACC_FINAL | ACC_SUPER | ACC_RECORD, internalName, null, "java/lang/Record", null);
        RecordComponentVisitor component = writer.visitRecordComponent("name", "Ljava/lang/String;", null);
        component.visitEnd();
        writer.visitField(ACC_PRIVATE | ACC_FINAL, "name", "Ljava/lang/String;", null, null).visitEnd();

        MethodVisitor init = writer.visitMethod(ACC_PUBLIC, "<init>", "(Ljava/lang/String;)V", null, null);
        init.visitCode();
        init.visitVarInsn(ALOAD, 0);
        init.visitMethodInsn(INVOKESPECIAL, "java/lang/Record", "<init>", "()V", false);
        init.visitVarInsn(ALOAD, 0);
        init.visitVarInsn(ALOAD, 1);
        init.visitFieldInsn(PUTFIELD, internalName, "name", "Ljava/lang/String;");
        init.visitInsn(RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();

        MethodVisitor accessor = writer.visitMethod(ACC_PUBLIC, "name", "()Ljava/lang/String;", null, null);
        accessor.visitCode();
        accessor.visitVarInsn(ALOAD, 0);
        accessor.visitFieldInsn(GETFIELD, internalName, "name", "Ljava/lang/String;");
        accessor.visitInsn(ARETURN);
        accessor.visitMaxs(0, 0);
        accessor.visitEnd();

        writer.visitEnd();
        return writer.toByteArray();
    }

    public static byte[] classWithBridgeSyntheticMethod(String internalName) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(V17, ACC_PUBLIC | ACC_SUPER, internalName, null, "java/lang/Object", null);
        MethodVisitor method = writer.visitMethod(
                ACC_PUBLIC | ACC_STATIC | ACC_BRIDGE | ACC_SYNTHETIC,
                "bridgeValue",
                "(Ljava/lang/Object;)Ljava/lang/Object;",
                null,
                null);
        method.visitCode();
        method.visitVarInsn(ALOAD, 0);
        method.visitInsn(ARETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    public static byte[] classWithReflectionTarget(String internalName) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(V17, ACC_PUBLIC | ACC_SUPER, internalName, null, "java/lang/Object", null);
        writer.visitField(ACC_PUBLIC, "field", "Ljava/lang/String;", null, null).visitEnd();

        MethodVisitor init = writer.visitMethod(ACC_PUBLIC, "<init>", "(Ljava/lang/String;)V", null, null);
        init.visitCode();
        init.visitVarInsn(ALOAD, 0);
        init.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitVarInsn(ALOAD, 0);
        init.visitVarInsn(ALOAD, 1);
        init.visitFieldInsn(PUTFIELD, internalName, "field", "Ljava/lang/String;");
        init.visitInsn(RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();

        MethodVisitor target = writer.visitMethod(
                ACC_PUBLIC | ACC_STATIC,
                "target",
                "(Ljava/lang/String;)Ljava/lang/String;",
                null,
                null);
        target.visitCode();
        target.visitVarInsn(ALOAD, 0);
        target.visitInsn(ARETURN);
        target.visitMaxs(0, 0);
        target.visitEnd();

        MethodVisitor invokeTarget = writer.visitMethod(
                ACC_PUBLIC | ACC_STATIC,
                "invokeTarget",
                "()Ljava/lang/String;",
                null,
                null);
        invokeTarget.visitCode();
        invokeTarget.visitLdcInsn("ok");
        invokeTarget.visitInsn(ARETURN);
        invokeTarget.visitMaxs(0, 0);
        invokeTarget.visitEnd();

        writer.visitEnd();
        return writer.toByteArray();
    }

    public static byte[] classWithStaticReflectionMethods(String internalName, String targetInternalName) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(V17, ACC_PUBLIC | ACC_SUPER, internalName, null, "java/lang/Object", null);

        MethodVisitor classLiteral = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "classLiteral", "()V", null, null);
        classLiteral.visitCode();
        classLiteral.visitLdcInsn(Type.getObjectType(targetInternalName));
        classLiteral.visitInsn(POP);
        classLiteral.visitInsn(RETURN);
        classLiteral.visitMaxs(0, 0);
        classLiteral.visitEnd();

        MethodVisitor forName = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "forName", "()V", null, null);
        forName.visitCode();
        forName.visitLdcInsn(targetInternalName.replace('/', '.'));
        forName.visitMethodInsn(INVOKESTATIC, "java/lang/Class", "forName", "(Ljava/lang/String;)Ljava/lang/Class;", false);
        forName.visitInsn(POP);
        forName.visitInsn(RETURN);
        forName.visitMaxs(0, 0);
        forName.visitEnd();

        MethodVisitor forNameNoInit = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "forNameNoInit", "()V", null, null);
        forNameNoInit.visitCode();
        forNameNoInit.visitLdcInsn(targetInternalName.replace('/', '.'));
        forNameNoInit.visitInsn(ICONST_0);
        forNameNoInit.visitInsn(ACONST_NULL);
        forNameNoInit.visitMethodInsn(
                INVOKESTATIC,
                "java/lang/Class",
                "forName",
                "(Ljava/lang/String;ZLjava/lang/ClassLoader;)Ljava/lang/Class;",
                false);
        forNameNoInit.visitInsn(POP);
        forNameNoInit.visitInsn(RETURN);
        forNameNoInit.visitMaxs(0, 0);
        forNameNoInit.visitEnd();

        MethodVisitor declaredMethod = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "declaredMethod", "()V", null, null);
        declaredMethod.visitCode();
        declaredMethod.visitLdcInsn(Type.getObjectType(targetInternalName));
        declaredMethod.visitLdcInsn("target");
        emitClassArray(declaredMethod, "java/lang/String");
        declaredMethod.visitMethodInsn(
                INVOKEVIRTUAL,
                "java/lang/Class",
                "getDeclaredMethod",
                "(Ljava/lang/String;[Ljava/lang/Class;)Ljava/lang/reflect/Method;",
                false);
        declaredMethod.visitInsn(POP);
        declaredMethod.visitInsn(RETURN);
        declaredMethod.visitMaxs(0, 0);
        declaredMethod.visitEnd();

        MethodVisitor declaredField = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "declaredField", "()V", null, null);
        declaredField.visitCode();
        declaredField.visitLdcInsn(Type.getObjectType(targetInternalName));
        declaredField.visitLdcInsn("field");
        declaredField.visitMethodInsn(
                INVOKEVIRTUAL,
                "java/lang/Class",
                "getDeclaredField",
                "(Ljava/lang/String;)Ljava/lang/reflect/Field;",
                false);
        declaredField.visitInsn(POP);
        declaredField.visitInsn(RETURN);
        declaredField.visitMaxs(0, 0);
        declaredField.visitEnd();

        MethodVisitor declaredConstructor = writer.visitMethod(
                ACC_PUBLIC | ACC_STATIC,
                "declaredConstructor",
                "()V",
                null,
                null);
        declaredConstructor.visitCode();
        declaredConstructor.visitLdcInsn(Type.getObjectType(targetInternalName));
        emitClassArray(declaredConstructor, "java/lang/String");
        declaredConstructor.visitMethodInsn(
                INVOKEVIRTUAL,
                "java/lang/Class",
                "getDeclaredConstructor",
                "([Ljava/lang/Class;)Ljava/lang/reflect/Constructor;",
                false);
        declaredConstructor.visitInsn(POP);
        declaredConstructor.visitInsn(RETURN);
        declaredConstructor.visitMaxs(0, 0);
        declaredConstructor.visitEnd();

        MethodVisitor reflectiveInvoke = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "reflectiveInvoke", "()V", null, null);
        reflectiveInvoke.visitCode();
        reflectiveInvoke.visitLdcInsn(Type.getObjectType(targetInternalName));
        reflectiveInvoke.visitLdcInsn("invokeTarget");
        reflectiveInvoke.visitInsn(ICONST_0);
        reflectiveInvoke.visitTypeInsn(ANEWARRAY, "java/lang/Class");
        reflectiveInvoke.visitMethodInsn(
                INVOKEVIRTUAL,
                "java/lang/Class",
                "getDeclaredMethod",
                "(Ljava/lang/String;[Ljava/lang/Class;)Ljava/lang/reflect/Method;",
                false);
        reflectiveInvoke.visitInsn(ACONST_NULL);
        reflectiveInvoke.visitInsn(ICONST_0);
        reflectiveInvoke.visitTypeInsn(ANEWARRAY, "java/lang/Object");
        reflectiveInvoke.visitMethodInsn(
                INVOKEVIRTUAL,
                "java/lang/reflect/Method",
                "invoke",
                "(Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;",
                false);
        reflectiveInvoke.visitInsn(POP);
        reflectiveInvoke.visitInsn(RETURN);
        reflectiveInvoke.visitMaxs(0, 0);
        reflectiveInvoke.visitEnd();

        MethodVisitor dynamicForName = writer.visitMethod(
                ACC_PUBLIC | ACC_STATIC,
                "dynamicForName",
                "(Ljava/lang/String;)V",
                null,
                null);
        dynamicForName.visitCode();
        dynamicForName.visitVarInsn(ALOAD, 0);
        dynamicForName.visitMethodInsn(INVOKESTATIC, "java/lang/Class", "forName", "(Ljava/lang/String;)Ljava/lang/Class;", false);
        dynamicForName.visitInsn(POP);
        dynamicForName.visitInsn(RETURN);
        dynamicForName.visitMaxs(0, 0);
        dynamicForName.visitEnd();

        writer.visitEnd();
        return writer.toByteArray();
    }

    private static void emitDefaultConstructor(ClassWriter writer) {
        MethodVisitor init = writer.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(ALOAD, 0);
        init.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(RETURN);
        init.visitMaxs(1, 1);
        init.visitEnd();
    }

    private static void emitClassArray(MethodVisitor method, String... internalNames) {
        method.visitIntInsn(BIPUSH, internalNames.length);
        method.visitTypeInsn(ANEWARRAY, "java/lang/Class");
        for (int index = 0; index < internalNames.length; index++) {
            method.visitInsn(DUP);
            method.visitIntInsn(BIPUSH, index);
            method.visitLdcInsn(Type.getObjectType(internalNames[index]));
            method.visitInsn(AASTORE);
        }
    }
}
