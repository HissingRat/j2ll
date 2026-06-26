package xyz.melodysky.ir.pass;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import xyz.melodysky.ir.model.IrBlock;
import xyz.melodysky.ir.model.IrExceptionSite;
import xyz.melodysky.ir.model.IrExceptionSiteKind;
import xyz.melodysky.ir.model.IrInstruction;
import xyz.melodysky.ir.model.IrMethod;
import xyz.melodysky.ir.model.IrOpcode;
import xyz.melodysky.ir.model.IrSwitchCase;
import xyz.melodysky.ir.model.IrTerminator;
import xyz.melodysky.ir.model.IrType;
import xyz.melodysky.ir.model.IrValue;

class OptimizationPipelineTest {
    @Test
    void foldsConstantsAndEliminatesDeadInputs() {
        IrValue two = new IrValue("%two", IrType.I32);
        IrValue three = new IrValue("%three", IrType.I32);
        IrValue sum = new IrValue("%sum", IrType.I32);
        IrMethod method = new IrMethod(
                "pkg/Mathy",
                "calc",
                "()I",
                IrType.I32,
                List.of(),
                List.of(new IrBlock(
                        "entry",
                        List.of(
                                IrInstruction.constInt(two, 2),
                                IrInstruction.constInt(three, 3),
                                IrInstruction.binary(sum, IrOpcode.ADD_I32, two, three)),
                        IrTerminator.returnValue(sum))));

        var result = OptimizationPipeline.defaultPipeline().run(method, PassContext.empty());

        assertFalse(result.hasErrors());
        IrInstruction folded = result.artifact().orElseThrow().blocks().get(0).instructions().get(0);
        assertEquals(IrOpcode.CONST_INT, folded.opcode());
        assertEquals(5, folded.intLiteral().orElseThrow());
        assertEquals("%sum", folded.result().orElseThrow().name());
        assertEquals(1, result.artifact().orElseThrow().blocks().get(0).instructions().size());
    }

    @Test
    void deadInstructionEliminationKeepsTerminatorInputs() {
        IrValue left = new IrValue("%left", IrType.I32);
        IrValue right = new IrValue("%right", IrType.I32);
        IrValue condition = new IrValue("%condition", IrType.I1);
        IrValue dead = new IrValue("%dead", IrType.I32);
        IrMethod branchMethod = new IrMethod(
                "pkg/Branchy",
                "branch",
                "()V",
                IrType.VOID,
                List.of(),
                List.of(
                        new IrBlock(
                                "entry",
                                List.of(
                                        IrInstruction.constInt(left, 1),
                                        IrInstruction.constInt(right, 1),
                                        IrInstruction.binary(condition, IrOpcode.CMP_EQ_I32, left, right),
                                        IrInstruction.constInt(dead, 9)),
                                IrTerminator.branch(condition, "yes", "no")),
                        new IrBlock("yes", List.of(), IrTerminator.returnVoid()),
                        new IrBlock("no", List.of(), IrTerminator.returnVoid())));

        IrMethod branchOptimized = new DeadInstructionEliminationPass().run(branchMethod, PassContext.empty());

        assertTrue(branchOptimized.blocks().get(0).instructions().stream()
                .anyMatch(instruction -> instruction.result().orElseThrow().equals(condition)));
        assertFalse(branchOptimized.blocks().get(0).instructions().stream()
                .anyMatch(instruction -> instruction.result().orElseThrow().equals(dead)));

        IrValue selector = new IrValue("%selector", IrType.I32);
        IrMethod switchMethod = new IrMethod(
                "pkg/Switchy",
                "select",
                "()V",
                IrType.VOID,
                List.of(),
                List.of(
                        new IrBlock(
                                "entry",
                                List.of(IrInstruction.constInt(selector, 7), IrInstruction.constInt(dead, 10)),
                                IrTerminator.switchOn(selector, "default", List.of(new IrSwitchCase(7, "seven")))),
                        new IrBlock("seven", List.of(), IrTerminator.returnVoid()),
                        new IrBlock("default", List.of(), IrTerminator.returnVoid())));

        IrMethod switchOptimized = new DeadInstructionEliminationPass().run(switchMethod, PassContext.empty());

        assertEquals(1, switchOptimized.blocks().get(0).instructions().size());
        assertEquals(selector, switchOptimized.blocks().get(0).instructions().get(0).result().orElseThrow());

        IrValue incoming = new IrValue("%incoming", IrType.I32);
        IrValue blockParameter = new IrValue("%blockParameter", IrType.I32);
        IrMethod parameterMethod = new IrMethod(
                "pkg/Mergey",
                "parameter",
                "()V",
                IrType.VOID,
                List.of(),
                List.of(
                        new IrBlock(
                                "entry",
                                List.of(IrInstruction.constInt(incoming, 3), IrInstruction.constInt(dead, 11)),
                                IrTerminator.gotoBlock("join", List.of(incoming))),
                        new IrBlock(
                                "join",
                                List.of(blockParameter),
                                List.of(),
                                IrTerminator.returnVoid())));

        IrMethod parameterOptimized = new DeadInstructionEliminationPass().run(parameterMethod, PassContext.empty());

        assertEquals(1, parameterOptimized.blocks().get(0).instructions().size());
        assertEquals(incoming, parameterOptimized.blocks().get(0).instructions().get(0).result().orElseThrow());
    }

    @Test
    void deadInstructionEliminationKeepsImplicitExceptionSites() {
        IrValue input = new IrValue("%input", IrType.REFERENCE);
        IrValue cast = new IrValue("%cast", IrType.REFERENCE);
        IrInstruction throwingCast = IrInstruction.operation(
                        java.util.Optional.of(cast),
                        IrOpcode.CHECKCAST,
                        List.of(input),
                        "checkcast:java/lang/String")
                .withExceptionSite(new IrExceptionSite(IrExceptionSiteKind.CLASS_CAST, List.of()));
        IrMethod method = new IrMethod(
                "pkg/Cast",
                "check",
                "(Ljava/lang/Object;)V",
                IrType.VOID,
                List.of(input),
                List.of(new IrBlock(
                        "entry",
                        List.of(throwingCast),
                        IrTerminator.returnVoid())));

        IrMethod optimized = new DeadInstructionEliminationPass().run(method, PassContext.empty());

        assertEquals(1, optimized.blocks().get(0).instructions().size());
        assertEquals(IrOpcode.CHECKCAST, optimized.blocks().get(0).instructions().get(0).opcode());
    }

    @Test
    void deadInstructionEliminationKeepsMemoryMarkersAndTheirInputs() {
        IrValue value = new IrValue("%value", IrType.I32);
        IrValue dead = new IrValue("%dead", IrType.I32);
        IrMethod method = new IrMethod(
                "pkg/Jmm",
                "barrier",
                "()V",
                IrType.VOID,
                List.of(),
                List.of(new IrBlock(
                        "entry",
                        List.of(
                                IrInstruction.constInt(value, 1),
                                IrInstruction.constInt(dead, 2),
                                IrInstruction.operation(
                                        java.util.Optional.empty(),
                                        IrOpcode.VOLATILE_WRITE_BARRIER,
                                        List.of(value),
                                        "pkg/Jmm#value!I")),
                        IrTerminator.returnVoid())));

        IrMethod optimized = new DeadInstructionEliminationPass().run(method, PassContext.empty());

        assertTrue(optimized.blocks().get(0).instructions().stream()
                .anyMatch(instruction -> instruction.result().isPresent()
                        && instruction.result().orElseThrow().equals(value)));
        assertTrue(optimized.blocks().get(0).instructions().stream()
                .anyMatch(instruction -> instruction.opcode() == IrOpcode.VOLATILE_WRITE_BARRIER));
        assertFalse(optimized.blocks().get(0).instructions().stream()
                .anyMatch(instruction -> instruction.result().isPresent()
                        && instruction.result().orElseThrow().equals(dead)));
    }

    @Test
    void deadInstructionEliminationKeepsMonitorHelpers() {
        IrValue lock = new IrValue("%lock", IrType.REFERENCE);
        IrValue dead = new IrValue("%dead", IrType.I32);
        IrMethod method = new IrMethod(
                "pkg/Lock",
                "sync",
                "(Ljava/lang/Object;)V",
                IrType.VOID,
                List.of(lock),
                List.of(new IrBlock(
                        "entry",
                        List.of(
                                IrInstruction.constInt(dead, 7),
                                IrInstruction.operation(
                                        java.util.Optional.empty(),
                                        IrOpcode.MONITOR_ENTER,
                                        List.of(lock),
                                        "monitor"),
                                IrInstruction.operation(
                                        java.util.Optional.empty(),
                                        IrOpcode.MONITOR_EXIT,
                                        List.of(lock),
                                        "monitor")),
                        IrTerminator.returnVoid())));

        IrMethod optimized = new DeadInstructionEliminationPass().run(method, PassContext.empty());

        assertTrue(optimized.blocks().get(0).instructions().stream()
                .anyMatch(instruction -> instruction.opcode() == IrOpcode.MONITOR_ENTER));
        assertTrue(optimized.blocks().get(0).instructions().stream()
                .anyMatch(instruction -> instruction.opcode() == IrOpcode.MONITOR_EXIT));
        assertFalse(optimized.blocks().get(0).instructions().stream()
                .anyMatch(instruction -> instruction.result().isPresent()
                        && instruction.result().orElseThrow().equals(dead)));
    }
}
