package xyz.melodysky.ir.pass.protection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;
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
import xyz.melodysky.ir.validate.IrMethodValidator;

class ControlFlowFlatteningRegionPlannerTest {
    private final ControlFlowFlatteningRegionPlanner planner =
            new ControlFlowFlatteningRegionPlanner();

    @Test
    void selectsBoundedSingleEntryMultiExitRegion() {
        IrValue condition = new IrValue("%condition", IrType.I1);
        IrValue throwable = new IrValue("%throwable", IrType.REFERENCE);
        IrValue value = new IrValue("%value", IrType.I32);
        IrMethod method = new IrMethod(
                "pkg/Region",
                "choose",
                "(ZLjava/lang/Throwable;)I",
                IrType.I32,
                List.of(condition, throwable),
                List.of(
                        new IrBlock(
                                "entry",
                                List.of(),
                                IrTerminator.branch(condition, "work", "throw")),
                        new IrBlock("work", List.of(), IrTerminator.gotoBlock("done")),
                        new IrBlock(
                                "done",
                                List.of(IrInstruction.constInt(value, 7)),
                                IrTerminator.returnValue(value)),
                        new IrBlock("throw", List.of(), IrTerminator.throwValue(throwable))));

        ControlFlowFlatteningPlan plan = planner.plan(method, 19L);

        assertTrue(plan.selected());
        assertEquals(ControlFlowFlatteningRegionPlanner.APPLIED_REASON, plan.reasonCode());
        assertEquals(1, plan.regions().size());
        ControlFlowFlatteningRegion region = plan.regions().get(0);
        assertEquals("entry", region.entryBlock());
        assertEquals(List.of("entry", "work", "done"), region.memberBlocks());
        assertFalse(region.contains("throw"));
        assertEquals(Set.of(0, 1, 2), Set.copyOf(region.stateByBlock().values()));
    }

    @Test
    void preservesExceptionAndOwnedReferenceBlocksOutsideSelectedRegion() {
        IrValue condition = new IrValue("%condition", IrType.I1);
        IrValue normal = new IrValue("%normal", IrType.I32);
        IrValue helperResult = new IrValue("%helper", IrType.I32);
        IrValue pending = new IrValue("%pending", IrType.REFERENCE);
        IrValue caught = new IrValue("%caught", IrType.REFERENCE);
        IrValue failure = new IrValue("%failure", IrType.I32);
        IrExceptionEdge handler = new IrExceptionEdge(
                "handler",
                "java/lang/RuntimeException",
                List.of(pending));
        IrInstruction helper = IrInstruction.call(
                        java.util.Optional.of(helperResult),
                        IrOpcode.CALL_RUNTIME_HELPER,
                        List.of(),
                        "j2ll_rt_int_helper")
                .withExceptionSite(new IrExceptionSite(
                        IrExceptionSiteKind.JVM_PENDING_EXCEPTION,
                        List.of(handler),
                        java.util.Optional.of(pending)));
        IrMethod method = new IrMethod(
                "pkg/ExceptionalRegion",
                "choose",
                "(Z)I",
                IrType.I32,
                List.of(condition),
                List.of(
                        new IrBlock(
                                "entry",
                                List.of(),
                                IrTerminator.branch(condition, "safe", "risky")),
                        new IrBlock("safe", List.of(), IrTerminator.gotoBlock("done")),
                        new IrBlock(
                                "done",
                                List.of(IrInstruction.constInt(normal, 7)),
                                IrTerminator.returnValue(normal)),
                        new IrBlock("risky", List.of(helper), IrTerminator.returnValue(helperResult)),
                        new IrBlock(
                                "handler",
                                List.of(caught),
                                List.of("java/lang/RuntimeException"),
                                List.of(IrInstruction.constInt(failure, -1)),
                                IrTerminator.returnValue(failure))));

        ControlFlowFlatteningPlan plan = planner.plan(method, 23L);

        assertTrue(plan.selected());
        assertEquals(List.of("entry", "safe", "done"), plan.regions().get(0).memberBlocks());
        assertTrue(plan.regionForBlock("risky").isEmpty());
        assertTrue(plan.regionForBlock("handler").isEmpty());
    }

    @Test
    void refusesEligibleComponentWithMoreThanOneEntry() {
        IrValue selector = new IrValue("%selector", IrType.I32);
        IrValue result = new IrValue("%result", IrType.I32);
        IrMethod method = new IrMethod(
                "pkg/MultiEntry",
                "choose",
                "(I)I",
                IrType.I32,
                List.of(selector),
                List.of(
                        new IrBlock(
                                "entry",
                                List.of(),
                                IrTerminator.switchOn(
                                        selector,
                                        "left",
                                        List.of(new IrSwitchCase(1, "right")))),
                        new IrBlock("left", List.of(), IrTerminator.gotoBlock("merge")),
                        new IrBlock("right", List.of(), IrTerminator.gotoBlock("merge")),
                        new IrBlock(
                                "merge",
                                List.of(IrInstruction.constInt(result, 1)),
                                IrTerminator.returnValue(result))));

        ControlFlowFlatteningPlan plan = planner.plan(method, 29L);

        assertFalse(plan.selected());
        assertEquals(ControlFlowFlatteningRegionPlanner.UNSUPPORTED_SHAPE_REASON, plan.reasonCode());
    }

    @Test
    void reportsCrossBlockInstructionDefinitionInsteadOfSelectingIt() {
        IrValue value = new IrValue("%value", IrType.I32);
        IrMethod method = new IrMethod(
                "pkg/CrossBlock",
                "value",
                "()I",
                IrType.I32,
                List.of(),
                List.of(
                        new IrBlock(
                                "entry",
                                List.of(IrInstruction.constInt(value, 7)),
                                IrTerminator.gotoBlock("exit")),
                        new IrBlock("exit", List.of(), IrTerminator.returnValue(value))));

        ControlFlowFlatteningPlan plan = planner.plan(method, 31L);

        assertFalse(plan.selected());
        assertEquals(ControlFlowFlatteningRegionPlanner.CROSS_BLOCK_SSA_REASON, plan.reasonCode());
    }

    @Test
    void reportsOwnedReferenceWhenItIsTheOnlyRegionBlocker() {
        IrValue condition = new IrValue("%condition", IrType.I1);
        IrValue string = new IrValue("%string", IrType.REFERENCE);
        IrValue yes = new IrValue("%yes", IrType.I32);
        IrValue no = new IrValue("%no", IrType.I32);
        IrMethod method = new IrMethod(
                "pkg/Owned",
                "choose",
                "(Z)I",
                IrType.I32,
                List.of(condition),
                List.of(
                        new IrBlock(
                                "entry",
                                List.of(IrInstruction.operation(
                                        java.util.Optional.of(string),
                                        IrOpcode.CONST_STRING,
                                        List.of(),
                                        "string:value")),
                                IrTerminator.branch(condition, "yes", "no")),
                        new IrBlock(
                                "yes",
                                List.of(IrInstruction.constInt(yes, 1)),
                                IrTerminator.returnValue(yes)),
                        new IrBlock(
                                "no",
                                List.of(IrInstruction.constInt(no, 0)),
                                IrTerminator.returnValue(no))));

        ControlFlowFlatteningPlan plan = planner.plan(method, 37L);

        assertFalse(plan.selected());
        assertEquals(
                ControlFlowFlatteningRegionPlanner.OWNED_LOCAL_REFERENCE_REASON,
                plan.reasonCode());
    }

    @Test
    void derivesStableDenseStatesAndVariesThemAcrossSeeds() {
        IrMethod method = threeBlockMethod();

        ControlFlowFlatteningPlan first = planner.plan(method, 41L);
        ControlFlowFlatteningPlan repeated = planner.plan(method, 41L);

        assertEquals(first, repeated);
        assertEquals(
                Set.of(0, 1, 2),
                Set.copyOf(first.regions().get(0).stateByBlock().values()));
        assertTrue(IntStream.range(42, 80)
                .mapToObj(seed -> planner.plan(method, seed))
                .anyMatch(plan -> !plan.regions().get(0).stateByBlock()
                        .equals(first.regions().get(0).stateByBlock())));
        assertNotEquals("", first.regions().get(0).regionId());
    }

    @Test
    void capsSelectedRegionsAtFour() {
        IrValue selector = new IrValue("%selector", IrType.I32);
        IrValue throwable = new IrValue("%throwable", IrType.REFERENCE);
        ArrayList<IrBlock> blocks = new ArrayList<>();
        blocks.add(new IrBlock(
                "start",
                List.of(),
                IrTerminator.switchOn(selector, "r0a", List.of())));
        for (int index = 0; index < 5; index++) {
            blocks.add(new IrBlock("r" + index + "a", List.of(), IrTerminator.gotoBlock("r" + index + "b")));
            blocks.add(new IrBlock("r" + index + "b", List.of(), IrTerminator.gotoBlock("separator" + index)));
            String next = index == 4 ? "throw" : "r" + (index + 1) + "a";
            blocks.add(new IrBlock(
                    "separator" + index,
                    List.of(),
                    IrTerminator.switchOn(selector, next, List.of())));
        }
        blocks.add(new IrBlock("throw", List.of(), IrTerminator.throwValue(throwable)));
        IrMethod method = new IrMethod(
                "pkg/RegionLimit",
                "run",
                "(ILjava/lang/Throwable;)V",
                IrType.VOID,
                List.of(selector, throwable),
                blocks);

        ControlFlowFlatteningPlan plan = planner.plan(method, 43L);

        assertEquals(ControlFlowFlatteningPlan.MAX_REGIONS, plan.regions().size());
        assertTrue(plan.regionForBlock("r4a").isEmpty());
    }

    @Test
    void methodEntryCannotBecomeAMemberOfALaterRegion() {
        IrValue selector = new IrValue("%selector", IrType.I32);
        IrValue condition = new IrValue("%condition", IrType.I1);
        IrMethod method = new IrMethod(
                "pkg/EntryCycle",
                "run",
                "(IZ)V",
                IrType.VOID,
                List.of(selector, condition),
                List.of(
                        new IrBlock("entry", List.of(), IrTerminator.gotoBlock("separator")),
                        new IrBlock(
                                "separator",
                                List.of(),
                                IrTerminator.switchOn(
                                        selector,
                                        "header",
                                        List.of(new IrSwitchCase(1, "exit")))),
                        new IrBlock(
                                "header",
                                List.of(),
                                IrTerminator.branch(condition, "inner", "entry")),
                        new IrBlock("inner", List.of(), IrTerminator.gotoBlock("entry")),
                        new IrBlock("exit", List.of(), IrTerminator.returnVoid())));

        ControlFlowFlatteningPlan plan = planner.plan(method, 44L);

        assertTrue(plan.selected());
        assertEquals(List.of("header", "inner"), plan.regions().get(0).memberBlocks());
        assertTrue(plan.regionForBlock("entry").isEmpty());
        IrMethod rewritten = new ControlFlowFlatteningPass().run(
                method,
                ProtectionConfig.enabled(44));
        assertEquals("entry", rewritten.blocks().get(0).name());
        assertTrue(new IrMethodValidator().validate(rewritten).isEmpty());
    }

    @Test
    void slicesOversizedEligibleComponentAtTheRegionBudget() {
        ArrayList<IrBlock> blocks = new ArrayList<>();
        int blockCount = ControlFlowFlatteningRegion.MAX_MEMBER_BLOCKS + 8;
        for (int index = 0; index < blockCount; index++) {
            blocks.add(new IrBlock(
                    "b" + index,
                    List.of(),
                    IrTerminator.gotoBlock("b" + ((index + 1) % blockCount))));
        }
        IrMethod method = new IrMethod(
                "pkg/LargeCycle",
                "run",
                "()V",
                IrType.VOID,
                List.of(),
                blocks);

        ControlFlowFlatteningPlan plan = planner.plan(method, 45L);

        assertTrue(plan.selected());
        assertEquals(
                ControlFlowFlatteningRegion.MAX_MEMBER_BLOCKS,
                plan.regions().get(0).memberBlocks().size());
        assertEquals("b0", plan.regions().get(0).entryBlock());
    }

    @Test
    void modelRejectsOversizedAndOverlappingRegions() {
        ArrayList<String> members = new ArrayList<>();
        LinkedHashMap<String, Integer> states = new LinkedHashMap<>();
        for (int index = 0; index <= ControlFlowFlatteningRegion.MAX_MEMBER_BLOCKS; index++) {
            String name = "b" + index;
            members.add(name);
            states.put(name, index);
        }
        assertThrows(
                IllegalArgumentException.class,
                () -> new ControlFlowFlatteningRegion("region", "b0", members, states));

        ControlFlowFlatteningRegion first = region("first", "a", "b");
        ControlFlowFlatteningRegion second = region("second", "b", "c");
        assertThrows(
                IllegalArgumentException.class,
                () -> new ControlFlowFlatteningPlan(
                        "pkg/Test#run!()V",
                        List.of(first, second),
                        ControlFlowFlatteningRegionPlanner.APPLIED_REASON));
    }

    @Test
    void returnsStableStubReason() {
        IrMethod method = new IrMethod(
                "pkg/Stub",
                "<clinit>",
                "()V",
                IrType.VOID,
                List.of(),
                List.of(
                        new IrBlock("entry", List.of(), IrTerminator.gotoBlock("exit")),
                        new IrBlock("exit", List.of(), IrTerminator.returnVoid())));

        ControlFlowFlatteningPlan plan = planner.plan(method, 47L);

        assertFalse(plan.selected());
        assertEquals(ControlFlowFlatteningRegionPlanner.STUB_BACKED_REASON, plan.reasonCode());
    }

    private IrMethod threeBlockMethod() {
        IrValue condition = new IrValue("%condition", IrType.I1);
        IrValue yes = new IrValue("%yes", IrType.I32);
        IrValue no = new IrValue("%no", IrType.I32);
        return new IrMethod(
                "pkg/States",
                "choose",
                "(Z)I",
                IrType.I32,
                List.of(condition),
                List.of(
                        new IrBlock(
                                "entry",
                                List.of(),
                                IrTerminator.branch(condition, "yes", "no")),
                        new IrBlock(
                                "yes",
                                List.of(IrInstruction.constInt(yes, 1)),
                                IrTerminator.returnValue(yes)),
                        new IrBlock(
                                "no",
                                List.of(IrInstruction.constInt(no, 0)),
                                IrTerminator.returnValue(no))));
    }

    private ControlFlowFlatteningRegion region(String id, String entry, String other) {
        LinkedHashMap<String, Integer> states = new LinkedHashMap<>();
        states.put(entry, 0);
        states.put(other, 1);
        return new ControlFlowFlatteningRegion(id, entry, List.of(entry, other), states);
    }
}
