package xyz.melodysky.toolchain;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import xyz.melodysky.ir.model.IrBlock;
import xyz.melodysky.ir.model.IrInstruction;
import xyz.melodysky.ir.model.IrMethod;
import xyz.melodysky.ir.model.IrOpcode;
import xyz.melodysky.ir.model.IrTerminator;
import xyz.melodysky.ir.model.IrType;
import xyz.melodysky.ir.model.IrValue;

class NativeLocalReferenceSafetyTest {
    private final NativeLocalReferenceSafety safety =
            new NativeLocalReferenceSafety();

    @Test
    void rejectsAnOwnedJniReferenceCreatedInsideAReachableCycle() {
        IrValue text = new IrValue("%text", IrType.REFERENCE);
        IrMethod method = method(List.of(
                new IrBlock(
                        "entry",
                        List.of(),
                        IrTerminator.gotoBlock("loop")),
                new IrBlock(
                        "loop",
                        List.of(IrInstruction.symbolicConstant(
                                text,
                                IrOpcode.CONST_STRING,
                                "plain:v1:loop")),
                        IrTerminator.gotoBlock("loop"))));

        assertTrue(safety.hasUnboundedLocalReferenceRisk(method));
    }

    @Test
    void allowsBorrowedOrNullReferencesAndAcyclicOwnedReferences() {
        IrValue nullValue = new IrValue("%null", IrType.REFERENCE);
        IrMethod borrowedLoop = method(List.of(new IrBlock(
                "entry",
                List.of(IrInstruction.constNull(nullValue)),
                IrTerminator.gotoBlock("entry"))));
        IrValue text = new IrValue("%text", IrType.REFERENCE);
        IrMethod acyclic = method(List.of(new IrBlock(
                "entry",
                List.of(IrInstruction.symbolicConstant(
                        text,
                        IrOpcode.CONST_STRING,
                        "plain:v1:once")),
                IrTerminator.returnVoid())));

        assertFalse(safety.hasUnboundedLocalReferenceRisk(borrowedLoop));
        assertFalse(safety.hasUnboundedLocalReferenceRisk(acyclic));
    }

    @Test
    void ignoresAnUnreachableReferenceProducingCycle() {
        IrValue text = new IrValue("%text", IrType.REFERENCE);
        IrMethod method = method(List.of(
                new IrBlock(
                        "entry",
                        List.of(),
                        IrTerminator.returnVoid()),
                new IrBlock(
                        "dead",
                        List.of(IrInstruction.symbolicConstant(
                                text,
                                IrOpcode.CONST_STRING,
                                "plain:v1:dead")),
                        IrTerminator.gotoBlock("dead"))));

        assertFalse(safety.hasUnboundedLocalReferenceRisk(method));
    }

    private IrMethod method(List<IrBlock> blocks) {
        return new IrMethod(
                "pkg/Refs",
                "run",
                "()V",
                IrType.VOID,
                List.of(),
                blocks);
    }
}
