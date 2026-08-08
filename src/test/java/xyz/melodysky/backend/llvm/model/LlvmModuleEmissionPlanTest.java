package xyz.melodysky.backend.llvm.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class LlvmModuleEmissionPlanTest {
    @Test
    void compatibleFunctionConstructorFailsClosedWhenMetadataIsMissing() {
        LlvmModule module = new LlvmModule("unknown-function", List.of(new LlvmFunction(
                "fixture",
                LlvmLinkage.INTERNAL,
                LlvmVisibility.HIDDEN,
                LlvmType.VOID,
                List.of(),
                List.of(block(List.of())))));

        LlvmNativeUnwindProof proof = LlvmModuleEmissionPlan.create(module).proof();

        assertFalse(proof.omissionSafe());
        assertEquals(LlvmNativeUnwindProof.PROOF_INCOMPLETE, proof.reasonCode());
        assertEquals(LlvmNativeUnwindSemantics.UNKNOWN, proof.findings().get(0).semantics());
    }

    @Test
    void compatibleInstructionConstructorFailsClosedInsideProvenFunction() {
        LlvmInstruction forgotten = LlvmInstruction.raw(
                Optional.of("%sum"),
                "add i32 1, 2");
        LlvmModule module = module(
                "unknown-instruction",
                LlvmNativeUnwindSemantics.PROVEN_ABSENT,
                List.of(forgotten));

        LlvmNativeUnwindProof proof = LlvmModuleEmissionPlan.create(module).proof();

        assertFalse(proof.omissionSafe());
        assertEquals(LlvmNativeUnwindProof.PROOF_INCOMPLETE, proof.reasonCode());
        assertTrue(proof.findings().get(0).instructionIndex().isPresent());
    }

    @Test
    void requiredFunctionOrInstructionTakesPrecedenceOverUnknownEvidence() {
        LlvmInstruction required = new LlvmInstruction(
                Optional.empty(),
                LlvmType.VOID,
                "native-eh",
                List.of(),
                Optional.empty(),
                Optional.empty(),
                LlvmNativeUnwindSemantics.REQUIRED);
        LlvmModule module = module(
                "required",
                LlvmNativeUnwindSemantics.UNKNOWN,
                List.of(required));

        LlvmNativeUnwindProof proof = LlvmModuleEmissionPlan.create(module).proof();

        assertFalse(proof.omissionSafe());
        assertEquals(LlvmNativeUnwindProof.REQUIRED, proof.reasonCode());
    }

    @Test
    void oneCanonicalModuleProducesRetainedAndProvenOmissionVariants() {
        LlvmInstruction instruction = LlvmInstruction.rawProvenNoNativeUnwind(
                Optional.of("%sum"),
                "add i32 1, 2");
        LlvmModule module = module(
                "safe",
                LlvmNativeUnwindSemantics.PROVEN_ABSENT,
                List.of(instruction));
        LlvmModuleEmissionPlan plan = LlvmModuleEmissionPlan.create(module);
        LlvmTextEmitter emitter = new LlvmTextEmitter();

        String retained = emitter.emit(plan, LlvmUnwindEmissionMode.RETAIN);
        String omitted = emitter.emit(plan, LlvmUnwindEmissionMode.OMIT_PROVEN);

        assertSame(module, plan.module());
        assertTrue(plan.proof().omissionSafe());
        assertEquals(LlvmNativeUnwindProof.PROVEN_ABSENT, plan.proof().reasonCode());
        assertFalse(retained.contains(" nounwind"));
        assertTrue(omitted.contains("define internal hidden void @fixture() nounwind {"));
        assertEquals(retained, emitter.emit(module));
    }

    @Test
    void omissionVariantRejectsAnIncompleteProof() {
        LlvmModuleEmissionPlan plan = LlvmModuleEmissionPlan.create(
                new LlvmModule("unsafe", List.of(new LlvmFunction(
                        "fixture",
                        LlvmLinkage.INTERNAL,
                        LlvmVisibility.HIDDEN,
                        LlvmType.VOID,
                        List.of(),
                        List.of(block(List.of()))))));

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> new LlvmTextEmitter().emit(
                        plan,
                        LlvmUnwindEmissionMode.OMIT_PROVEN));

        assertTrue(failure.getMessage().contains(
                LlvmNativeUnwindProof.PROOF_INCOMPLETE));
    }

    private LlvmModule module(
            String identifier,
            LlvmNativeUnwindSemantics functionSemantics,
            List<LlvmInstruction> instructions) {
        return new LlvmModule(identifier, List.of(new LlvmFunction(
                "fixture",
                LlvmLinkage.INTERNAL,
                LlvmVisibility.HIDDEN,
                LlvmType.VOID,
                List.of(),
                List.of(block(instructions)),
                functionSemantics)));
    }

    private LlvmBasicBlock block(List<LlvmInstruction> instructions) {
        return new LlvmBasicBlock(
                "entry",
                instructions,
                new LlvmTerminator(LlvmType.VOID, Optional.empty()));
    }
}
