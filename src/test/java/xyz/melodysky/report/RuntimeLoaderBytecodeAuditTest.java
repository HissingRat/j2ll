package xyz.melodysky.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

class RuntimeLoaderBytecodeAuditTest implements Opcodes {
    @Test
    void acceptsMinimalLoaderWithoutClassDefinitionSurface() {
        RuntimeLoaderBytecodeAudit.Result result =
                new RuntimeLoaderBytecodeAudit().inspect(loader(false));

        assertEquals("native0/Loader", result.internalName());
        assertEquals(V17, result.majorVersion());
        assertTrue(result.forbiddenSurfaces().isEmpty());
    }

    @Test
    void rejectsRenamedHiddenClassDefinitionCall() {
        RuntimeLoaderBytecodeAudit.Result result =
                new RuntimeLoaderBytecodeAudit().inspect(loader(true));

        assertTrue(result.forbiddenSurfaces().stream().anyMatch(surface ->
                surface.contains(
                        "java/lang/invoke/MethodHandles$Lookup.defineHiddenClass")));
    }

    private byte[] loader(boolean classDefinitionCall) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(
                V17,
                ACC_PUBLIC | ACC_FINAL | ACC_SUPER,
                "native0/Loader",
                null,
                "java/lang/Object",
                null);
        MethodVisitor method = writer.visitMethod(
                ACC_PUBLIC | ACC_STATIC,
                "load",
                "()V",
                null,
                null);
        method.visitCode();
        if (classDefinitionCall) {
            method.visitInsn(ACONST_NULL);
            method.visitInsn(ACONST_NULL);
            method.visitInsn(ICONST_0);
            method.visitInsn(ICONST_0);
            method.visitTypeInsn(
                    ANEWARRAY,
                    "java/lang/invoke/MethodHandles$Lookup$ClassOption");
            method.visitMethodInsn(
                    INVOKEVIRTUAL,
                    "java/lang/invoke/MethodHandles$Lookup",
                    "defineHiddenClass",
                    "([BZ[Ljava/lang/invoke/MethodHandles$Lookup$ClassOption;)"
                            + "Ljava/lang/invoke/MethodHandles$Lookup;",
                    false);
            method.visitInsn(POP);
        }
        method.visitInsn(RETURN);
        method.visitMaxs(4, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }
}
