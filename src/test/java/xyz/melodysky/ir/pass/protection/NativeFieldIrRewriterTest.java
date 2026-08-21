package xyz.melodysky.ir.pass.protection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static xyz.melodysky.testsupport.NativeFieldInternalizationFixtures.nativeStored;
import static xyz.melodysky.testsupport.NativeFieldInternalizationFixtures.plan;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import xyz.melodysky.analysis.field.FieldAccessSite;
import xyz.melodysky.analysis.field.FieldCodeOrigin;
import xyz.melodysky.analysis.field.FieldId;
import xyz.melodysky.analysis.field.FieldReferenceKind;
import xyz.melodysky.analysis.field.NativeFieldInternalizationDecision;
import xyz.melodysky.analysis.field.NativeFieldInternalizationPlan;
import xyz.melodysky.ir.model.IrBlock;
import xyz.melodysky.ir.model.IrInstruction;
import xyz.melodysky.ir.model.IrMethod;
import xyz.melodysky.ir.model.IrOpcode;
import xyz.melodysky.ir.model.IrTerminator;
import xyz.melodysky.ir.model.IrType;
import xyz.melodysky.ir.model.IrValue;
import xyz.melodysky.ir.model.NativeFieldSlotRef;
import xyz.melodysky.analysis.field.NativeFieldStorageKind;

class NativeFieldIrRewriterTest {
    private static final FieldId APPROVED = new FieldId("pkg/State", "counter", "I");
    private static final String SLOT = "j2ll_nfs_00112233445566778899aabbccddeeff";
    private static final String ENCODED_SLOT = new NativeFieldSlotRef(
            NativeFieldStorageKind.INT,
            SLOT,
            -1).encoded();

    private final NativeFieldIrRewriter rewriter = new NativeFieldIrRewriter();

    @Test
    void rewritesOnlyApprovedStaticFieldInstructionsAndPreservesClassInitGuard() {
        IrValue definingClass = new IrValue("%class", IrType.REFERENCE);
        IrValue value = new IrValue("%value", IrType.I32);
        IrValue approvedRead = new IrValue("%approved", IrType.I32);
        IrValue retainedRead = new IrValue("%retained", IrType.I64);
        IrInstruction guard = IrInstruction.operation(
                Optional.empty(),
                IrOpcode.CLASS_INIT_GUARD,
                List.of(definingClass),
                APPROVED.owner());
        IrMethod input = method(List.of(
                guard,
                IrInstruction.fieldGet(
                        approvedRead,
                        IrOpcode.GET_STATIC,
                        List.of(),
                        APPROVED.fieldKey()),
                IrInstruction.fieldPut(
                        IrOpcode.PUT_STATIC,
                        List.of(value),
                        APPROVED.fieldKey()),
                IrInstruction.fieldGet(
                        retainedRead,
                        IrOpcode.GET_STATIC,
                        List.of(),
                        "pkg/State#retained!J")));

        NativeFieldIrRewriteResult result = rewriter.rewrite(
                Map.of(input.methodKey(), input),
                approvedPlan());
        List<IrInstruction> instructions = result.methods().get(input.methodKey()).blocks().get(0).instructions();

        assertTrue(result.diagnostics().isEmpty());
        assertEquals(List.of(input.methodKey()), result.affectedMethods());
        assertEquals(List.of(SLOT), result.affectedSlots());
        assertSame(guard, instructions.get(0));
        assertEquals(IrOpcode.GET_NATIVE_STATIC, instructions.get(1).opcode());
        assertEquals(Optional.of(ENCODED_SLOT), instructions.get(1).symbol());
        assertEquals(IrOpcode.PUT_NATIVE_STATIC, instructions.get(2).opcode());
        assertEquals(Optional.of(ENCODED_SLOT), instructions.get(2).symbol());
        assertEquals(IrOpcode.GET_STATIC, instructions.get(3).opcode());
        assertEquals(Optional.of("pkg/State#retained!J"), instructions.get(3).symbol());
    }

    @Test
    void referenceRewriteConsumesExplicitPlanIndicesInsteadOfFieldOrder() {
        FieldId first = new FieldId(
                "pkg/State",
                "alpha",
                "Ljava/lang/Object;");
        FieldId second = new FieldId(
                "pkg/State",
                "omega",
                "[Ljava/lang/String;");
        String firstSlot = "j2ll_nfs_reference_first";
        String secondSlot = "j2ll_nfs_reference_second";
        IrMethod input = method(List.of(
                IrInstruction.fieldGet(
                        new IrValue("%first", IrType.REFERENCE),
                        IrOpcode.GET_STATIC,
                        List.of(),
                        first.fieldKey()),
                IrInstruction.fieldGet(
                        new IrValue("%second", IrType.REFERENCE),
                        IrOpcode.GET_STATIC,
                        List.of(),
                        second.fieldKey())));
        String methodKey = input.methodKey();
        NativeFieldInternalizationDecision firstDecision =
                referenceDecision(first, firstSlot, methodKey, 0);
        NativeFieldInternalizationDecision secondDecision =
                referenceDecision(second, secondSlot, methodKey, 1);
        NativeFieldInternalizationPlan plan = new NativeFieldInternalizationPlan(
                List.of(firstDecision, secondDecision),
                Map.of(first.owner(), Map.of(first, 1, second, 0)));

        NativeFieldIrRewriteResult result = rewriter.rewrite(
                Map.of(methodKey, input),
                plan);
        List<IrInstruction> rewritten =
                result.methods().get(methodKey).blocks().get(0).instructions();

        assertTrue(result.diagnostics().isEmpty());
        assertEquals(
                Optional.of(new NativeFieldSlotRef(
                                NativeFieldStorageKind.REFERENCE,
                                firstSlot,
                                1)
                        .encoded()),
                rewritten.get(0).symbol());
        assertEquals(
                Optional.of(new NativeFieldSlotRef(
                                NativeFieldStorageKind.REFERENCE,
                                secondSlot,
                                0)
                        .encoded()),
                rewritten.get(1).symbol());
    }

    @Test
    void emptyPlanIsNoOpAndDoesNotRebuildMethods() {
        IrMethod input = method(List.of(IrInstruction.fieldGet(
                new IrValue("%result", IrType.I32),
                IrOpcode.GET_STATIC,
                List.of(),
                APPROVED.fieldKey())));

        NativeFieldIrRewriteResult result = rewriter.rewrite(
                Map.of(input.methodKey(), input),
                NativeFieldInternalizationPlan.empty());

        assertFalse(result.changed());
        assertSame(input, result.methods().get(input.methodKey()));
        assertTrue(result.diagnostics().isEmpty());
    }

    @Test
    void invalidInputIrIsRejectedWithoutPartialRewrite() {
        IrValue resultValue = new IrValue("%result", IrType.I32);
        IrMethod invalid = new IrMethod(
                "pkg/State",
                "broken",
                "()I",
                IrType.I32,
                List.of(),
                List.of(new IrBlock(
                        "entry",
                        List.of(IrInstruction.fieldGet(
                                resultValue,
                                IrOpcode.GET_STATIC,
                                List.of(),
                                APPROVED.fieldKey())),
                        IrTerminator.returnVoid())));

        NativeFieldIrRewriteResult result = rewriter.rewrite(
                Map.of(invalid.methodKey(), invalid),
                approvedPlan(
                        invalid.methodKey(),
                        List.of(FieldReferenceKind.BYTECODE_STATIC_READ)));

        assertFalse(result.changed());
        assertSame(invalid, result.methods().get(invalid.methodKey()));
        assertEquals(1, result.diagnostics().size());
        assertEquals(
                "FIELD_INTERNALIZATION_INPUT_IR_INVALID",
                result.diagnostics().get(0).code().value());
    }

    @Test
    void unrelatedInvalidIrDoesNotCancelACompleteAccessorRewrite() {
        IrValue value = new IrValue("%value", IrType.I32);
        IrMethod accessor = method(List.of(
                IrInstruction.fieldGet(
                        new IrValue("%result", IrType.I32),
                        IrOpcode.GET_STATIC,
                        List.of(),
                        APPROVED.fieldKey()),
                IrInstruction.fieldPut(
                        IrOpcode.PUT_STATIC,
                        List.of(value),
                        APPROVED.fieldKey())));
        IrMethod unrelatedInvalid = new IrMethod(
                "pkg/Other",
                "broken",
                "()I",
                IrType.I32,
                List.of(),
                List.of(new IrBlock(
                        "entry",
                        List.of(),
                        IrTerminator.returnVoid())));

        NativeFieldIrRewriteResult result = rewriter.rewrite(
                Map.of(
                        accessor.methodKey(), accessor,
                        unrelatedInvalid.methodKey(), unrelatedInvalid),
                approvedPlan());

        assertTrue(result.diagnostics().isEmpty());
        assertEquals(List.of(accessor.methodKey()), result.affectedMethods());
        assertTrue(result.methods().get(accessor.methodKey()).blocks().get(0)
                .instructions().stream()
                .allMatch(instruction -> instruction.opcode() == IrOpcode.GET_NATIVE_STATIC
                        || instruction.opcode() == IrOpcode.PUT_NATIVE_STATIC));
        assertSame(
                unrelatedInvalid,
                result.methods().get(unrelatedInvalid.methodKey()));
    }

    @Test
    void missingOrExtraPlanAccessFailsClosedBeforeAnyMethodIsRewritten() {
        IrMethod onlyRead = method(List.of(IrInstruction.fieldGet(
                new IrValue("%result", IrType.I32),
                IrOpcode.GET_STATIC,
                List.of(),
                APPROVED.fieldKey())));
        NativeFieldIrRewriteResult missingWrite = rewriter.rewrite(
                Map.of(onlyRead.methodKey(), onlyRead),
                approvedPlan());

        assertFalse(missingWrite.changed());
        assertSame(onlyRead, missingWrite.methods().get(onlyRead.methodKey()));
        assertTrue(missingWrite.diagnostics().stream().allMatch(diagnostic ->
                diagnostic.code().value().equals("FIELD_INTERNALIZATION_ACCESS_MISMATCH")));

        IrValue value = new IrValue("%value", IrType.I32);
        IrMethod readAndWrite = method(List.of(
                IrInstruction.fieldGet(
                        new IrValue("%result", IrType.I32),
                        IrOpcode.GET_STATIC,
                        List.of(),
                        APPROVED.fieldKey()),
                IrInstruction.fieldPut(
                        IrOpcode.PUT_STATIC,
                        List.of(value),
                        APPROVED.fieldKey())));
        NativeFieldIrRewriteResult extraWrite = rewriter.rewrite(
                Map.of(readAndWrite.methodKey(), readAndWrite),
                approvedPlan(
                        readAndWrite.methodKey(),
                        List.of(FieldReferenceKind.BYTECODE_STATIC_READ)));

        assertFalse(extraWrite.changed());
        assertSame(readAndWrite, extraWrite.methods().get(readAndWrite.methodKey()));
        assertFalse(extraWrite.diagnostics().isEmpty());
    }

    @Test
    void absentPlannedMethodRollsBackTheWholeRewriteSet() {
        IrMethod existing = method(List.of(
                IrInstruction.fieldGet(
                        new IrValue("%result", IrType.I32),
                        IrOpcode.GET_STATIC,
                        List.of(),
                        APPROVED.fieldKey()),
                IrInstruction.fieldPut(
                        IrOpcode.PUT_STATIC,
                        List.of(new IrValue("%value", IrType.I32)),
                        APPROVED.fieldKey())));
        NativeFieldInternalizationPlan plan = approvedPlan(
                existing.methodKey(),
                List.of(
                        FieldReferenceKind.BYTECODE_STATIC_READ,
                        FieldReferenceKind.BYTECODE_STATIC_WRITE),
                "pkg/State#missing!()V",
                List.of(FieldReferenceKind.BYTECODE_STATIC_READ));

        NativeFieldIrRewriteResult result = rewriter.rewrite(
                Map.of(existing.methodKey(), existing),
                plan);

        assertFalse(result.changed());
        assertSame(existing, result.methods().get(existing.methodKey()));
        assertTrue(existing.blocks().get(0).instructions().stream()
                .anyMatch(instruction -> instruction.opcode() == IrOpcode.GET_STATIC));
        assertFalse(result.diagnostics().isEmpty());
    }

    private IrMethod method(List<IrInstruction> instructions) {
        IrValue definingClass = new IrValue("%class", IrType.REFERENCE);
        IrValue value = new IrValue("%value", IrType.I32);
        return new IrMethod(
                "pkg/State",
                "access",
                "(Ljava/lang/Class;I)V",
                IrType.VOID,
                List.of(definingClass, value),
                List.of(new IrBlock("entry", instructions, IrTerminator.returnVoid())));
    }

    private NativeFieldInternalizationPlan approvedPlan() {
        return approvedPlan(
                "pkg/State#access!(Ljava/lang/Class;I)V",
                List.of(
                        FieldReferenceKind.BYTECODE_STATIC_READ,
                        FieldReferenceKind.BYTECODE_STATIC_WRITE));
    }

    private NativeFieldInternalizationPlan approvedPlan(
            String methodKey,
            List<FieldReferenceKind> accesses) {
        return approvedPlan(methodKey, accesses, null, List.of());
    }

    private NativeFieldInternalizationPlan approvedPlan(
            String firstMethodKey,
            List<FieldReferenceKind> firstAccesses,
            String secondMethodKey,
            List<FieldReferenceKind> secondAccesses) {
        java.util.ArrayList<FieldAccessSite> sites = new java.util.ArrayList<>();
        addSites(sites, firstMethodKey, firstAccesses);
        if (secondMethodKey != null) {
            addSites(sites, secondMethodKey, secondAccesses);
        }
        return plan(List.of(nativeStored(APPROVED, SLOT, sites)));
    }

    private NativeFieldInternalizationDecision referenceDecision(
            FieldId field,
            String slot,
            String methodKey,
            int bytecodeOffset) {
        return nativeStored(
                field,
                slot,
                List.of(new FieldAccessSite(
                        field,
                        methodKey,
                        field.owner(),
                        "access",
                        true,
                        FieldCodeOrigin.INPUT,
                        FieldReferenceKind.BYTECODE_STATIC_READ,
                        field.owner(),
                        bytecodeOffset,
                        false)));
    }

    private void addSites(
            List<FieldAccessSite> sites,
            String methodKey,
            List<FieldReferenceKind> kinds) {
        String methodName = methodKey.substring(methodKey.indexOf('#') + 1, methodKey.indexOf('!'));
        for (int index = 0; index < kinds.size(); index++) {
            sites.add(new FieldAccessSite(
                    APPROVED,
                    methodKey,
                    APPROVED.owner(),
                    methodName,
                    true,
                    FieldCodeOrigin.INPUT,
                    kinds.get(index),
                    APPROVED.owner(),
                    index,
                    false));
        }
    }
}
