package xyz.melodysky.ir.pass;

import org.junit.jupiter.api.Test;
import xyz.melodysky.ir.model.*;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class CfgCleanupPassTest {

    @Test
    public void testRemovesUnreachableBlocks() {
        IrValue returnValue = new IrValue(0, IrType.INT, "value");
        IrMethod method = new IrMethod(
                "cleanup",
                IrType.INT,
                List.of(),
                0,
                "entry",
                List.of(
                        new IrBlock("entry", List.of(new IrInstruction.Const(returnValue, 7)), new IrTerminator.Return(returnValue)),
                        new IrBlock("dead", List.of(), new IrTerminator.Return(returnValue))
                )
        );

        IrMethod cleaned = new CfgCleanupPass().apply(method);

        assertEquals(1, cleaned.blocks().size());
        assertEquals("entry", cleaned.blocks().get(0).label());
    }

    @Test
    public void testPipelineRunsCleanupAndKeepsValidMethod() {
        IrValue condition = new IrValue(0, IrType.BOOLEAN, "cond");
        IrValue one = new IrValue(1, IrType.INT, "one");
        IrValue deadValue = new IrValue(2, IrType.INT, "dead");
        IrMethod method = new IrMethod(
                "pipeline",
                IrType.INT,
                List.of(),
                1,
                "entry",
                List.of(
                        new IrBlock("entry", List.of(new IrInstruction.Const(condition, true)), new IrTerminator.Branch(condition, "live", "live")),
                        new IrBlock("live", List.of(new IrInstruction.Const(one, 1)), new IrTerminator.Return(one)),
                        new IrBlock("dead", List.of(new IrInstruction.Const(deadValue, 9)), new IrTerminator.Return(deadValue))
                )
        );

        IrMethod result = new IrMethodPassPipeline(List.of(new CfgCleanupPass())).run(method);

        assertEquals(2, result.blocks().size());
        assertEquals("entry", result.blocks().get(0).label());
        assertEquals("live", result.blocks().get(1).label());
    }
}
