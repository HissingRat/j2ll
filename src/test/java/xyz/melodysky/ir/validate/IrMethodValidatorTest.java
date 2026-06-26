package xyz.melodysky.ir.validate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import xyz.melodysky.ir.model.IrBlock;
import xyz.melodysky.ir.model.IrExceptionEdge;
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
                                List.of(new IrExceptionEdge("handler", "java/lang/RuntimeException")),
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
}
