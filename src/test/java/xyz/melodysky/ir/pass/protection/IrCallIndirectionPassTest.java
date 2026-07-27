package xyz.melodysky.ir.pass.protection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import xyz.melodysky.ir.model.IrBlock;
import xyz.melodysky.ir.model.IrCallIndirectionMode;
import xyz.melodysky.ir.model.IrCallIndirectionRef;
import xyz.melodysky.ir.model.IrCallInvokeKind;
import xyz.melodysky.ir.model.IrCallSignature;
import xyz.melodysky.ir.model.IrClass;
import xyz.melodysky.ir.model.IrInstruction;
import xyz.melodysky.ir.model.IrMethod;
import xyz.melodysky.ir.model.IrOpcode;
import xyz.melodysky.ir.model.IrProgram;
import xyz.melodysky.ir.model.IrTerminator;
import xyz.melodysky.ir.model.IrType;
import xyz.melodysky.ir.model.IrValue;
import xyz.melodysky.ir.validate.IrMethodValidator;
import xyz.melodysky.ir.validate.IrValidationDiagnostics;

class IrCallIndirectionPassTest {
    @Test
    void rewritesOnlyProvenNativeDirectCallsDeterministically() {
        IrMethod target = staticTarget("pkg/Sample", "target", 7);
        IrMethod caller = staticCaller("pkg/Sample", "caller", target.methodKey());
        IrProgram program = program(caller, target);
        IrNativeDirectTargets nativeTargets =
                new IrNativeDirectTargets(List.of(target.methodKey(), caller.methodKey()));
        IrDirectCallFacts directCallFacts =
                bytecodeDirectFacts(caller, 0, IrCallInvokeKind.STATIC, target.methodKey());

        IrCallIndirectionResult first = new IrCallIndirectionPass().run(
                program,
                directCallFacts,
                nativeTargets,
                IrCallIndirectionMode.TABLE,
                41L,
                true);
        IrCallIndirectionResult repeated = new IrCallIndirectionPass().run(
                program,
                directCallFacts,
                nativeTargets,
                IrCallIndirectionMode.TABLE,
                41L,
                true);
        IrCallIndirectionResult differentSeed = new IrCallIndirectionPass().run(
                program,
                directCallFacts,
                nativeTargets,
                IrCallIndirectionMode.TABLE,
                42L,
                true);

        assertTrue(first.changed());
        assertEquals(IrCallIndirectionReasons.TABLE, first.reasonCode());
        assertEquals(
                first.plan().orElseThrow().planId(),
                repeated.plan().orElseThrow().planId());
        assertEquals(
                first.plan().orElseThrow().groups(),
                repeated.plan().orElseThrow().groups());
        assertEquals(
                first.plan().orElseThrow().sites(),
                repeated.plan().orElseThrow().sites());
        assertEquals(first.program(), repeated.program());
        assertNotEquals(
                first.plan().orElseThrow().planId(),
                differentSeed.plan().orElseThrow().planId());
        IrInstruction protectedCall = callInstruction(first.program(), caller.methodKey());
        assertEquals(IrOpcode.CALL_STATIC, protectedCall.opcode());
        assertEquals(Optional.of(target.methodKey()), protectedCall.symbol());
        assertTrue(protectedCall.callIndirection().isPresent());
        IrCallIndirectionRef reference = protectedCall.callIndirection().orElseThrow();
        assertFalse(reference.planId().contains("pkg"));
        assertFalse(reference.groupId().contains("target"));
        assertFalse(reference.entryId().contains("target"));
        assertTrue(first.diagnostics().isEmpty());
    }

    @Test
    void skipsCallerAndTargetWithoutNativePathProof() {
        IrMethod target = staticTarget("pkg/Sample", "target", 7);
        IrMethod caller = staticCaller("pkg/Sample", "caller", target.methodKey());
        IrProgram program = program(caller, target);
        IrDirectCallFacts directCallFacts =
                bytecodeDirectFacts(caller, 0, IrCallInvokeKind.STATIC, target.methodKey());

        IrCallIndirectionResult missingCaller = new IrCallIndirectionPass().run(
                program,
                directCallFacts,
                new IrNativeDirectTargets(List.of(target.methodKey())),
                IrCallIndirectionMode.TABLE,
                1L,
                true);
        IrCallIndirectionResult missingTarget = new IrCallIndirectionPass().run(
                program,
                directCallFacts,
                new IrNativeDirectTargets(List.of(caller.methodKey())),
                IrCallIndirectionMode.TABLE,
                1L,
                true);

        assertFalse(missingCaller.changed());
        assertEquals(
                IrCallIndirectionReasons.CALLER_NOT_NATIVE_LOWERED,
                missingCaller.skippedSites().get(0).reasonCode());
        assertFalse(missingTarget.changed());
        assertEquals(
                IrCallIndirectionReasons.TARGET_NOT_NATIVE_LOWERED,
                missingTarget.skippedSites().get(0).reasonCode());
    }

    @Test
    void virtualCallIsRejectedUntilTheBackendCanPreserveDispatchSemantics() {
        IrValue targetSelf = new IrValue("%target_self", IrType.REFERENCE);
        IrMethod target = new IrMethod(
                "pkg/Sample",
                "value",
                "()I",
                IrType.I32,
                List.of(targetSelf),
                List.of(new IrBlock(
                        "entry",
                        List.of(IrInstruction.constInt(new IrValue("%value", IrType.I32), 5)),
                        IrTerminator.returnValue(new IrValue("%value", IrType.I32)))));
        IrValue receiver = new IrValue("%receiver", IrType.REFERENCE);
        IrValue result = new IrValue("%result", IrType.I32);
        IrMethod caller = new IrMethod(
                "pkg/Caller",
                "call",
                "(Lpkg/Sample;)I",
                IrType.I32,
                List.of(receiver),
                List.of(new IrBlock(
                        "entry",
                        List.of(IrInstruction.call(
                                Optional.of(result),
                                IrOpcode.CALL_VIRTUAL,
                                List.of(receiver),
                                target.methodKey())),
                        IrTerminator.returnValue(result))));
        IrProgram program = new IrProgram(List.of(
                new IrClass(caller.owner(), List.of(caller)),
                new IrClass(target.owner(), List.of(target))));
        IrNativeDirectTargets nativeTargets =
                new IrNativeDirectTargets(List.of(caller.methodKey(), target.methodKey()));
        IrCallSiteId siteId = new IrCallSiteId(caller.methodKey(), "entry", 0);

        IrCallIndirectionResult unresolved = new IrCallIndirectionPass().run(
                program,
                new IrDirectCallFacts(List.of(IrDirectCallFact.unresolved(
                        siteId,
                        IrCallInvokeKind.VIRTUAL,
                        IrDirectCallResolutionKind.UNRESOLVED))),
                nativeTargets,
                IrCallIndirectionMode.DISPATCHER,
                8L,
                true);
        IrCallIndirectionResult resolved = new IrCallIndirectionPass().run(
                program,
                new IrDirectCallFacts(List.of(IrDirectCallFact.devirtualized(
                        siteId,
                        IrCallInvokeKind.VIRTUAL,
                        target.methodKey()))),
                nativeTargets,
                IrCallIndirectionMode.DISPATCHER,
                8L,
                true);

        assertFalse(unresolved.changed());
        assertEquals(
                IrCallIndirectionReasons.UNRESOLVED_TARGET,
                unresolved.skippedSites().get(0).reasonCode());
        assertFalse(resolved.changed());
        assertEquals(
                IrCallIndirectionReasons.BACKEND_UNSUPPORTED_SHAPE,
                resolved.skippedSites().get(0).reasonCode());
    }

    @Test
    void acceptsBytecodeDirectSpecialCallAndPreservesReceiverSemantics() {
        IrValue targetSelf = new IrValue("%target_self", IrType.REFERENCE);
        IrValue targetValue = new IrValue("%target_value", IrType.I32);
        IrMethod target = new IrMethod(
                "pkg/Sample",
                "privateValue",
                "()I",
                IrType.I32,
                List.of(targetSelf),
                List.of(new IrBlock(
                        "entry",
                        List.of(IrInstruction.constInt(targetValue, 11)),
                        IrTerminator.returnValue(targetValue))));
        IrValue callerSelf = new IrValue("%caller_self", IrType.REFERENCE);
        IrValue result = new IrValue("%result", IrType.I32);
        IrMethod caller = new IrMethod(
                "pkg/Sample",
                "callPrivate",
                "()I",
                IrType.I32,
                List.of(callerSelf),
                List.of(new IrBlock(
                        "entry",
                        List.of(IrInstruction.call(
                                Optional.of(result),
                                IrOpcode.CALL_SPECIAL,
                                List.of(callerSelf),
                                target.methodKey())),
                        IrTerminator.returnValue(result))));
        IrProgram program = program(caller, target);

        IrCallIndirectionResult resultPlan = new IrCallIndirectionPass().run(
                program,
                bytecodeDirectFacts(
                        caller,
                        0,
                        IrCallInvokeKind.SPECIAL,
                        target.methodKey()),
                new IrNativeDirectTargets(List.of(caller.methodKey(), target.methodKey())),
                IrCallIndirectionMode.TABLE,
                12L,
                true);

        assertTrue(resultPlan.changed());
        assertEquals(
                IrCallInvokeKind.SPECIAL,
                resultPlan.plan()
                        .orElseThrow()
                        .sites()
                        .get(0)
                        .reference()
                        .originalInvokeKind());
        assertTrue(resultPlan.plan()
                .orElseThrow()
                .sites()
                .get(0)
                .semantics()
                .receiverNullCheckRequired());
    }

    @Test
    void groupsTargetsByTypedIrSignature() {
        IrMethod intTarget = staticTarget("pkg/Sample", "intTarget", 3);
        IrValue longValue = new IrValue("%long_value", IrType.I64);
        IrMethod longTarget = new IrMethod(
                "pkg/Sample",
                "longTarget",
                "()J",
                IrType.I64,
                List.of(),
                List.of(new IrBlock(
                        "entry",
                        List.of(IrInstruction.constLong(longValue, 5L)),
                        IrTerminator.returnValue(longValue))));
        IrValue intResult = new IrValue("%int_result", IrType.I32);
        IrValue longResult = new IrValue("%long_result", IrType.I64);
        IrMethod caller = new IrMethod(
                "pkg/Sample",
                "both",
                "()J",
                IrType.I64,
                List.of(),
                List.of(new IrBlock(
                        "entry",
                        List.of(
                                IrInstruction.call(
                                        Optional.of(intResult),
                                        IrOpcode.CALL_STATIC,
                                        List.of(),
                                        intTarget.methodKey()),
                                IrInstruction.call(
                                        Optional.of(longResult),
                                        IrOpcode.CALL_STATIC,
                                        List.of(),
                                        longTarget.methodKey())),
                        IrTerminator.returnValue(longResult))));
        IrProgram program = new IrProgram(List.of(
                new IrClass("pkg/Sample", List.of(caller, longTarget, intTarget))));

        IrCallIndirectionResult result = new IrCallIndirectionPass().run(
                program,
                new IrDirectCallFacts(List.of(
                        IrDirectCallFact.bytecodeDirect(
                                new IrCallSiteId(caller.methodKey(), "entry", 0),
                                IrCallInvokeKind.STATIC,
                                intTarget.methodKey()),
                        IrDirectCallFact.bytecodeDirect(
                                new IrCallSiteId(caller.methodKey(), "entry", 1),
                                IrCallInvokeKind.STATIC,
                                longTarget.methodKey()))),
                new IrNativeDirectTargets(
                        List.of(caller.methodKey(), intTarget.methodKey(), longTarget.methodKey())),
                IrCallIndirectionMode.DISPATCHER,
                22L,
                true);

        assertTrue(result.changed());
        assertEquals(2, result.plan().orElseThrow().groups().size());
        assertEquals(
                List.of(IrType.I32, IrType.I64),
                result.plan().orElseThrow().groups().stream()
                        .map(group -> group.signature().returnType())
                        .sorted()
                        .toList());
    }

    @Test
    void unknownTargetIsSkippedEvenWhenSuppliedAsNativeEvidence() {
        String missingTarget = "pkg/Sample#missing!()I";
        IrMethod caller = staticCaller("pkg/Sample", "caller", missingTarget);
        IrProgram program =
                new IrProgram(List.of(new IrClass(caller.owner(), List.of(caller))));

        IrCallIndirectionResult result = new IrCallIndirectionPass().run(
                program,
                bytecodeDirectFacts(
                        caller,
                        0,
                        IrCallInvokeKind.STATIC,
                        missingTarget),
                new IrNativeDirectTargets(List.of(caller.methodKey(), missingTarget)),
                IrCallIndirectionMode.TABLE,
                2L,
                true);

        assertFalse(result.changed());
        assertEquals(
                IrCallIndirectionReasons.TARGET_NOT_IN_PROGRAM,
                result.skippedSites().get(0).reasonCode());
    }

    @Test
    void unavailableNativeTargetAndCrossOwnerStaticAreExplicitlySkipped() {
        IrMethod sameOwnerTarget = staticTarget("pkg/A", "sameOwner", 1);
        IrMethod unavailableCaller =
                staticCaller("pkg/A", "unavailable", sameOwnerTarget.methodKey());
        IrCallSiteId unavailableSite = new IrCallSiteId(
                unavailableCaller.methodKey(),
                "entry",
                0);
        IrProgram unavailableProgram =
                program(unavailableCaller, sameOwnerTarget);
        IrNativeDirectTargets unavailableNativeTargets =
                new IrNativeDirectTargets(List.of(
                        unavailableCaller.methodKey(),
                        sameOwnerTarget.methodKey()));

        IrCallIndirectionResult unavailable = new IrCallIndirectionPass().run(
                unavailableProgram,
                new IrDirectCallFacts(List.of(IrDirectCallFact.bytecodeDirect(
                                unavailableSite,
                                IrCallInvokeKind.STATIC,
                                sameOwnerTarget.methodKey())
                        .withoutNativeTarget())),
                unavailableNativeTargets,
                IrCallIndirectionMode.TABLE,
                3L,
                true);

        IrMethod otherOwnerTarget = staticTarget("pkg/B", "target", 2);
        IrMethod crossOwnerCaller = staticCaller("pkg/A", "cross", otherOwnerTarget.methodKey());
        IrProgram crossOwnerProgram = new IrProgram(List.of(
                new IrClass(crossOwnerCaller.owner(), List.of(crossOwnerCaller)),
                new IrClass(otherOwnerTarget.owner(), List.of(otherOwnerTarget))));
        IrCallIndirectionResult missingGuard = new IrCallIndirectionPass().run(
                crossOwnerProgram,
                bytecodeDirectFacts(
                        crossOwnerCaller,
                        0,
                        IrCallInvokeKind.STATIC,
                        otherOwnerTarget.methodKey()),
                new IrNativeDirectTargets(
                        List.of(crossOwnerCaller.methodKey(), otherOwnerTarget.methodKey())),
                IrCallIndirectionMode.TABLE,
                3L,
                true);

        assertEquals(
                IrCallIndirectionReasons.NATIVE_TARGET_UNAVAILABLE,
                unavailable.skippedSites().get(0).reasonCode());
        assertEquals(
                IrCallIndirectionReasons.BACKEND_UNSUPPORTED_SHAPE,
                missingGuard.skippedSites().get(0).reasonCode());
    }

    @Test
    void crossOwnerSpecialIsRejectedBecauseItsTargetIsOutsideTheCallerModule() {
        IrValue targetSelf = new IrValue("%target_self", IrType.REFERENCE);
        IrValue targetValue = new IrValue("%target_value", IrType.I32);
        IrMethod target = new IrMethod(
                "pkg/Base",
                "value",
                "()I",
                IrType.I32,
                List.of(targetSelf),
                List.of(new IrBlock(
                        "entry",
                        List.of(IrInstruction.constInt(targetValue, 13)),
                        IrTerminator.returnValue(targetValue))));
        IrValue callerSelf = new IrValue("%caller_self", IrType.REFERENCE);
        IrValue resultValue = new IrValue("%result", IrType.I32);
        IrMethod caller = new IrMethod(
                "pkg/Child",
                "callBase",
                "()I",
                IrType.I32,
                List.of(callerSelf),
                List.of(new IrBlock(
                        "entry",
                        List.of(IrInstruction.call(
                                Optional.of(resultValue),
                                IrOpcode.CALL_SPECIAL,
                                List.of(callerSelf),
                                target.methodKey())),
                        IrTerminator.returnValue(resultValue))));
        IrProgram program = new IrProgram(List.of(
                new IrClass(caller.owner(), List.of(caller)),
                new IrClass(target.owner(), List.of(target))));

        IrCallIndirectionResult result = new IrCallIndirectionPass().run(
                program,
                bytecodeDirectFacts(
                        caller,
                        0,
                        IrCallInvokeKind.SPECIAL,
                        target.methodKey()),
                new IrNativeDirectTargets(List.of(caller.methodKey(), target.methodKey())),
                IrCallIndirectionMode.TABLE,
                15L,
                true);

        assertFalse(result.changed());
        assertEquals(
                IrCallIndirectionReasons.BACKEND_UNSUPPORTED_SHAPE,
                result.skippedSites().get(0).reasonCode());
    }

    @Test
    void disabledPassPreservesProgramIdentity() {
        IrMethod target = staticTarget("pkg/Sample", "target", 7);
        IrMethod caller = staticCaller("pkg/Sample", "caller", target.methodKey());
        IrProgram program = program(caller, target);

        IrCallIndirectionResult result = new IrCallIndirectionPass().run(
                program,
                IrDirectCallFacts.empty(),
                new IrNativeDirectTargets(List.of(caller.methodKey(), target.methodKey())),
                IrCallIndirectionMode.TABLE,
                4L,
                false);

        assertSame(program, result.program());
        assertEquals(IrCallIndirectionReasons.DISABLED, result.reasonCode());
        assertTrue(result.plan().isEmpty());
    }

    @Test
    void methodAndPlanValidatorsRejectForgedOrMissingMetadata() {
        IrValue value = new IrValue("%value", IrType.I32);
        IrCallIndirectionRef forgedReference = new IrCallIndirectionRef(
                "ircp_forged",
                "ircg_forged",
                "irce_forged",
                IrCallIndirectionMode.TABLE,
                new IrCallSignature(IrType.I32, List.of()),
                IrCallInvokeKind.STATIC);
        IrInstruction forgedInstruction =
                IrInstruction.constInt(value, 1).withCallIndirection(forgedReference);
        IrMethod forgedMethod = new IrMethod(
                "pkg/Sample",
                "forged",
                "()I",
                IrType.I32,
                List.of(),
                List.of(new IrBlock(
                        "entry",
                        List.of(forgedInstruction),
                        IrTerminator.returnValue(value))));

        assertTrue(new IrMethodValidator().validate(forgedMethod).stream()
                .anyMatch(diagnostic -> diagnostic.code().equals(
                        IrValidationDiagnostics.IR_CALL_INDIRECTION_MISMATCH)));

        IrMethod target = staticTarget("pkg/Sample", "target", 7);
        IrMethod caller = staticCaller("pkg/Sample", "caller", target.methodKey());
        IrProgram original = program(caller, target);
        IrNativeDirectTargets nativeTargets =
                new IrNativeDirectTargets(List.of(caller.methodKey(), target.methodKey()));
        IrCallIndirectionResult protectedResult = new IrCallIndirectionPass().run(
                original,
                bytecodeDirectFacts(
                        caller,
                        0,
                        IrCallInvokeKind.STATIC,
                        target.methodKey()),
                nativeTargets,
                IrCallIndirectionMode.TABLE,
                9L,
                true);

        assertTrue(new IrCallIndirectionValidator()
                .validate(original, protectedResult.plan().orElseThrow(), nativeTargets)
                .stream()
                .anyMatch(diagnostic -> diagnostic.message()
                        .contains("does not match its plan site")));
    }

    private IrMethod staticTarget(String owner, String name, int constant) {
        IrValue value = new IrValue("%" + name + "_value", IrType.I32);
        return new IrMethod(
                owner,
                name,
                "()I",
                IrType.I32,
                List.of(),
                List.of(new IrBlock(
                        "entry",
                        List.of(IrInstruction.constInt(value, constant)),
                        IrTerminator.returnValue(value))));
    }

    private IrMethod staticCaller(String owner, String name, String targetMethodKey) {
        IrValue result = new IrValue("%" + name + "_result", IrType.I32);
        return new IrMethod(
                owner,
                name,
                "()I",
                IrType.I32,
                List.of(),
                List.of(new IrBlock(
                        "entry",
                        List.of(IrInstruction.call(
                                Optional.of(result),
                                IrOpcode.CALL_STATIC,
                                List.of(),
                                targetMethodKey)),
                        IrTerminator.returnValue(result))));
    }

    private IrProgram program(IrMethod first, IrMethod second) {
        return new IrProgram(List.of(new IrClass(first.owner(), List.of(first, second))));
    }

    private IrInstruction callInstruction(IrProgram program, String callerMethodKey) {
        return program.classes().stream()
                .flatMap(irClass -> irClass.methods().stream())
                .filter(method -> method.methodKey().equals(callerMethodKey))
                .findFirst()
                .orElseThrow()
                .blocks()
                .get(0)
                .instructions()
                .get(0);
    }

    private IrDirectCallFacts bytecodeDirectFacts(
            IrMethod caller,
            int instructionIndex,
            IrCallInvokeKind invokeKind,
            String targetMethodKey) {
        return new IrDirectCallFacts(List.of(IrDirectCallFact.bytecodeDirect(
                new IrCallSiteId(caller.methodKey(), "entry", instructionIndex),
                invokeKind,
                targetMethodKey)));
    }
}
