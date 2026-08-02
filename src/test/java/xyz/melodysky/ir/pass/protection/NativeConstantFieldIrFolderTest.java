package xyz.melodysky.ir.pass.protection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import xyz.melodysky.analysis.field.FieldAccessSite;
import xyz.melodysky.analysis.field.FieldCodeOrigin;
import xyz.melodysky.analysis.field.FieldId;
import xyz.melodysky.analysis.field.FieldInternalizationReason;
import xyz.melodysky.analysis.field.FieldInternalizationStatus;
import xyz.melodysky.analysis.field.FieldReferenceKind;
import xyz.melodysky.analysis.field.NativeFieldConstant;
import xyz.melodysky.analysis.field.NativeFieldInternalizationDecision;
import xyz.melodysky.analysis.field.NativeFieldInternalizationPlan;
import xyz.melodysky.analysis.field.NativeFieldInternalizationStorage;
import xyz.melodysky.ir.model.IrBlock;
import xyz.melodysky.ir.model.IrInstruction;
import xyz.melodysky.ir.model.IrMethod;
import xyz.melodysky.ir.model.IrOpcode;
import xyz.melodysky.ir.model.IrTerminator;
import xyz.melodysky.ir.model.IrType;
import xyz.melodysky.ir.model.IrValue;

class NativeConstantFieldIrFolderTest {
    private static final FieldId LIMIT =
            new FieldId("fixture/Constants", "LIMIT", "I");

    @Test
    void replacesPrimitiveGetstaticWithExactSsaConstantAndNoSlot() {
        IrValue value = new IrValue("%limit", IrType.I32);
        IrMethod input = method(List.of(IrInstruction.fieldGet(
                value,
                IrOpcode.GET_STATIC,
                List.of(),
                LIMIT.fieldKey())));
        NativeFieldInternalizationPlan plan = constantPlan(
                LIMIT,
                NativeFieldConstant.from("I", Integer.valueOf(128)).orElseThrow(),
                input.methodKey());

        NativeFieldIrRewriteResult result = new NativeFieldIrRewriter().rewrite(
                Map.of(input.methodKey(), input),
                plan,
                java.util.Set.of(input.methodKey()));

        assertTrue(result.diagnostics().isEmpty());
        assertEquals(List.of(input.methodKey()), result.affectedMethods());
        assertTrue(result.affectedSlots().isEmpty());
        IrInstruction folded = result.methods().get(input.methodKey())
                .blocks().get(0).instructions().get(0);
        assertEquals(IrOpcode.CONST_INT, folded.opcode());
        assertEquals(Optional.of(128), folded.intLiteral());
        assertEquals(Optional.of(value), folded.result());
        assertTrue(folded.symbol().isEmpty());
    }

    @Test
    void foldsEveryPrimitiveConstantDescriptorWithExactJvmBits() {
        List<ConstantCase> cases = List.of(
                new ConstantCase("Z", Integer.valueOf(3), IrType.I32, 1L),
                new ConstantCase("B", Integer.valueOf(255), IrType.I32, -1L),
                new ConstantCase("S", Integer.valueOf(65535), IrType.I32, -1L),
                new ConstantCase("C", Integer.valueOf(-1), IrType.I32, 65535L),
                new ConstantCase("I", Integer.valueOf(-123456), IrType.I32, -123456L),
                new ConstantCase("J", Long.valueOf(0x7123456789abcdefL), IrType.I64,
                        0x7123456789abcdefL),
                new ConstantCase("F", Float.intBitsToFloat(0x7fa12345), IrType.F32,
                        Integer.toUnsignedLong(0x7fa12345)),
                new ConstantCase("D", Double.longBitsToDouble(0x8000000000000000L), IrType.F64,
                        0x8000000000000000L));

        for (int index = 0; index < cases.size(); index++) {
            ConstantCase candidate = cases.get(index);
            FieldId field = new FieldId(
                    "fixture/Constants",
                    "VALUE" + index,
                    candidate.descriptor());
            IrValue value = new IrValue("%value" + index, candidate.irType());
            IrMethod input = method(List.of(IrInstruction.fieldGet(
                    value,
                    IrOpcode.GET_STATIC,
                    List.of(),
                    field.fieldKey())));
            NativeFieldInternalizationPlan plan = constantPlan(
                    field,
                    NativeFieldConstant.from(
                                    candidate.descriptor(),
                                    candidate.classfileValue())
                            .orElseThrow(),
                    input.methodKey());

            NativeFieldIrRewriteResult result = new NativeFieldIrRewriter().rewrite(
                    Map.of(input.methodKey(), input),
                    plan,
                    java.util.Set.of(input.methodKey()));
            IrInstruction folded = result.methods().get(input.methodKey())
                    .blocks().get(0).instructions().get(0);

            assertTrue(result.diagnostics().isEmpty(), candidate.descriptor());
            assertFoldedBits(candidate, folded);
        }
    }

    @Test
    void rejectsAccessCountMismatchWithoutPartialRewrite() {
        IrMethod input = method(List.of());
        NativeFieldInternalizationPlan plan = constantPlan(
                LIMIT,
                NativeFieldConstant.from("I", Integer.valueOf(3)).orElseThrow(),
                input.methodKey());

        NativeFieldIrRewriteResult result = new NativeFieldIrRewriter().rewrite(
                Map.of(input.methodKey(), input),
                plan,
                java.util.Set.of(input.methodKey()));

        assertFalse(result.changed());
        assertSame(input, result.methods().get(input.methodKey()));
        assertTrue(result.diagnostics().stream().allMatch(diagnostic ->
                diagnostic.code().value().equals(
                        "FIELD_INTERNALIZATION_ACCESS_MISMATCH")));
    }

    @Test
    void failsClosedForExplicitStringReadUntilInternIdentityIsPreserved() {
        FieldId text = new FieldId(
                "fixture/Constants",
                "TEXT",
                "Ljava/lang/String;");
        IrMethod input = method(List.of(IrInstruction.fieldGet(
                new IrValue("%text", IrType.REFERENCE),
                IrOpcode.GET_STATIC,
                List.of(),
                text.fieldKey())));
        NativeFieldInternalizationPlan plan = constantPlan(
                text,
                NativeFieldConstant.from(
                                "Ljava/lang/String;",
                                "protected-text")
                        .orElseThrow(),
                input.methodKey());

        NativeFieldIrRewriteResult result = new NativeFieldIrRewriter().rewrite(
                Map.of(input.methodKey(), input),
                plan,
                java.util.Set.of(input.methodKey()));

        assertFalse(result.changed());
        assertSame(input, result.methods().get(input.methodKey()));
        assertTrue(result.diagnostics().stream()
                .flatMap(diagnostic -> java.util.stream.Stream.of(diagnostic.message()))
                .anyMatch(message -> message.contains("intern-preserving identity")));
    }

    private IrMethod method(List<IrInstruction> instructions) {
        return new IrMethod(
                "fixture/Constants",
                "read",
                "()V",
                IrType.VOID,
                List.of(),
                List.of(new IrBlock(
                        "entry",
                        instructions,
                        IrTerminator.returnVoid())));
    }

    private NativeFieldInternalizationPlan constantPlan(
            FieldId field,
            NativeFieldConstant constant,
            String methodKey) {
        String methodName = methodKey.substring(
                methodKey.indexOf('#') + 1,
                methodKey.indexOf('!'));
        FieldAccessSite access = new FieldAccessSite(
                field,
                methodKey,
                field.owner(),
                methodName,
                true,
                FieldCodeOrigin.INPUT,
                FieldReferenceKind.BYTECODE_STATIC_READ,
                field.owner(),
                0,
                false);
        return new NativeFieldInternalizationPlan(List.of(
                new NativeFieldInternalizationDecision(
                        field,
                        FieldInternalizationStatus.INTERNALIZED,
                        NativeFieldInternalizationStorage.COMPILE_TIME_CONSTANT,
                        Optional.empty(),
                        Optional.of(constant),
                        List.of(access),
                        List.of(FieldInternalizationReason.FIELD_CONSTANT_INTERNALIZATION_ELIGIBLE))));
    }

    private void assertFoldedBits(
            ConstantCase candidate,
            IrInstruction folded) {
        switch (candidate.descriptor()) {
            case "Z", "B", "S", "C", "I" -> {
                assertEquals(IrOpcode.CONST_INT, folded.opcode());
                assertEquals((int) candidate.expectedBits(),
                        folded.intLiteral().orElseThrow());
            }
            case "J" -> {
                assertEquals(IrOpcode.CONST_LONG, folded.opcode());
                assertEquals(candidate.expectedBits(),
                        folded.longLiteral().orElseThrow());
            }
            case "F" -> {
                assertEquals(IrOpcode.CONST_FLOAT, folded.opcode());
                assertEquals(
                        (int) candidate.expectedBits(),
                        Float.floatToRawIntBits(
                                folded.floatLiteral().orElseThrow()));
            }
            case "D" -> {
                assertEquals(IrOpcode.CONST_DOUBLE, folded.opcode());
                assertEquals(
                        candidate.expectedBits(),
                        Double.doubleToRawLongBits(
                                folded.doubleLiteral().orElseThrow()));
            }
            default -> throw new AssertionError(candidate.descriptor());
        }
    }

    private record ConstantCase(
            String descriptor,
            Object classfileValue,
            IrType irType,
            long expectedBits) {}
}
