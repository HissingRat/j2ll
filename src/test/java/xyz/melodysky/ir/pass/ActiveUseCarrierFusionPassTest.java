package xyz.melodysky.ir.pass;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import xyz.melodysky.backend.llvm.LlvmModuleLowerer;
import xyz.melodysky.backend.llvm.model.LlvmTextEmitter;
import xyz.melodysky.frontend.cfg.MethodCfgBuilder;
import xyz.melodysky.frontend.classfile.AsmClassParser;
import xyz.melodysky.frontend.classfile.ClassFileEntry;
import xyz.melodysky.frontend.classfile.ParsedMethod;
import xyz.melodysky.ir.model.IrBlock;
import xyz.melodysky.ir.model.IrClass;
import xyz.melodysky.ir.model.IrInstruction;
import xyz.melodysky.ir.model.IrMethod;
import xyz.melodysky.ir.model.IrOpcode;
import xyz.melodysky.ir.model.IrType;
import xyz.melodysky.ir.model.IrValue;
import xyz.melodysky.ir.ssa.BytecodeToSsaLowerer;
import xyz.melodysky.ir.validate.IrMethodValidator;
import xyz.melodysky.testsupport.AsmFixtureBuilder;

class ActiveUseCarrierFusionPassTest {
    @Test
    void fusesExactJvmBackedStaticFieldAndCallCarriers() {
        assertFused(
                lower(
                        AsmFixtureBuilder.classWithExternalStaticFieldRead(
                                "pkg/Read",
                                "pkg/Fields"),
                        "getValue"),
                IrOpcode.GET_STATIC);
        assertFused(
                lower(
                        AsmFixtureBuilder.classWithExternalStaticFieldWrite(
                                "pkg/Write",
                                "pkg/Fields"),
                        "setValue"),
                IrOpcode.PUT_STATIC);
        assertFused(
                lower(
                        AsmFixtureBuilder.classWithExternalStaticCall(
                                "pkg/Call",
                                "pkg/Calls"),
                        "call"),
                IrOpcode.CALL_STATIC);
    }

    @Test
    void retainsAllocationCarrierBecauseAllocObjectDoesNotInitializeClass() {
        IrMethod input = lower(
                AsmFixtureBuilder.classWithAllocation(
                        "pkg/Allocate",
                        "pkg/Value"),
                "make");

        IrMethod result = new ActiveUseCarrierFusionPass(Set.of())
                .run(input, PassContext.empty());

        assertSame(input, result);
        assertTrue(hasOpcode(result, IrOpcode.CLASS_OBJECT));
        assertTrue(hasOpcode(result, IrOpcode.CLASS_INIT_GUARD));
        assertTrue(hasOpcode(result, IrOpcode.NEW_OBJECT));
        assertFalse(hasOpcode(result, IrOpcode.CLASS_INIT_ACTIVE_USE));
    }

    @Test
    void retainsStaticCallCarrierWhenTargetCouldBecomeDirectNative() {
        IrMethod input = lower(
                AsmFixtureBuilder.classWithExternalStaticCall(
                        "pkg/Call",
                        "pkg/Calls"),
                "call");
        String target = input.blocks().stream()
                .flatMap(block -> block.instructions().stream())
                .filter(instruction -> instruction.opcode() == IrOpcode.CALL_STATIC)
                .findFirst()
                .flatMap(IrInstruction::symbol)
                .orElseThrow();

        IrMethod result = new ActiveUseCarrierFusionPass(Set.of(target))
                .run(input, PassContext.empty());

        assertSame(input, result);
        assertTrue(hasOpcode(result, IrOpcode.CLASS_OBJECT));
        assertTrue(hasOpcode(result, IrOpcode.CLASS_INIT_GUARD));
        assertFalse(hasOpcode(result, IrOpcode.CLASS_INIT_ACTIVE_USE));
    }

    @Test
    void rejectsOwnerMismatchExtraCarrierUseAndExceptionBoundaryMismatch() {
        IrMethod input = lower(
                AsmFixtureBuilder.classWithExternalStaticFieldRead(
                        "pkg/Read",
                        "pkg/Fields"),
                "getValue");

        IrMethod wrongOwner = replaceActiveUse(input, instruction ->
                IrInstruction.fieldGet(
                                instruction.result().orElseThrow(),
                                IrOpcode.GET_STATIC,
                                List.of(),
                                "pkg/Other#VALUE!I")
                        .withExceptionSites(instruction.exceptionSites()));
        assertSame(
                wrongOwner,
                new ActiveUseCarrierFusionPass(Set.of()).run(
                        wrongOwner,
                        PassContext.empty()));

        IrMethod extraUse = appendClassObjectUse(input);
        assertSame(
                extraUse,
                new ActiveUseCarrierFusionPass(Set.of()).run(
                        extraUse,
                        PassContext.empty()));

        IrMethod mismatchedException = replaceActiveUse(input, instruction ->
                new IrInstruction(
                        instruction.result(),
                        instruction.opcode(),
                        instruction.operands(),
                        instruction.intLiteral(),
                        instruction.longLiteral(),
                        instruction.floatLiteral(),
                        instruction.doubleLiteral(),
                        instruction.symbol(),
                        List.of(),
                        instruction.callIndirection()));
        assertSame(
                mismatchedException,
                new ActiveUseCarrierFusionPass(Set.of()).run(
                        mismatchedException,
                        PassContext.empty()));
    }

    @Test
    void fusedMarkerValidatesAndLowersToAcquireWithoutCarrierHelpers() {
        IrMethod input = lower(
                AsmFixtureBuilder.classWithExternalStaticFieldRead(
                        "pkg/Read",
                        "pkg/Fields"),
                "getValue");
        var result = new OptimizationPipeline(List.of(
                        new ActiveUseCarrierFusionPass(Set.of())))
                .run(input, PassContext.empty());
        IrMethod fused = result.artifact().orElseThrow();
        String llvm = new LlvmTextEmitter().emit(
                new LlvmModuleLowerer().lowerClass(new IrClass(
                        fused.owner(),
                        List.of(fused))));

        assertTrue(result.diagnostics().isEmpty(), result.diagnostics().toString());
        assertTrue(llvm.contains("fence acquire"), llvm);
        assertFalse(llvm.contains("localizedClassObject"), llvm);
        assertFalse(llvm.contains("call void @j2ll_rt_class_init_guard"), llvm);
        assertTrue(llvm.contains("localizedFieldBinding"), llvm);
        int activeCall = llvm.indexOf(" = call i32 @j2ll_h_");
        int pendingCheck = llvm.indexOf("call ptr @j2ll_rt_pending_exception");
        int acquire = llvm.indexOf("fence acquire");
        assertTrue(activeCall >= 0 && activeCall < pendingCheck, llvm);
        assertTrue(pendingCheck < acquire, llvm);
    }

    @Test
    void validatorRejectsMalformedFusedMarker() {
        IrMethod method = new IrMethod(
                "pkg/Bad",
                "run",
                "()V",
                IrType.VOID,
                List.of(),
                List.of(new IrBlock(
                        "entry",
                        List.of(IrInstruction.operation(
                                Optional.empty(),
                                IrOpcode.CLASS_INIT_ACTIVE_USE,
                                List.of(),
                                "not-a-class")),
                        xyz.melodysky.ir.model.IrTerminator.returnVoid())));

        assertFalse(new IrMethodValidator().validate(method).isEmpty());
    }

    private void assertFused(IrMethod input, IrOpcode activeUse) {
        IrMethod result = new ActiveUseCarrierFusionPass(Set.of())
                .run(input, PassContext.empty());

        assertFalse(hasOpcode(result, IrOpcode.CONST_LONG));
        assertFalse(hasOpcode(result, IrOpcode.CLASS_OBJECT));
        assertFalse(hasOpcode(result, IrOpcode.CLASS_INIT_GUARD));
        assertFalse(hasOpcode(result, IrOpcode.CLASS_INIT_HAPPENS_BEFORE));
        assertTrue(hasOpcode(result, IrOpcode.CLASS_INIT_ACTIVE_USE));
        assertTrue(hasOpcode(result, activeUse));
        List<IrInstruction> instructions = result.blocks().get(0).instructions();
        int marker = indexOf(instructions, IrOpcode.CLASS_INIT_ACTIVE_USE);
        assertEquals(activeUse, instructions.get(marker - 1).opcode());
    }

    private IrMethod replaceActiveUse(
            IrMethod method,
            java.util.function.UnaryOperator<IrInstruction> replacement) {
        IrBlock block = method.blocks().get(0);
        ArrayList<IrInstruction> instructions = new ArrayList<>(block.instructions());
        int active = firstActiveUse(instructions);
        instructions.set(active, replacement.apply(instructions.get(active)));
        return withInstructions(method, instructions);
    }

    private IrMethod appendClassObjectUse(IrMethod method) {
        IrBlock block = method.blocks().get(0);
        ArrayList<IrInstruction> instructions = new ArrayList<>(block.instructions());
        IrValue classObject = instructions.stream()
                .filter(instruction -> instruction.opcode() == IrOpcode.CLASS_OBJECT)
                .findFirst()
                .flatMap(IrInstruction::result)
                .orElseThrow();
        IrValue compared = new IrValue("%extra_class_use", IrType.I1);
        instructions.add(firstActiveUse(instructions) + 1, IrInstruction.binary(
                compared,
                IrOpcode.CMP_EQ_REF,
                classObject,
                classObject));
        return withInstructions(method, instructions);
    }

    private IrMethod withInstructions(
            IrMethod method,
            List<IrInstruction> instructions) {
        IrBlock source = method.blocks().get(0);
        IrBlock block = new IrBlock(
                source.name(),
                source.parameters(),
                source.exceptionCatchTypes(),
                source.exceptionEdges(),
                instructions,
                source.terminator());
        return new IrMethod(
                method.owner(),
                method.name(),
                method.descriptor(),
                method.returnType(),
                method.parameters(),
                List.of(block));
    }

    private int firstActiveUse(List<IrInstruction> instructions) {
        for (int index = 0; index < instructions.size(); index++) {
            IrOpcode opcode = instructions.get(index).opcode();
            if (opcode == IrOpcode.GET_STATIC
                    || opcode == IrOpcode.PUT_STATIC
                    || opcode == IrOpcode.CALL_STATIC
                    || opcode == IrOpcode.NEW_OBJECT) {
                return index;
            }
        }
        throw new IllegalArgumentException("fixture has no active use");
    }

    private int indexOf(List<IrInstruction> instructions, IrOpcode opcode) {
        for (int index = 0; index < instructions.size(); index++) {
            if (instructions.get(index).opcode() == opcode) {
                return index;
            }
        }
        return -1;
    }

    private boolean hasOpcode(IrMethod method, IrOpcode opcode) {
        return method.blocks().stream()
                .flatMap(block -> block.instructions().stream())
                .anyMatch(instruction -> instruction.opcode() == opcode);
    }

    private IrMethod lower(byte[] classBytes, String methodName) {
        ParsedMethod method = new AsmClassParser()
                .parse(new ClassFileEntry("fixture.class", classBytes, "fixture"))
                .artifact()
                .orElseThrow()
                .methods().stream()
                .filter(candidate -> candidate.name().equals(methodName))
                .findFirst()
                .orElseThrow();
        return new BytecodeToSsaLowerer()
                .lower(new MethodCfgBuilder().build(method).artifact().orElseThrow())
                .artifact()
                .orElseThrow()
                .irMethod()
                .orElseThrow();
    }
}
