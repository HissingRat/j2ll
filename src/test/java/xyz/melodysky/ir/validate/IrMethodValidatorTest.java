package xyz.melodysky.ir.validate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import xyz.melodysky.ir.model.IrBlock;
import xyz.melodysky.ir.model.IrExceptionEdge;
import xyz.melodysky.ir.model.IrExceptionSite;
import xyz.melodysky.ir.model.IrExceptionSiteKind;
import xyz.melodysky.ir.model.IrInstruction;
import xyz.melodysky.ir.model.IrMethod;
import xyz.melodysky.ir.model.IrOpcode;
import xyz.melodysky.ir.model.IrSwitchCase;
import xyz.melodysky.ir.model.IrTerminator;
import xyz.melodysky.ir.model.IrType;
import xyz.melodysky.ir.model.IrValue;

class IrMethodValidatorTest {
    @Test
    void reportsUseBeforeDef() {
        IrValue undefined = new IrValue("%missing", IrType.I32);
        IrValue result = new IrValue("%result", IrType.I32);
        IrMethod method = new IrMethod(
                "pkg/Broken",
                "bad",
                "()I",
                IrType.I32,
                List.of(),
                List.of(new IrBlock(
                        "entry",
                        List.of(new IrInstruction(
                                Optional.of(result),
                                IrOpcode.ADD_I32,
                                List.of(undefined, undefined),
                                Optional.empty())),
                        IrTerminator.returnValue(result))));

        var diagnostics = new IrMethodValidator().validate(method);

        assertEquals(IrValidationDiagnostics.IR_USE_BEFORE_DEF, diagnostics.get(0).code());
    }

    @Test
    void validatesSwitchTerminatorTargets() {
        IrValue selector = new IrValue("%p0", IrType.I32);
        IrMethod valid = new IrMethod(
                "pkg/Switchy",
                "select",
                "(I)V",
                IrType.VOID,
                List.of(selector),
                List.of(
                        new IrBlock(
                                "entry",
                                List.of(),
                                IrTerminator.switchOn(selector, "default", List.of(new IrSwitchCase(1, "one")))),
                        new IrBlock("one", List.of(), IrTerminator.returnVoid()),
                        new IrBlock("default", List.of(), IrTerminator.returnVoid())));

        assertTrue(new IrMethodValidator().validate(valid).isEmpty());

        IrMethod missingDefault = new IrMethod(
                "pkg/Switchy",
                "broken",
                "(I)V",
                IrType.VOID,
                List.of(selector),
                List.of(new IrBlock(
                        "entry",
                        List.of(),
                        IrTerminator.switchOn(selector, "missing", List.of(new IrSwitchCase(1, "entry"))))));

        var diagnostics = new IrMethodValidator().validate(missingDefault);

        assertEquals(IrValidationDiagnostics.IR_USE_BEFORE_DEF, diagnostics.get(0).code());
    }

    @Test
    void validatesBlockParameterTargetArguments() {
        IrValue argument = new IrValue("%arg", IrType.I32);
        IrValue parameter = new IrValue("%join", IrType.I32);
        IrMethod valid = new IrMethod(
                "pkg/Merge",
                "ok",
                "()V",
                IrType.VOID,
                List.of(),
                List.of(
                        new IrBlock(
                                "entry",
                                List.of(IrInstruction.constInt(argument, 1)),
                                IrTerminator.gotoBlock("join", List.of(argument))),
                        new IrBlock("join", List.of(parameter), List.of(), IrTerminator.returnVoid())));

        assertTrue(new IrMethodValidator().validate(valid).isEmpty());

        IrMethod wrongArity = new IrMethod(
                "pkg/Merge",
                "arity",
                "()V",
                IrType.VOID,
                List.of(),
                List.of(
                        new IrBlock("entry", List.of(), IrTerminator.gotoBlock("join")),
                        new IrBlock("join", List.of(parameter), List.of(), IrTerminator.returnVoid())));
        assertEquals(
                IrValidationDiagnostics.IR_BLOCK_ARGUMENT_MISMATCH,
                new IrMethodValidator().validate(wrongArity).get(0).code());

        IrValue reference = new IrValue("%ref", IrType.REFERENCE);
        IrMethod wrongType = new IrMethod(
                "pkg/Merge",
                "type",
                "()V",
                IrType.VOID,
                List.of(reference),
                List.of(
                        new IrBlock(
                                "entry",
                                List.of(),
                                IrTerminator.gotoBlock("join", List.of(reference))),
                        new IrBlock("join", List.of(parameter), List.of(), IrTerminator.returnVoid())));
        assertEquals(
                IrValidationDiagnostics.IR_BLOCK_ARGUMENT_MISMATCH,
                new IrMethodValidator().validate(wrongType).get(0).code());
    }

    @Test
    void validatesThrowAndExceptionHandlerShape() {
        IrValue thrown = new IrValue("%ex", IrType.REFERENCE);
        IrValue handlerParam = new IrValue("%caught", IrType.REFERENCE);
        IrMethod valid = new IrMethod(
                "pkg/Try",
                "raise",
                "(Ljava/lang/RuntimeException;)V",
                IrType.VOID,
                List.of(thrown),
                List.of(
                        new IrBlock(
                                "entry",
                                List.of(),
                                List.of(),
                                List.of(new IrExceptionEdge(
                                        "handler",
                                        "java/lang/RuntimeException",
                                        List.of(thrown))),
                                List.of(),
                                IrTerminator.throwValue(thrown)),
                        new IrBlock(
                                "handler",
                                List.of(handlerParam),
                                List.of("java/lang/RuntimeException"),
                                List.of(),
                                List.of(),
                                IrTerminator.returnVoid())));

        assertTrue(new IrMethodValidator().validate(valid).isEmpty());

        IrValue intValue = new IrValue("%bad", IrType.I32);
        IrMethod badThrowType = new IrMethod(
                "pkg/Try",
                "bad",
                "()V",
                IrType.VOID,
                List.of(intValue),
                List.of(new IrBlock("entry", List.of(), IrTerminator.throwValue(intValue))));
        assertEquals(
                IrValidationDiagnostics.IR_THROW_TYPE_MISMATCH,
                new IrMethodValidator().validate(badThrowType).get(0).code());

        IrMethod badHandler = new IrMethod(
                "pkg/Try",
                "badHandler",
                "()V",
                IrType.VOID,
                List.of(),
                List.of(new IrBlock(
                        "handler",
                        List.of(),
                        List.of("java/lang/RuntimeException"),
                        List.of(),
                        IrTerminator.returnVoid())));
        assertEquals(
                IrValidationDiagnostics.IR_EXCEPTION_EDGE_MISMATCH,
                new IrMethodValidator().validate(badHandler).get(0).code());
    }

    @Test
    void validatesMonitorHelperOperandType() {
        IrValue lock = new IrValue("%lock", IrType.REFERENCE);
        IrMethod valid = new IrMethod(
                "pkg/Lock",
                "ok",
                "(Ljava/lang/Object;)V",
                IrType.VOID,
                List.of(lock),
                List.of(new IrBlock(
                        "entry",
                        List.of(IrInstruction.operation(
                                Optional.empty(),
                                IrOpcode.MONITOR_ENTER,
                                List.of(lock),
                                "monitor")),
                        IrTerminator.returnVoid())));

        assertTrue(new IrMethodValidator().validate(valid).isEmpty());

        IrValue intLock = new IrValue("%intLock", IrType.I32);
        IrMethod wrongType = new IrMethod(
                "pkg/Lock",
                "bad",
                "(I)V",
                IrType.VOID,
                List.of(intLock),
                List.of(new IrBlock(
                        "entry",
                        List.of(IrInstruction.operation(
                                Optional.empty(),
                                IrOpcode.MONITOR_EXIT,
                                List.of(intLock),
                                "monitor")),
                        IrTerminator.returnVoid())));

        assertEquals(
                IrValidationDiagnostics.IR_MONITOR_TYPE_MISMATCH,
                new IrMethodValidator().validate(wrongType).get(0).code());
    }

    @Test
    void validatesClassInitializationHelperOperandTypes() {
        IrValue classId = new IrValue("%classId", IrType.I64);
        IrValue classObject = new IrValue("%classObject", IrType.REFERENCE);
        IrMethod valid = new IrMethod(
                "pkg/Classy",
                "use",
                "()V",
                IrType.VOID,
                List.of(classId),
                List.of(new IrBlock(
                        "entry",
                        List.of(
                                IrInstruction.operation(
                                        Optional.of(classObject),
                                        IrOpcode.CLASS_OBJECT,
                                        List.of(classId),
                                        "class:Lpkg/Classy;"),
                                IrInstruction.operation(
                                        Optional.empty(),
                                        IrOpcode.CLASS_INIT_GUARD,
                                        List.of(classObject),
                                        "class:Lpkg/Classy;")),
                        IrTerminator.returnVoid())));

        assertTrue(new IrMethodValidator().validate(valid).isEmpty());

        IrValue intClass = new IrValue("%badClass", IrType.I32);
        IrMethod wrongType = new IrMethod(
                "pkg/Classy",
                "bad",
                "()V",
                IrType.VOID,
                List.of(intClass),
                List.of(new IrBlock(
                        "entry",
                        List.of(IrInstruction.operation(
                                Optional.empty(),
                                IrOpcode.CLASS_INIT_END,
                                List.of(intClass),
                                "class:Lpkg/Classy;")),
                        IrTerminator.returnVoid())));

        assertEquals(
                IrValidationDiagnostics.IR_CLASS_INIT_TYPE_MISMATCH,
                new IrMethodValidator().validate(wrongType).get(0).code());
    }

    @Test
    void acceptsDiamondPhiAndRejectsDirectCrossBranchUse() {
        IrValue condition = new IrValue("%p0", IrType.I1);
        IrValue left = new IrValue("%left", IrType.I32);
        IrValue right = new IrValue("%right", IrType.I32);
        IrValue merged = new IrValue("%merged", IrType.I32);
        IrMethod valid = new IrMethod(
                "pkg/Diamond",
                "valid",
                "(Z)I",
                IrType.I32,
                List.of(condition),
                List.of(
                        new IrBlock(
                                "entry",
                                List.of(),
                                IrTerminator.branch(condition, "left", "right")),
                        new IrBlock(
                                "left",
                                List.of(IrInstruction.constInt(left, 1)),
                                IrTerminator.gotoBlock("join", List.of(left))),
                        new IrBlock(
                                "right",
                                List.of(IrInstruction.constInt(right, 2)),
                                IrTerminator.gotoBlock("join", List.of(right))),
                        new IrBlock(
                                "join",
                                List.of(merged),
                                List.of(),
                                IrTerminator.returnValue(merged))));

        assertTrue(new IrMethodValidator().validate(valid).isEmpty());

        IrMethod invalid = new IrMethod(
                "pkg/Diamond",
                "invalid",
                "(Z)I",
                IrType.I32,
                List.of(condition),
                List.of(
                        new IrBlock(
                                "entry",
                                List.of(),
                                IrTerminator.branch(condition, "left", "right")),
                        new IrBlock(
                                "left",
                                List.of(IrInstruction.constInt(left, 1)),
                                IrTerminator.gotoBlock("join")),
                        new IrBlock("right", List.of(), IrTerminator.gotoBlock("join")),
                        new IrBlock("join", List.of(), IrTerminator.returnValue(left))));

        var diagnostics = new IrMethodValidator().validate(invalid);
        assertTrue(diagnostics.stream().anyMatch(diagnostic ->
                diagnostic.code().equals(IrValidationDiagnostics.IR_USE_BEFORE_DEF)
                        && diagnostic.message().contains("terminator value")));

        IrValue zero = new IrValue("%zero", IrType.I32);
        IrValue sum = new IrValue("%sum", IrType.I32);
        IrMethod invalidInstruction = new IrMethod(
                "pkg/Diamond",
                "invalidInstruction",
                "(Z)I",
                IrType.I32,
                List.of(condition),
                List.of(
                        new IrBlock(
                                "entry",
                                List.of(),
                                IrTerminator.branch(condition, "left", "right")),
                        new IrBlock(
                                "left",
                                List.of(IrInstruction.constInt(left, 1)),
                                IrTerminator.gotoBlock("join")),
                        new IrBlock("right", List.of(), IrTerminator.gotoBlock("join")),
                        new IrBlock(
                                "join",
                                List.of(
                                        IrInstruction.constInt(zero, 0),
                                        IrInstruction.binary(sum, IrOpcode.ADD_I32, left, zero)),
                                IrTerminator.returnValue(sum))));
        assertTrue(new IrMethodValidator().validate(invalidInstruction).stream().anyMatch(diagnostic ->
                diagnostic.code().equals(IrValidationDiagnostics.IR_USE_BEFORE_DEF)
                        && diagnostic.message().contains("instruction operand")));
    }

    @Test
    void acceptsLoopCarriedBlockParameters() {
        IrValue keepGoing = new IrValue("%p0", IrType.I1);
        IrValue seed = new IrValue("%seed", IrType.I32);
        IrValue current = new IrValue("%current", IrType.I32);
        IrValue one = new IrValue("%one", IrType.I32);
        IrValue next = new IrValue("%next", IrType.I32);
        IrValue result = new IrValue("%result", IrType.I32);
        IrMethod method = new IrMethod(
                "pkg/Loop",
                "count",
                "(Z)I",
                IrType.I32,
                List.of(keepGoing),
                List.of(
                        new IrBlock(
                                "entry",
                                List.of(IrInstruction.constInt(seed, 0)),
                                IrTerminator.gotoBlock("header", List.of(seed))),
                        new IrBlock(
                                "header",
                                List.of(current),
                                List.of(),
                                IrTerminator.branch(
                                        keepGoing,
                                        "body",
                                        List.of(),
                                        "exit",
                                        List.of(current))),
                        new IrBlock(
                                "body",
                                List.of(
                                        IrInstruction.constInt(one, 1),
                                        IrInstruction.binary(next, IrOpcode.ADD_I32, current, one)),
                                IrTerminator.gotoBlock("header", List.of(next))),
                        new IrBlock(
                                "exit",
                                List.of(result),
                                List.of(),
                                IrTerminator.returnValue(result))));

        assertTrue(new IrMethodValidator().validate(method).isEmpty());
    }

    @Test
    void validatesTerminatorConditionAndTargetArgumentDominance() {
        IrValue selector = new IrValue("%p0", IrType.I1);
        IrValue leftCondition = new IrValue("%leftCondition", IrType.I1);
        IrValue one = new IrValue("%one", IrType.I32);
        IrValue anotherOne = new IrValue("%anotherOne", IrType.I32);
        IrMethod badCondition = new IrMethod(
                "pkg/Dominance",
                "condition",
                "(Z)V",
                IrType.VOID,
                List.of(selector),
                List.of(
                        new IrBlock(
                                "entry",
                                List.of(),
                                IrTerminator.branch(selector, "left", "right")),
                        new IrBlock(
                                "left",
                                List.of(
                                        IrInstruction.constInt(one, 1),
                                        IrInstruction.constInt(anotherOne, 1),
                                        IrInstruction.binary(
                                                leftCondition,
                                                IrOpcode.CMP_EQ_I32,
                                                one,
                                                anotherOne)),
                                IrTerminator.gotoBlock("join")),
                        new IrBlock("right", List.of(), IrTerminator.gotoBlock("join")),
                        new IrBlock(
                                "join",
                                List.of(),
                                IrTerminator.branch(leftCondition, "yes", "no")),
                        new IrBlock("yes", List.of(), IrTerminator.returnVoid()),
                        new IrBlock("no", List.of(), IrTerminator.returnVoid())));
        assertTrue(new IrMethodValidator().validate(badCondition).stream().anyMatch(diagnostic ->
                diagnostic.code().equals(IrValidationDiagnostics.IR_USE_BEFORE_DEF)
                        && diagnostic.message().contains("terminator condition")));

        IrValue branchValue = new IrValue("%branchValue", IrType.I32);
        IrValue joined = new IrValue("%joined", IrType.I32);
        IrMethod badArgument = new IrMethod(
                "pkg/Dominance",
                "argument",
                "(Z)I",
                IrType.I32,
                List.of(selector),
                List.of(
                        new IrBlock(
                                "entry",
                                List.of(),
                                IrTerminator.branch(selector, "left", "right")),
                        new IrBlock(
                                "left",
                                List.of(IrInstruction.constInt(branchValue, 3)),
                                IrTerminator.gotoBlock("join", List.of(branchValue))),
                        new IrBlock(
                                "right",
                                List.of(),
                                IrTerminator.gotoBlock("join", List.of(branchValue))),
                        new IrBlock(
                                "join",
                                List.of(joined),
                                List.of(),
                                IrTerminator.returnValue(joined))));
        assertTrue(new IrMethodValidator().validate(badArgument).stream().anyMatch(diagnostic ->
                diagnostic.code().equals(IrValidationDiagnostics.IR_USE_BEFORE_DEF)
                        && diagnostic.message().contains("terminator target argument")));
    }

    @Test
    void exceptionSiteValueIsAvailableOnlyToItsOwnHandlerArguments() {
        IrValue input = new IrValue("%p0", IrType.REFERENCE);
        IrValue result = new IrValue("%result", IrType.REFERENCE);
        IrValue pending = new IrValue("%pending", IrType.REFERENCE);
        IrValue caught = new IrValue("%caught", IrType.REFERENCE);
        IrValue saved = new IrValue("%saved", IrType.REFERENCE);
        IrExceptionSite validSite = new IrExceptionSite(
                IrExceptionSiteKind.JVM_PENDING_EXCEPTION,
                List.of(new IrExceptionEdge(
                        "handler",
                        "java/lang/RuntimeException",
                        List.of(pending, input))),
                Optional.of(pending));
        IrMethod valid = new IrMethod(
                "pkg/Handler",
                "valid",
                "(Ljava/lang/Object;)Ljava/lang/Object;",
                IrType.REFERENCE,
                List.of(input),
                List.of(
                        new IrBlock(
                                "entry",
                                List.of(IrInstruction.call(
                                                Optional.of(result),
                                                IrOpcode.CALL_RUNTIME_HELPER,
                                                List.of(input),
                                                "j2ll_rt_identity")
                                        .withExceptionSite(validSite)),
                                IrTerminator.returnValue(result)),
                        new IrBlock(
                                "handler",
                                List.of(caught, saved),
                                List.of("java/lang/RuntimeException"),
                                List.of(),
                                IrTerminator.returnValue(saved))));

        assertTrue(new IrMethodValidator().validate(valid).isEmpty());

        IrExceptionSite invalidSite = new IrExceptionSite(
                IrExceptionSiteKind.JVM_PENDING_EXCEPTION,
                List.of(new IrExceptionEdge(
                        "handler",
                        "java/lang/RuntimeException",
                        List.of(pending, result))),
                Optional.of(pending));
        IrMethod invalid = new IrMethod(
                "pkg/Handler",
                "invalid",
                "(Ljava/lang/Object;)Ljava/lang/Object;",
                IrType.REFERENCE,
                List.of(input),
                List.of(
                        new IrBlock(
                                "entry",
                                List.of(IrInstruction.call(
                                                Optional.of(result),
                                                IrOpcode.CALL_RUNTIME_HELPER,
                                                List.of(input),
                                                "j2ll_rt_identity")
                                        .withExceptionSite(invalidSite)),
                                IrTerminator.returnValue(result)),
                        new IrBlock(
                                "handler",
                                List.of(caught, saved),
                                List.of("java/lang/RuntimeException"),
                                List.of(),
                                IrTerminator.returnValue(saved))));
        assertTrue(new IrMethodValidator().validate(invalid).stream().anyMatch(diagnostic ->
                diagnostic.code().equals(IrValidationDiagnostics.IR_USE_BEFORE_DEF)
                        && diagnostic.message().contains("exception-site handler argument")));

        IrMethod leakedExceptionValue = new IrMethod(
                "pkg/Handler",
                "leakedExceptionValue",
                "(Ljava/lang/Object;)Ljava/lang/Object;",
                IrType.REFERENCE,
                List.of(input),
                List.of(
                        new IrBlock(
                                "entry",
                                List.of(IrInstruction.call(
                                                Optional.of(result),
                                                IrOpcode.CALL_RUNTIME_HELPER,
                                                List.of(input),
                                                "j2ll_rt_identity")
                                        .withExceptionSite(validSite)),
                                IrTerminator.returnValue(pending)),
                        new IrBlock(
                                "handler",
                                List.of(caught, saved),
                                List.of("java/lang/RuntimeException"),
                                List.of(),
                                IrTerminator.returnValue(saved))));
        assertTrue(new IrMethodValidator().validate(leakedExceptionValue).stream().anyMatch(diagnostic ->
                diagnostic.code().equals(IrValidationDiagnostics.IR_USE_BEFORE_DEF)
                        && diagnostic.message().contains("terminator value")));
    }

    @Test
    void validatesBlockExceptionEdgeArgumentDominance() {
        IrValue selector = new IrValue("%p0", IrType.I1);
        IrValue thrown = new IrValue("%p1", IrType.REFERENCE);
        IrValue leftValue = new IrValue("%leftValue", IrType.REFERENCE);
        IrValue caught = new IrValue("%caught", IrType.REFERENCE);
        IrMethod method = new IrMethod(
                "pkg/Handler",
                "badEdge",
                "(ZLjava/lang/RuntimeException;)V",
                IrType.VOID,
                List.of(selector, thrown),
                List.of(
                        new IrBlock(
                                "entry",
                                List.of(),
                                IrTerminator.branch(selector, "left", "right")),
                        new IrBlock(
                                "left",
                                List.of(IrInstruction.constNull(leftValue)),
                                IrTerminator.returnVoid()),
                        new IrBlock(
                                "right",
                                List.of(),
                                List.of(),
                                List.of(new IrExceptionEdge(
                                        "handler",
                                        "java/lang/RuntimeException",
                                        List.of(leftValue))),
                                List.of(),
                                IrTerminator.throwValue(thrown)),
                        new IrBlock(
                                "handler",
                                List.of(caught),
                                List.of("java/lang/RuntimeException"),
                                List.of(),
                                IrTerminator.returnVoid())));

        assertTrue(new IrMethodValidator().validate(method).stream().anyMatch(diagnostic ->
                diagnostic.code().equals(IrValidationDiagnostics.IR_USE_BEFORE_DEF)
                        && diagnostic.message().contains("exception-edge argument")));
    }

    @Test
    void unreachableBlockGetsStableDiagnosticWithoutDominanceNoise() {
        IrValue missing = new IrValue("%missing", IrType.I32);
        IrValue result = new IrValue("%result", IrType.I32);
        IrMethod method = new IrMethod(
                "pkg/Dead",
                "dead",
                "()V",
                IrType.VOID,
                List.of(),
                List.of(
                        new IrBlock("entry", List.of(), IrTerminator.returnVoid()),
                        new IrBlock(
                                "dead",
                                List.of(IrInstruction.unary(result, IrOpcode.NEG_I32, missing)),
                                IrTerminator.returnVoid())));

        var diagnostics = new IrMethodValidator().validate(method);

        assertEquals(
                List.of(IrValidationDiagnostics.IR_UNREACHABLE_BLOCK),
                diagnostics.stream().map(diagnostic -> diagnostic.code()).toList());
        assertTrue(diagnostics.get(0).message().endsWith("dead"));
    }
}
