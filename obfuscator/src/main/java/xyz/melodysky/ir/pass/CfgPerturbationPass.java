package xyz.melodysky.ir.pass;

import xyz.melodysky.ir.model.IrBlock;
import xyz.melodysky.ir.model.IrCompareOpcode;
import xyz.melodysky.ir.model.IrInstruction;
import xyz.melodysky.ir.model.IrMethod;
import xyz.melodysky.ir.model.IrTerminator;
import xyz.melodysky.ir.model.IrType;
import xyz.melodysky.ir.model.IrValue;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class CfgPerturbationPass implements IrMethodPass {

    private final Random random;

    public CfgPerturbationPass() {
        this(new SecureRandom());
    }

    CfgPerturbationPass(Random random) {
        this.random = random;
    }

    @Override
    public String name() {
        return "cfg-perturbation";
    }

    @Override
    public IrMethod apply(IrMethod method) {
        int nextValueId = nextValueId(method);
        int nextEdgeId = 0;
        ArrayList<IrBlock> rewrittenBlocks = new ArrayList<>(method.blocks().size() * 3);
        for (IrBlock block : method.blocks()) {
            ArrayList<IrBlock> syntheticBlocks = new ArrayList<>();
            IrTerminator rewrittenTerminator = switch (block.terminator()) {
                case IrTerminator.Goto goTo -> {
                    EdgeRewrite rewrite = rewriteEdge(block.label(), "goto", nextEdgeId++, goTo.targetBlock(), nextValueId);
                    nextValueId = rewrite.nextValueId();
                    syntheticBlocks.addAll(rewrite.syntheticBlocks());
                    yield new IrTerminator.Goto(rewrite.entryLabel());
                }
                case IrTerminator.Branch branch -> {
                    EdgeRewrite trueRewrite = rewriteEdge(block.label(), "true", nextEdgeId++, branch.trueTarget(), nextValueId);
                    nextValueId = trueRewrite.nextValueId();
                    syntheticBlocks.addAll(trueRewrite.syntheticBlocks());
                    EdgeRewrite falseRewrite = rewriteEdge(block.label(), "false", nextEdgeId++, branch.falseTarget(), nextValueId);
                    nextValueId = falseRewrite.nextValueId();
                    syntheticBlocks.addAll(falseRewrite.syntheticBlocks());
                    yield new IrTerminator.Branch(branch.condition(), trueRewrite.entryLabel(), falseRewrite.entryLabel());
                }
                case IrTerminator.Switch switchTerminator -> switchTerminator;
                case IrTerminator.Return returnTerminator -> returnTerminator;
                case IrTerminator.ReturnVoid returnVoid -> returnVoid;
                case IrTerminator.Throw throwTerminator -> throwTerminator;
                case IrTerminator.Unreachable unreachable -> unreachable;
            };
            rewrittenBlocks.add(new IrBlock(block.label(), block.instructions(), rewrittenTerminator));
            rewrittenBlocks.addAll(syntheticBlocks);
        }
        return new IrMethod(
                method.name(),
                method.returnType(),
                method.parameterTypes(),
                method.maxLocals(),
                method.isStatic(),
                method.isPrivate(),
                method.isFinal(),
                method.entryBlock(),
                rewrittenBlocks
        );
    }

    private EdgeRewrite rewriteEdge(String ownerLabel, String edgeKind, int edgeId, String actualTarget, int nextValueId) {
        String entryLabel = ownerLabel + "_cf_" + edgeKind + "_" + edgeId;
        String fallbackLabel = entryLabel + "_fallback";
        int seed = randomNonZeroInt();
        IrValue left = new IrValue(nextValueId, IrType.INT, "cf_left");
        IrValue right = new IrValue(nextValueId + 1, IrType.INT, "cf_right");
        IrValue condition = new IrValue(nextValueId + 2, IrType.BOOLEAN, "cf_cond");
        IrBlock entryBlock = new IrBlock(
                entryLabel,
                List.of(
                        new IrInstruction.Const(left, seed),
                        new IrInstruction.Const(right, seed),
                        new IrInstruction.Compare(condition, IrCompareOpcode.EQ, left, right)
                ),
                new IrTerminator.Branch(condition, actualTarget, fallbackLabel)
        );
        IrBlock fallbackBlock = new IrBlock(
                fallbackLabel,
                List.of(),
                new IrTerminator.Goto(actualTarget)
        );
        return new EdgeRewrite(entryLabel, List.of(entryBlock, fallbackBlock), nextValueId + 3);
    }

    private int randomNonZeroInt() {
        int value;
        do {
            value = random.nextInt();
        } while (value == 0);
        return value;
    }

    private static int nextValueId(IrMethod method) {
        int maxId = -1;
        for (IrBlock block : method.blocks()) {
            for (IrInstruction instruction : block.instructions()) {
                maxId = Math.max(maxId, maxValueId(instruction));
            }
            maxId = Math.max(maxId, maxValueId(block.terminator()));
        }
        return maxId + 1;
    }

    private static int maxValueId(IrInstruction instruction) {
        return switch (instruction) {
            case IrInstruction.Const constant -> constant.result().id();
            case IrInstruction.LoadLocal loadLocal -> loadLocal.result().id();
            case IrInstruction.StoreLocal storeLocal -> storeLocal.value().id();
            case IrInstruction.Binary binary -> max(binary.result().id(), binary.left().id(), binary.right().id());
            case IrInstruction.Compare compare -> max(compare.result().id(), compare.left().id(), compare.right().id());
            case IrInstruction.Convert convert -> max(convert.result().id(), convert.value().id());
            case IrInstruction.LoadField loadField -> max(loadField.result().id(), loadField.owner().id());
            case IrInstruction.LoadStaticField loadStaticField -> loadStaticField.result().id();
            case IrInstruction.NewObject newObject -> newObject.result().id();
            case IrInstruction.StoreField storeField -> max(storeField.owner().id(), storeField.value().id());
            case IrInstruction.StoreStaticField storeStaticField -> storeStaticField.value().id();
            case IrInstruction.Invoke invoke -> max(invoke.result().id(), invoke.arguments());
            case IrInstruction.CallHelper helper -> max(helper.result().id(), helper.arguments());
            case IrInstruction.CallHelperVoid helper -> max(helper.arguments());
        };
    }

    private static int maxValueId(IrTerminator terminator) {
        return switch (terminator) {
            case IrTerminator.Goto ignored -> -1;
            case IrTerminator.Branch branch -> branch.condition().id();
            case IrTerminator.Switch switchTerminator -> switchTerminator.selector().id();
            case IrTerminator.Return returnTerminator -> returnTerminator.value().id();
            case IrTerminator.ReturnVoid ignored -> -1;
            case IrTerminator.Throw throwTerminator -> throwTerminator.exceptionValue().id();
            case IrTerminator.Unreachable ignored -> -1;
        };
    }

    private static int max(int... values) {
        int max = -1;
        for (int value : values) {
            max = Math.max(max, value);
        }
        return max;
    }

    private static int max(List<IrValue> values) {
        int max = -1;
        for (IrValue value : values) {
            max = Math.max(max, value.id());
        }
        return max;
    }

    private static int max(int first, List<IrValue> values) {
        return Math.max(first, max(values));
    }

    private record EdgeRewrite(String entryLabel, List<IrBlock> syntheticBlocks, int nextValueId) {
    }
}
