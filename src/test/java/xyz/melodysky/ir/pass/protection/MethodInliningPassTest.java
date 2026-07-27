package xyz.melodysky.ir.pass.protection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import xyz.melodysky.ir.model.IrBlock;
import xyz.melodysky.ir.model.IrClass;
import xyz.melodysky.ir.model.IrInstruction;
import xyz.melodysky.ir.model.IrMethod;
import xyz.melodysky.ir.model.IrOpcode;
import xyz.melodysky.ir.model.IrProgram;
import xyz.melodysky.ir.model.IrTerminator;
import xyz.melodysky.ir.model.IrTerminatorKind;
import xyz.melodysky.ir.model.IrType;
import xyz.melodysky.ir.model.IrValue;
import xyz.melodysky.ir.validate.IrMethodValidator;

class MethodInliningPassTest {
    @Test
    void inlinesStraightLineStaticCalleeAndRemapsValues() {
        IrValue callerInput = value("%v0", IrType.I32);
        IrValue callResult = value("%call", IrType.I32);
        IrValue callerOne = value("%one", IrType.I32);
        IrValue callerResult = value("%result", IrType.I32);
        IrMethod caller = method(
                "caller",
                "(I)I",
                IrType.I32,
                List.of(callerInput),
                new IrBlock(
                        "entry",
                        List.of(
                                IrInstruction.call(
                                        Optional.of(callResult),
                                        IrOpcode.CALL_STATIC,
                                        List.of(callerInput),
                                        key("callee", "(I)I")),
                                IrInstruction.constInt(callerOne, 1),
                                IrInstruction.binary(callerResult, IrOpcode.ADD_I32, callResult, callerOne)),
                        IrTerminator.returnValue(callerResult)));

        IrValue calleeInput = value("%v0", IrType.I32);
        IrValue calleeOne = value("%one", IrType.I32);
        IrValue calleeResult = value("%result", IrType.I32);
        IrMethod callee = method(
                "callee",
                "(I)I",
                IrType.I32,
                List.of(calleeInput),
                new IrBlock(
                        "entry",
                        List.of(
                                IrInstruction.constInt(calleeOne, 1),
                                IrInstruction.binary(calleeResult, IrOpcode.ADD_I32, calleeInput, calleeOne)),
                        IrTerminator.returnValue(calleeResult)));

        MethodInliningResult result = run(caller, callee, staticCandidate(caller, callee));
        IrMethod transformed = find(result.program(), caller.methodKey());

        assertEquals(1, result.inlinedCount());
        assertFalse(hasOpcode(transformed, IrOpcode.CALL_STATIC));
        assertEquals(callee, find(result.program(), callee.methodKey()));
        assertTrue(new IrMethodValidator().validate(transformed).isEmpty());

        IrBlock continuation = transformed.blocks().stream()
                .filter(block -> block.name().contains("_continue"))
                .findFirst()
                .orElseThrow();
        assertEquals(1, continuation.parameters().size());
        IrInstruction suffixAdd = continuation.instructions().stream()
                .filter(instruction -> instruction.opcode() == IrOpcode.ADD_I32)
                .findFirst()
                .orElseThrow();
        assertEquals(continuation.parameters().get(0), suffixAdd.operands().get(0));

        Set<String> definitions = new HashSet<>();
        transformed.parameters().forEach(parameter -> assertTrue(definitions.add(parameter.name())));
        transformed.blocks().forEach(block -> {
            block.parameters().forEach(parameter -> assertTrue(definitions.add(parameter.name())));
            block.instructions().stream()
                    .flatMap(instruction -> instruction.result().stream())
                    .forEach(value -> assertTrue(definitions.add(value.name())));
        });
    }

    @Test
    void mergesMultipleCalleeReturnsThroughContinuationParameter() {
        IrValue callerInput = value("%input", IrType.I32);
        IrValue callResult = value("%call", IrType.I32);
        IrMethod caller = method(
                "caller",
                "(I)I",
                IrType.I32,
                List.of(callerInput),
                new IrBlock(
                        "entry",
                        List.of(IrInstruction.call(
                                Optional.of(callResult),
                                IrOpcode.CALL_STATIC,
                                List.of(callerInput),
                                key("choose", "(I)I"))),
                        IrTerminator.returnValue(callResult)));

        IrValue input = value("%input", IrType.I32);
        IrValue zero = value("%zero", IrType.I32);
        IrValue condition = value("%condition", IrType.I1);
        IrValue positive = value("%positive", IrType.I32);
        IrValue negative = value("%negative", IrType.I32);
        IrMethod callee = method(
                "choose",
                "(I)I",
                IrType.I32,
                List.of(input),
                new IrBlock(
                        "entry",
                        List.of(
                                IrInstruction.constInt(zero, 0),
                                IrInstruction.binary(condition, IrOpcode.CMP_GE_I32, input, zero)),
                        IrTerminator.branch(condition, "positive", "negative")),
                new IrBlock(
                        "positive",
                        List.of(IrInstruction.constInt(positive, 7)),
                        IrTerminator.returnValue(positive)),
                new IrBlock(
                        "negative",
                        List.of(IrInstruction.constInt(negative, -7)),
                        IrTerminator.returnValue(negative)));

        IrMethod transformed = find(
                run(caller, callee, staticCandidate(caller, callee)).program(),
                caller.methodKey());
        IrBlock continuation = transformed.blocks().stream()
                .filter(block -> block.name().contains("_continue"))
                .findFirst()
                .orElseThrow();
        long returnEdges = transformed.blocks().stream()
                .filter(block -> block.terminator().kind() == IrTerminatorKind.GOTO)
                .filter(block -> block.terminator().target().filter(continuation.name()::equals).isPresent())
                .filter(block -> block.terminator().targetArguments().size() == 1)
                .count();

        assertEquals(2, returnEdges);
        assertEquals(continuation.parameters().get(0), continuation.terminator().value().orElseThrow());
        assertTrue(new IrMethodValidator().validate(transformed).isEmpty());
    }

    @Test
    void remapsCalleeBlockParametersAndIncomingArguments() {
        IrValue callerInput = value("%callerInput", IrType.I32);
        IrValue callResult = value("%call", IrType.I32);
        IrMethod caller = method(
                "caller",
                "(I)I",
                IrType.I32,
                List.of(callerInput),
                new IrBlock(
                        "entry",
                        List.of(IrInstruction.call(
                                Optional.of(callResult),
                                IrOpcode.CALL_STATIC,
                                List.of(callerInput),
                                key("merge", "(I)I"))),
                        IrTerminator.returnValue(callResult)));

        IrValue input = value("%input", IrType.I32);
        IrValue zero = value("%zero", IrType.I32);
        IrValue condition = value("%condition", IrType.I1);
        IrValue merged = value("%merged", IrType.I32);
        IrMethod callee = method(
                "merge",
                "(I)I",
                IrType.I32,
                List.of(input),
                new IrBlock(
                        "entry",
                        List.of(
                                IrInstruction.constInt(zero, 0),
                                IrInstruction.binary(condition, IrOpcode.CMP_GE_I32, input, zero)),
                        IrTerminator.branch(
                                condition,
                                "join",
                                List.of(input),
                                "join",
                                List.of(zero))),
                new IrBlock(
                        "join",
                        List.of(merged),
                        List.of(),
                        IrTerminator.returnValue(merged)));

        IrMethod transformed = find(
                run(caller, callee, staticCandidate(caller, callee)).program(),
                caller.methodKey());
        IrBlock clonedJoin = transformed.blocks().stream()
                .filter(block -> block.parameters().size() == 1)
                .filter(block -> !block.name().contains("_continue"))
                .findFirst()
                .orElseThrow();
        IrBlock clonedEntry = transformed.blocks().stream()
                .filter(block -> block.terminator().kind() == IrTerminatorKind.BRANCH)
                .findFirst()
                .orElseThrow();

        assertEquals(clonedJoin.name(), clonedEntry.terminator().trueTarget().orElseThrow());
        assertEquals(clonedJoin.name(), clonedEntry.terminator().falseTarget().orElseThrow());
        assertEquals(callerInput, clonedEntry.terminator().trueTargetArguments().get(0));
        assertNotEquals(zero, clonedEntry.terminator().falseTargetArguments().get(0));
        assertTrue(new IrMethodValidator().validate(transformed).isEmpty());
    }

    @Test
    void supportsPrivateSelfSpecialCallButRejectsArbitraryReceiver() {
        IrValue self = value("%self", IrType.REFERENCE);
        IrValue other = value("%other", IrType.REFERENCE);
        IrValue argument = value("%argument", IrType.I32);
        IrValue callResult = value("%call", IrType.I32);
        IrMethod callee = method(
                "privateHelper",
                "(I)I",
                IrType.I32,
                List.of(value("%calleeSelf", IrType.REFERENCE), value("%calleeArg", IrType.I32)),
                new IrBlock("entry", List.of(), IrTerminator.returnValue(value("%calleeArg", IrType.I32))));
        IrMethod selfCaller = method(
                "selfCaller",
                "(I)I",
                IrType.I32,
                List.of(self, argument),
                new IrBlock(
                        "entry",
                        List.of(IrInstruction.call(
                                Optional.of(callResult),
                                IrOpcode.CALL_SPECIAL,
                                List.of(self, argument),
                                callee.methodKey())),
                        IrTerminator.returnValue(callResult)));
        MethodInliningCandidate selfCandidate = privateSelfCandidate(selfCaller, callee);

        MethodInliningResult selfResult = run(selfCaller, callee, selfCandidate);

        assertEquals(1, selfResult.inlinedCount());
        assertFalse(hasOpcode(find(selfResult.program(), selfCaller.methodKey()), IrOpcode.CALL_SPECIAL));

        IrMethod otherCaller = method(
                "otherCaller",
                "(Lpkg/Owner;I)I",
                IrType.I32,
                List.of(self, other, argument),
                new IrBlock(
                        "entry",
                        List.of(IrInstruction.call(
                                Optional.of(callResult),
                                IrOpcode.CALL_SPECIAL,
                                List.of(other, argument),
                                callee.methodKey())),
                        IrTerminator.returnValue(callResult)));
        MethodInliningResult otherResult = run(otherCaller, callee, privateSelfCandidate(otherCaller, callee));

        assertTrue(hasOpcode(find(otherResult.program(), otherCaller.methodKey()), IrOpcode.CALL_SPECIAL));
        assertReason(otherResult, MethodInliningReason.UNSAFE_CALL_SITE);
    }

    @Test
    void rejectsRecursiveEdgeBeforeInspectingCallSensitiveBody() {
        IrValue input = value("%input", IrType.I32);
        IrValue fromB = value("%fromB", IrType.I32);
        IrMethod caller = method(
                "a",
                "(I)I",
                IrType.I32,
                List.of(input),
                new IrBlock(
                        "entry",
                        List.of(IrInstruction.call(
                                Optional.of(fromB),
                                IrOpcode.CALL_STATIC,
                                List.of(input),
                                key("b", "(I)I"))),
                        IrTerminator.returnValue(fromB)));
        IrValue fromA = value("%fromA", IrType.I32);
        IrMethod callee = method(
                "b",
                "(I)I",
                IrType.I32,
                List.of(input),
                new IrBlock(
                        "entry",
                        List.of(IrInstruction.call(
                                Optional.of(fromA),
                                IrOpcode.CALL_STATIC,
                                List.of(input),
                                caller.methodKey())),
                        IrTerminator.returnValue(fromA)));

        MethodInliningResult result = run(caller, callee, staticCandidate(caller, callee));

        assertEquals(0, result.inlinedCount());
        assertEquals(caller, find(result.program(), caller.methodKey()));
        assertReason(result, MethodInliningReason.RECURSIVE);
    }

    @Test
    void rejectsExceptionMonitorJmmAndCallSensitiveCallees() {
        IrMethod exceptionCallee = binaryCallee("divide", IrOpcode.DIV_I32);
        assertRejected(exceptionCallee, MethodInliningReason.EXCEPTION_SENSITIVE);

        IrValue monitor = value("%monitor", IrType.REFERENCE);
        IrMethod monitorCallee = method(
                "monitor",
                "(Ljava/lang/Object;)V",
                IrType.VOID,
                List.of(monitor),
                new IrBlock(
                        "entry",
                        List.of(IrInstruction.operation(
                                Optional.empty(),
                                IrOpcode.MONITOR_ENTER,
                                List.of(monitor),
                                "monitor")),
                        IrTerminator.returnVoid()));
        assertRejected(monitorCallee, MethodInliningReason.MONITOR_JMM_SENSITIVE);

        IrValue value = value("%value", IrType.I32);
        IrMethod jmmCallee = method(
                "jmm",
                "(I)I",
                IrType.I32,
                List.of(value),
                new IrBlock(
                        "entry",
                        List.of(IrInstruction.operation(
                                Optional.empty(),
                                IrOpcode.VOLATILE_READ_BARRIER,
                                List.of(),
                                "volatile")),
                        IrTerminator.returnValue(value)));
        assertRejected(jmmCallee, MethodInliningReason.MONITOR_JMM_SENSITIVE);

        IrMethod callCallee = method(
                "call",
                "()V",
                IrType.VOID,
                List.of(),
                new IrBlock(
                        "entry",
                        List.of(IrInstruction.call(
                                Optional.empty(),
                                IrOpcode.CALL_RUNTIME_HELPER,
                                List.of(),
                                "j2ll_rt_helper")),
                        IrTerminator.returnVoid()));
        assertRejected(
                callCallee,
                MethodInliningReason.CALL_OR_FIELD_SENSITIVE);
    }

    @Test
    void rejectsAnalysisFactsThatDoNotProveSafeNativeSingleTarget() {
        IrMethod callee = identityCallee("callee");
        IrMethod caller = staticCaller("caller", callee);
        MethodInliningCandidate baseline = staticCandidate(caller, callee);

        assertReason(
                run(caller, callee, copy(baseline, false, true, true, false)),
                MethodInliningReason.NOT_SINGLE_TARGET);
        assertReason(
                run(caller, callee, copy(baseline, true, false, true, false)),
                MethodInliningReason.NON_NATIVE_PATH);
        assertReason(
                run(caller, callee, copy(baseline, true, true, false, false)),
                MethodInliningReason.NON_NATIVE_PATH);
        assertReason(
                run(caller, callee, copy(baseline, true, true, true, true)),
                MethodInliningReason.REFLECTION_SENSITIVE);
    }

    @Test
    void rejectsInvalidAndOversizedCallee() {
        IrValue undefined = value("%undefined", IrType.I32);
        IrMethod invalid = method(
                "invalid",
                "()I",
                IrType.I32,
                List.of(),
                new IrBlock("entry", List.of(), IrTerminator.returnValue(undefined)));
        assertRejected(invalid, MethodInliningReason.VALIDATION_FAILED);

        ArrayList<IrInstruction> instructions = new ArrayList<>();
        IrValue previous = value("%p0", IrType.I32);
        List<IrValue> parameters = List.of(previous);
        for (int index = 0; index < 4; index++) {
            IrValue one = value("%one" + index, IrType.I32);
            IrValue next = value("%next" + index, IrType.I32);
            instructions.add(IrInstruction.constInt(one, 1));
            instructions.add(IrInstruction.binary(next, IrOpcode.ADD_I32, previous, one));
            previous = next;
        }
        IrMethod large = method(
                "large",
                "(I)I",
                IrType.I32,
                parameters,
                new IrBlock("entry", instructions, IrTerminator.returnValue(previous)));
        IrMethod caller = staticCaller("largeCaller", large);
        MethodInliningOptions options = new MethodInliningOptions(true, 7, 4, 8);
        MethodInliningResult largeResult = new MethodInliningPass().run(
                program(caller, large),
                new MethodInliningPlan(List.of(staticCandidate(caller, large))),
                options);

        assertReason(largeResult, MethodInliningReason.CALLEE_TOO_LARGE);
    }

    @Test
    void disabledIsNoOpAndSeedControlsDeterministicNames() {
        IrMethod callee = identityCallee("callee");
        IrMethod caller = staticCaller("caller", callee);
        MethodInliningPlan plan = new MethodInliningPlan(List.of(staticCandidate(caller, callee)));
        IrProgram program = program(caller, callee);

        MethodInliningResult disabled = new MethodInliningPass().run(
                program,
                plan,
                MethodInliningOptions.disabled(7));
        MethodInliningResult first = new MethodInliningPass().run(
                program,
                plan,
                MethodInliningOptions.enabled(7));
        MethodInliningResult repeated = new MethodInliningPass().run(
                program,
                plan,
                MethodInliningOptions.enabled(7));
        MethodInliningResult differentSeed = new MethodInliningPass().run(
                program,
                plan,
                MethodInliningOptions.enabled(8));

        assertSame(program, disabled.program());
        assertReason(disabled, MethodInliningReason.DISABLED);
        assertEquals(first.program(), repeated.program());
        assertEquals(first.decisions(), repeated.decisions());
        assertNotEquals(
                find(first.program(), caller.methodKey()).blocks(),
                find(differentSeed.program(), caller.methodKey()).blocks());
    }

    private void assertRejected(IrMethod callee, String reason) {
        IrMethod caller = staticCaller("callerFor" + callee.name(), callee);
        MethodInliningResult result = run(caller, callee, staticCandidate(caller, callee));
        assertEquals(caller, find(result.program(), caller.methodKey()));
        assertReason(result, reason);
    }

    private IrMethod binaryCallee(String name, IrOpcode opcode) {
        IrValue left = value("%left", IrType.I32);
        IrValue right = value("%right", IrType.I32);
        IrValue result = value("%result", IrType.I32);
        return method(
                name,
                "(II)I",
                IrType.I32,
                List.of(left, right),
                new IrBlock(
                        "entry",
                        List.of(IrInstruction.binary(result, opcode, left, right)),
                        IrTerminator.returnValue(result)));
    }

    private IrMethod identityCallee(String name) {
        IrValue input = value("%input", IrType.I32);
        return method(
                name,
                "(I)I",
                IrType.I32,
                List.of(input),
                new IrBlock("entry", List.of(), IrTerminator.returnValue(input)));
    }

    private IrMethod staticCaller(String name, IrMethod callee) {
        List<IrValue> arguments = callee.parameters().stream()
                .map(parameter -> value("%arg" + callee.parameters().indexOf(parameter), parameter.type()))
                .toList();
        IrValue result = callee.returnType() == IrType.VOID
                ? null
                : value("%call", callee.returnType());
        IrInstruction call = IrInstruction.call(
                Optional.ofNullable(result),
                IrOpcode.CALL_STATIC,
                arguments,
                callee.methodKey());
        return method(
                name,
                callee.descriptor(),
                callee.returnType(),
                arguments,
                new IrBlock(
                        "entry",
                        List.of(call),
                        result == null ? IrTerminator.returnVoid() : IrTerminator.returnValue(result)));
    }

    private MethodInliningResult run(
            IrMethod caller,
            IrMethod callee,
            MethodInliningCandidate candidate) {
        return new MethodInliningPass().run(
                program(caller, callee),
                new MethodInliningPlan(List.of(candidate)),
                MethodInliningOptions.enabled(17));
    }

    private MethodInliningCandidate staticCandidate(IrMethod caller, IrMethod callee) {
        return new MethodInliningCandidate(
                caller.methodKey(),
                callee.methodKey(),
                IrOpcode.CALL_STATIC,
                MethodInliningAccess.STATIC,
                true,
                true,
                true,
                false);
    }

    private MethodInliningCandidate privateSelfCandidate(IrMethod caller, IrMethod callee) {
        return new MethodInliningCandidate(
                caller.methodKey(),
                callee.methodKey(),
                IrOpcode.CALL_SPECIAL,
                MethodInliningAccess.PRIVATE_INSTANCE_SELF,
                true,
                true,
                true,
                false);
    }

    private MethodInliningCandidate copy(
            MethodInliningCandidate candidate,
            boolean singleTarget,
            boolean callerNative,
            boolean calleeNative,
            boolean reflectionSensitive) {
        return new MethodInliningCandidate(
                candidate.callerMethodKey(),
                candidate.calleeMethodKey(),
                candidate.invokeOpcode(),
                candidate.access(),
                singleTarget,
                callerNative,
                calleeNative,
                reflectionSensitive);
    }

    private IrProgram program(IrMethod... methods) {
        return new IrProgram(List.of(new IrClass("pkg/Owner", List.of(methods))));
    }

    private IrMethod find(IrProgram program, String methodKey) {
        return program.classes().stream()
                .flatMap(irClass -> irClass.methods().stream())
                .filter(method -> method.methodKey().equals(methodKey))
                .findFirst()
                .orElseThrow();
    }

    private IrMethod method(
            String name,
            String descriptor,
            IrType returnType,
            List<IrValue> parameters,
            IrBlock... blocks) {
        return new IrMethod(
                "pkg/Owner",
                name,
                descriptor,
                returnType,
                parameters,
                List.of(blocks));
    }

    private String key(String name, String descriptor) {
        return "pkg/Owner#" + name + "!" + descriptor;
    }

    private IrValue value(String name, IrType type) {
        return new IrValue(name, type);
    }

    private boolean hasOpcode(IrMethod method, IrOpcode opcode) {
        return method.blocks().stream()
                .flatMap(block -> block.instructions().stream())
                .anyMatch(instruction -> instruction.opcode() == opcode);
    }

    private void assertReason(MethodInliningResult result, String reason) {
        assertTrue(result.decisions().stream().anyMatch(decision -> decision.reasonCode().equals(reason)));
    }
}
