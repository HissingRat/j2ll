package xyz.melodysky.ir.ssa;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import xyz.melodysky.ir.model.IrOpcode;
import xyz.melodysky.ir.model.IrType;

class SsaOpcodeSemanticsTest implements Opcodes {
    @Test
    void classifiesOpcodeFamiliesWithoutTreatingAdjacentOpcodesAsMembers() {
        assertTrue(SsaOpcodeSemantics.isLoad(ALOAD));
        assertFalse(SsaOpcodeSemantics.isLoad(ASTORE));
        assertTrue(SsaOpcodeSemantics.isStore(DSTORE));
        assertTrue(SsaOpcodeSemantics.isBinary(LXOR));
        assertTrue(SsaOpcodeSemantics.isIntegerDivisionOrRemainder(LREM));
        assertTrue(SsaOpcodeSemantics.isNegation(FNEG));
        assertTrue(SsaOpcodeSemantics.isShift(IUSHR));
        assertTrue(SsaOpcodeSemantics.isConversion(D2I));
        assertTrue(SsaOpcodeSemantics.isValueComparison(FCMPG));
        assertTrue(SsaOpcodeSemantics.isStackManipulation(DUP2_X2));
        assertTrue(SsaOpcodeSemantics.isArrayLoad(BALOAD));
        assertTrue(SsaOpcodeSemantics.isArrayStore(AASTORE));
        assertTrue(SsaOpcodeSemantics.isValueReturn(ARETURN));
        assertFalse(SsaOpcodeSemantics.isValueReturn(RETURN));
    }

    @Test
    void mapsNumericOpcodesAndResultTypes() {
        assertEquals(IrOpcode.ADD_I32, SsaOpcodeSemantics.irOpcode(IADD));
        assertEquals(IrOpcode.USHR_I64, SsaOpcodeSemantics.irOpcode(LUSHR));
        assertEquals(IrOpcode.FCMPL, SsaOpcodeSemantics.irOpcode(FCMPL));
        assertEquals(IrOpcode.D2F, SsaOpcodeSemantics.irOpcode(D2F));
        assertEquals(IrType.I64, SsaOpcodeSemantics.binaryResultType(LSHL));
        assertEquals(IrType.F32, SsaOpcodeSemantics.binaryResultType(FREM));
        assertEquals(IrType.F64, SsaOpcodeSemantics.conversionResultType(I2D));
        assertEquals(IrType.I32, SsaOpcodeSemantics.conversionResultType(I2B));
    }

    @Test
    void mapsBranchCallAndMethodHandleKinds() {
        assertTrue(SsaOpcodeSemantics.isConditionalBranch(IF_ICMPLE));
        assertTrue(SsaOpcodeSemantics.isConditionalBranch(IFNONNULL));
        assertFalse(SsaOpcodeSemantics.isConditionalBranch(GOTO));
        assertEquals(IrOpcode.CMP_LE_I32, SsaOpcodeSemantics.compareOpcode(IF_ICMPLE));
        assertEquals(IrOpcode.CMP_NE_REF, SsaOpcodeSemantics.compareOpcode(IFNONNULL));
        assertEquals(IrOpcode.CALL_INTERFACE, SsaOpcodeSemantics.callOpcode(INVOKEINTERFACE));
        assertEquals(IrOpcode.CALL_STATIC, SsaOpcodeSemantics.callOpcodeForHandleTag(H_INVOKESTATIC));
        assertEquals(IrOpcode.CALL_SPECIAL, SsaOpcodeSemantics.callOpcodeForHandleTag(H_NEWINVOKESPECIAL));
        assertEquals(IrOpcode.CALL_DYNAMIC, SsaOpcodeSemantics.callOpcodeForHandleTag(H_GETFIELD));
    }

    @Test
    void mapsArrayOpcodesAndPrimitiveAllocationKinds() {
        assertEquals(IrOpcode.ARRAY_LOAD_I64, SsaOpcodeSemantics.arrayLoadOpcode(LALOAD));
        assertEquals(IrOpcode.ARRAY_STORE_REF, SsaOpcodeSemantics.arrayStoreOpcode(AASTORE));
        assertEquals(IrType.REFERENCE, SsaOpcodeSemantics.arrayLoadType(AALOAD));
        assertEquals("byteOrBoolean", SsaOpcodeSemantics.arrayElementKind(BASTORE));
        assertEquals("double", SsaOpcodeSemantics.primitiveArrayType(T_DOUBLE));
    }

    @Test
    void rejectsOpcodesOutsideEachMappingDomain() {
        assertThrows(IllegalArgumentException.class, () -> SsaOpcodeSemantics.irOpcode(NOP));
        assertThrows(IllegalArgumentException.class, () -> SsaOpcodeSemantics.conversionResultType(IADD));
        assertThrows(IllegalArgumentException.class, () -> SsaOpcodeSemantics.compareOpcode(GOTO));
        assertThrows(IllegalArgumentException.class, () -> SsaOpcodeSemantics.callOpcode(INVOKEDYNAMIC));
        assertThrows(IllegalArgumentException.class, () -> SsaOpcodeSemantics.arrayLoadOpcode(IALOAD + 1000));
        assertThrows(IllegalArgumentException.class, () -> SsaOpcodeSemantics.arrayStoreOpcode(IASTORE + 1000));
        assertThrows(IllegalArgumentException.class, () -> SsaOpcodeSemantics.arrayElementKind(NOP));
        assertThrows(IllegalArgumentException.class, () -> SsaOpcodeSemantics.primitiveArrayType(-1));
    }
}
