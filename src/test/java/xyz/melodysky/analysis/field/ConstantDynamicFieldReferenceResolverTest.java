package xyz.melodysky.analysis.field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.ConstantDynamic;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.LdcInsnNode;

class ConstantDynamicFieldReferenceResolverTest implements Opcodes {
    private final ConstantDynamicFieldReferenceResolver resolver =
            new ConstantDynamicFieldReferenceResolver();

    @Test
    void resolvesCurrentAndExplicitOwnerGetStaticFinal() {
        Handle bootstrap = bootstrap("getStaticFinal");

        var current = resolver.resolve(
                "sample/Current",
                new ConstantDynamic("value", "I", bootstrap));
        var explicit = resolver.resolve(
                "sample/Current",
                new ConstantDynamic(
                        "value",
                        "J",
                        bootstrap,
                        Type.getObjectType("sample/Other")));

        assertEquals(
                new FieldId("sample/Current", "value", "I"),
                current.target().orElseThrow());
        assertEquals(
                new FieldId("sample/Other", "value", "J"),
                explicit.target().orElseThrow());
    }

    @Test
    void resolvesVarHandleFieldTypeAndRejectsUnknownShape() {
        var exact = resolver.resolve(
                "sample/Current",
                new ConstantDynamic(
                        "value",
                        "Ljava/lang/invoke/VarHandle;",
                        bootstrap("staticFieldVarHandle"),
                        Type.getObjectType("sample/Other"),
                        Type.INT_TYPE));
        var unknown = resolver.resolve(
                "sample/Current",
                new ConstantDynamic(
                        "value",
                        "Ljava/lang/invoke/VarHandle;",
                        bootstrap("fieldVarHandle"),
                        "not-a-Class"));

        assertEquals(
                new FieldId("sample/Other", "value", "I"),
                exact.target().orElseThrow());
        assertTrue(exact.staticField());
        assertEquals(FieldDynamicBoundaryKind.VAR_HANDLE, exact.observerKind());
        assertTrue(unknown.fieldBootstrap());
        assertFalse(unknown.target().isPresent());
    }

    @Test
    void normalizesPrimitiveClassBootstrapArgumentAfterClassfileRoundTrip() {
        ConstantDynamic original = new ConstantDynamic(
                "value",
                "Ljava/lang/invoke/VarHandle;",
                bootstrap("staticFieldVarHandle"),
                Type.getObjectType("sample/Other"),
                Type.INT_TYPE);
        ClassWriter writer = new ClassWriter(0);
        writer.visit(V17, ACC_PUBLIC, "sample/Carrier", null, "java/lang/Object", null);
        var method = writer.visitMethod(ACC_STATIC, "read", "()V", null, null);
        method.visitCode();
        method.visitLdcInsn(original);
        method.visitInsn(POP);
        method.visitInsn(RETURN);
        method.visitMaxs(1, 0);
        method.visitEnd();
        writer.visitEnd();
        ClassNode parsed = new ClassNode();
        new ClassReader(writer.toByteArray()).accept(parsed, 0);
        ConstantDynamic roundTripped = (ConstantDynamic) ((LdcInsnNode) parsed.methods
                        .get(0)
                        .instructions
                        .getFirst())
                .cst;

        var resolution = resolver.resolve("sample/Carrier", roundTripped);

        assertEquals(
                new FieldId("sample/Other", "value", "I"),
                resolution.target().orElseThrow());
    }

    private Handle bootstrap(String name) {
        return new Handle(
                H_INVOKESTATIC,
                "java/lang/invoke/ConstantBootstraps",
                name,
                "()Ljava/lang/Object;",
                false);
    }
}
