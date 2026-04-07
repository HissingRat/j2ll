package xyz.melodysky.ir.pass;

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

public class CfgPerturbationPassTest {

    @Test
    public void testPerturbsGotoEdgeWithSyntheticBranchBlocks() {
        IrValue value = new IrValue(0, IrType.INT, "value");
        IrMethod method = new IrMethod(
                "gotoFlow",
                IrType.INT,
                List.of(),
                0,
                "entry",
                List.of(
                        new IrBlock("entry", List.of(), new IrTerminator.Goto("body")),
                        new IrBlock("body", List.of(new IrInstruction.Const(value, 7)), new IrTerminator.Return(value))
                )
        );

        IrMethod rewritten = new IrMethodPassPipeline(List.of(new CfgPerturbationPass(new Random(1L)))).run(method);

        assertEquals(4, rewritten.blocks().size());
        assertTrue(rewritten.blocks().get(0).terminator() instanceof IrTerminator.Goto);
        IrTerminator.Goto rewrittenGoto = (IrTerminator.Goto) rewritten.blocks().get(0).terminator();
        assertEquals("entry_cf_goto_0", rewrittenGoto.targetBlock());
        assertEquals("entry_cf_goto_0", rewritten.blocks().get(1).label());
        assertTrue(rewritten.blocks().get(1).terminator() instanceof IrTerminator.Branch);
        assertEquals("entry_cf_goto_0_fallback", rewritten.blocks().get(2).label());
        assertTrue(rewritten.blocks().get(2).terminator() instanceof IrTerminator.Goto);
    }

    @Test
    public void testPerturbsBranchTargetsAndPreservesEntry() {
        IrValue condition = new IrValue(0, IrType.BOOLEAN, "cond");
        IrValue thenValue = new IrValue(1, IrType.INT, "then");
        IrValue elseValue = new IrValue(2, IrType.INT, "else");
        IrMethod method = new IrMethod(
                "branchFlow",
                IrType.INT,
                List.of(),
                0,
                "entry",
                List.of(
                        new IrBlock("entry", List.of(new IrInstruction.Const(condition, true)), new IrTerminator.Branch(condition, "then", "else")),
                        new IrBlock("then", List.of(new IrInstruction.Const(thenValue, 1)), new IrTerminator.Return(thenValue)),
                        new IrBlock("else", List.of(new IrInstruction.Const(elseValue, 2)), new IrTerminator.Return(elseValue))
                )
        );

        IrMethod rewritten = new IrMethodPassPipeline(List.of(new CfgPerturbationPass(new Random(2L)))).run(method);

        assertEquals("entry", rewritten.entryBlock());
        assertEquals(7, rewritten.blocks().size());
        IrTerminator.Branch branch = (IrTerminator.Branch) rewritten.blocks().get(0).terminator();
        assertEquals("entry_cf_true_0", branch.trueTarget());
        assertEquals("entry_cf_false_1", branch.falseTarget());
    }

    @Test
    public void testLeavesSwitchUntouched() {
        IrValue selector = new IrValue(0, IrType.INT, "selector");
        IrValue caseValue = new IrValue(1, IrType.INT, "case");
        IrValue defaultValue = new IrValue(2, IrType.INT, "default");
        IrMethod method = new IrMethod(
                "switchFlow",
                IrType.INT,
                List.of(),
                0,
                "entry",
                List.of(
                        new IrBlock(
                                "entry",
                                List.of(new IrInstruction.Const(selector, 1)),
                                new IrTerminator.Switch(selector, java.util.Map.of(1, "case1"), "default")
                        ),
                        new IrBlock("case1", List.of(new IrInstruction.Const(caseValue, 1)), new IrTerminator.Return(caseValue)),
                        new IrBlock("default", List.of(new IrInstruction.Const(defaultValue, 0)), new IrTerminator.Return(defaultValue))
                )
        );

        IrMethod rewritten = new IrMethodPassPipeline(List.of(new CfgPerturbationPass(new Random(3L)))).run(method);

        assertEquals(3, rewritten.blocks().size());
        assertTrue(rewritten.blocks().get(0).terminator() instanceof IrTerminator.Switch);
    }
}
