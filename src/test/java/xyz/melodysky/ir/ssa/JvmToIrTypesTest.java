package xyz.melodysky.ir.ssa;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;
import xyz.melodysky.ir.model.IrType;

class JvmToIrTypesTest {
    @Test
    void mapsFieldDescriptorsToIrTypes() {
        assertEquals(IrType.I32, JvmToIrTypes.fieldType("Z"));
        assertEquals(IrType.I64, JvmToIrTypes.fieldType("J"));
        assertEquals(IrType.F32, JvmToIrTypes.fieldType("F"));
        assertEquals(IrType.F64, JvmToIrTypes.fieldType("D"));
        assertEquals(IrType.REFERENCE, JvmToIrTypes.fieldType("Ljava/lang/String;"));
        assertEquals(IrType.REFERENCE, JvmToIrTypes.fieldType("[[I"));
        assertEquals(IrType.REFERENCE, JvmToIrTypes.fieldType("[Ljava/lang/String;"));
    }

    @Test
    void mapsMethodParametersDescriptorsAndReturnTypeTogether() {
        String descriptor = "(IZJFDLjava/lang/String;[[I[Ljava/lang/Object;)Ljava/lang/Number;";

        assertEquals(
                List.of(
                        IrType.I32,
                        IrType.I32,
                        IrType.I64,
                        IrType.F32,
                        IrType.F64,
                        IrType.REFERENCE,
                        IrType.REFERENCE,
                        IrType.REFERENCE),
                JvmToIrTypes.parameterTypes(descriptor));
        assertEquals(
                List.of("I", "Z", "J", "F", "D", "Ljava/lang/String;", "[[I", "[Ljava/lang/Object;"),
                JvmToIrTypes.parameterDescriptors(descriptor));
        assertEquals(IrType.REFERENCE, JvmToIrTypes.returnType(descriptor));
        assertEquals(IrType.VOID, JvmToIrTypes.returnType("()V"));
    }

    @Test
    void rejectsMalformedFieldDescriptors() {
        assertInvalidField("");
        assertInvalidField("V");
        assertInvalidField("Ljava/lang/String");
        assertInvalidField("L;");
        assertInvalidField("[");
        assertInvalidField("[V");
        assertInvalidField("II");
        assertInvalidField("Q");
    }

    @Test
    void rejectsMalformedMethodDescriptors() {
        assertInvalidMethod("I)V");
        assertInvalidMethod("(I");
        assertInvalidMethod("(I)");
        assertInvalidMethod("(V)V");
        assertInvalidMethod("(Ljava/lang/String)V");
        assertInvalidMethod("([V)V");
        assertThrows(IllegalArgumentException.class, () -> JvmToIrTypes.returnType("()VI"));
    }

    private void assertInvalidField(String descriptor) {
        assertThrows(IllegalArgumentException.class, () -> JvmToIrTypes.fieldType(descriptor));
    }

    private void assertInvalidMethod(String descriptor) {
        assertThrows(IllegalArgumentException.class, () -> JvmToIrTypes.parameterTypes(descriptor));
    }
}
