package xyz.melodysky.ir.pass;

import xyz.melodysky.ir.model.IrBinaryOpcode;
import xyz.melodysky.ir.model.IrBlock;
import xyz.melodysky.ir.model.IrInstruction;
import xyz.melodysky.ir.model.IrMethod;
import xyz.melodysky.ir.model.IrTerminator;
import xyz.melodysky.ir.model.IrType;
import xyz.melodysky.ir.model.IrValue;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ConstantSplittingPassTest {

    @Test
    public void testRewritesNonTrivialIntConstIntoXorChain() {
        IrValue result = new IrValue(0, IrType.INT, "answer");
        IrMethod method = new IrMethod(
                "splitInt",
                IrType.INT,
                List.of(),
                0,
                "entry",
                List.of(new IrBlock(
                        "entry",
                        List.of(new IrInstruction.Const(result, 42)),
                        new IrTerminator.Return(result)
                ))
        );

        IrMethod rewritten = new ConstantSplittingPass(new Random(1234L)).apply(method);

        assertEquals(3, rewritten.blocks().get(0).instructions().size());
        assertTrue(rewritten.blocks().get(0).instructions().get(0) instanceof IrInstruction.Const);
        assertTrue(rewritten.blocks().get(0).instructions().get(1) instanceof IrInstruction.Const);
        IrInstruction.Binary binary = (IrInstruction.Binary) rewritten.blocks().get(0).instructions().get(2);
        assertEquals(IrBinaryOpcode.XOR, binary.opcode());
        assertEquals(result, binary.result());
    }

    @Test
    public void testLeavesTrivialOrUnsupportedConstsAlone() {
        IrValue flag = new IrValue(0, IrType.BOOLEAN, "flag");
        IrValue tiny = new IrValue(1, IrType.INT, "tiny");
        IrValue keep = new IrValue(2, IrType.LONG, "keep");
        IrMethod method = new IrMethod(
                "keepSmall",
                IrType.LONG,
                List.of(),
                0,
                "entry",
                List.of(new IrBlock(
                        "entry",
                        List.of(
                                new IrInstruction.Const(flag, true),
                                new IrInstruction.Const(tiny, 1),
                                new IrInstruction.Const(keep, -1L)
                        ),
                        new IrTerminator.Return(keep)
                ))
        );

        IrMethod rewritten = new ConstantSplittingPass(new Random(4321L)).apply(method);

        assertEquals(3, rewritten.blocks().get(0).instructions().size());
        assertEquals(method.blocks().get(0).instructions(), rewritten.blocks().get(0).instructions());
    }

    @Test
    public void testPipelineProducesValidMethodAfterSplittingLongConst() {
        IrValue value = new IrValue(0, IrType.LONG, "big");
        IrMethod method = new IrMethod(
                "splitLong",
                IrType.LONG,
                List.of(),
                0,
                "entry",
                List.of(new IrBlock(
                        "entry",
                        List.of(new IrInstruction.Const(value, 9_876_543_210L)),
                        new IrTerminator.Return(value)
                ))
        );

        IrMethod rewritten = new IrMethodPassPipeline(List.of(new ConstantSplittingPass(new Random(999L)))).run(method);

        assertEquals(3, rewritten.blocks().get(0).instructions().size());
        assertTrue(rewritten.blocks().get(0).instructions().get(2) instanceof IrInstruction.Binary);
    }
}
