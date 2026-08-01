package xyz.melodysky.packaging;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import xyz.melodysky.analysis.method.NativeMethodId;
import xyz.melodysky.analysis.method.NativeMethodInternalizationDecision;
import xyz.melodysky.analysis.method.NativeMethodInternalizationPlan;
import xyz.melodysky.analysis.method.NativeMethodInternalizationReason;
import xyz.melodysky.analysis.method.NativeMethodInternalizationStatus;
import xyz.melodysky.analysis.world.WholeProgramAnalysisScope;

class InternalizedMethodClassTransformTest implements Opcodes {
    @Test
    void removesOnlyTheApprovedOverload() {
        byte[] input = fixture();
        NativeMethodInternalizationPlan plan = plan(
                new NativeMethodId(
                        "pkg/Hidden",
                        "helper",
                        "()Ljava/lang/String;"));

        var result = new InternalizedMethodClassTransform().apply(
                input,
                "pkg/Hidden",
                plan);

        assertTrue(result.diagnostics().isEmpty());
        assertEquals(
                java.util.Set.of(
                        "pkg/Hidden#helper!()Ljava/lang/String;"),
                result.removedMethodKeys());
        ClassNode node = read(result.classBytes());
        assertFalse(node.methods.stream().anyMatch(method ->
                method.name.equals("helper")
                        && method.desc.equals(
                                "()Ljava/lang/String;")));
        assertTrue(node.methods.stream().anyMatch(method ->
                method.name.equals("helper")
                        && method.desc.equals("(I)I")));
        assertTrue(node.methods.stream().anyMatch(method ->
                method.name.equals("caller")));
    }

    @Test
    void missingApprovedMethodFailsClosedWithoutPartialRewrite() {
        byte[] input = fixture();
        NativeMethodInternalizationPlan plan = plan(
                new NativeMethodId(
                        "pkg/Hidden",
                        "missing",
                        "()V"));

        var result = new InternalizedMethodClassTransform().apply(
                input,
                "pkg/Hidden",
                plan);

        assertEquals(1, result.diagnostics().size());
        assertEquals(
                "METHOD_INTERNALIZATION_REWRITE_FAILED",
                result.diagnostics().get(0).code().value());
        assertTrue(result.removedMethodKeys().isEmpty());
        assertArrayEquals(input, result.classBytes());
    }

    private NativeMethodInternalizationPlan plan(
            NativeMethodId method) {
        return new NativeMethodInternalizationPlan(
                true,
                WholeProgramAnalysisScope.DECLARED_CLOSED_WORLD,
                List.of(new NativeMethodInternalizationDecision(
                        method,
                        NativeMethodInternalizationStatus.INTERNALIZED,
                        true,
                        "protected",
                        List.of("pkg/Hidden#caller!()V"),
                        List.of(NativeMethodInternalizationReason
                                .METHOD_INTERNALIZATION_ELIGIBLE))));
    }

    private byte[] fixture() {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(
                V17,
                ACC_SUPER,
                "pkg/Hidden",
                null,
                "java/lang/Object",
                null);
        var stringHelper = writer.visitMethod(
                ACC_PROTECTED | ACC_STATIC,
                "helper",
                "()Ljava/lang/String;",
                null,
                null);
        stringHelper.visitCode();
        stringHelper.visitInsn(ACONST_NULL);
        stringHelper.visitInsn(ARETURN);
        stringHelper.visitMaxs(1, 0);
        stringHelper.visitEnd();
        var intHelper = writer.visitMethod(
                ACC_PROTECTED | ACC_STATIC,
                "helper",
                "(I)I",
                null,
                null);
        intHelper.visitCode();
        intHelper.visitVarInsn(ILOAD, 0);
        intHelper.visitInsn(IRETURN);
        intHelper.visitMaxs(1, 1);
        intHelper.visitEnd();
        var caller = writer.visitMethod(
                ACC_PUBLIC | ACC_STATIC,
                "caller",
                "()V",
                null,
                null);
        caller.visitCode();
        caller.visitInsn(RETURN);
        caller.visitMaxs(0, 0);
        caller.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private ClassNode read(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }
}
