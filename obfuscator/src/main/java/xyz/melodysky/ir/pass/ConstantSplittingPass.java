package xyz.melodysky.ir.pass;

import xyz.melodysky.ir.model.*;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class ConstantSplittingPass implements IrMethodPass {

    private final Random random;

    public ConstantSplittingPass() {
        this(new SecureRandom());
    }

    ConstantSplittingPass(Random random) {
        this.random = random;
    }

    @Override
    public String name() {
        return "constant-splitting";
    }

    @Override
    public IrMethod apply(IrMethod method) {
        int nextValueId = nextValueId(method);
        ArrayList<IrBlock> rewrittenBlocks = new ArrayList<>(method.blocks().size());
        for (IrBlock block : method.blocks()) {
            ArrayList<IrInstruction> rewrittenInstructions = new ArrayList<>(block.instructions().size());
            for (IrInstruction instruction : block.instructions()) {
                if (instruction instanceof IrInstruction.Const constant) {
                    SplitPlan splitPlan = trySplit(constant, nextValueId);
                    if (splitPlan != null) {
                        rewrittenInstructions.addAll(splitPlan.instructions());
                        nextValueId = splitPlan.nextValueId();
                        continue;
                    }
                }
                rewrittenInstructions.add(instruction);
            }
            rewrittenBlocks.add(new IrBlock(block.label(), rewrittenInstructions, block.terminator()));
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

    private SplitPlan trySplit(IrInstruction.Const constant, int nextValueId) {
        if (constant.result().type() == IrType.INT) {
            int literal = toIntLiteral(constant.value());
            if (!shouldSplit(literal)) {
                return null;
            }
            int leftLiteral = randomIntMask(literal);
            int rightLiteral = literal ^ leftLiteral;
            IrValue leftValue = new IrValue(nextValueId, IrType.INT, deriveDebugName(constant.result(), "kc_l"));
            IrValue rightValue = new IrValue(nextValueId + 1, IrType.INT, deriveDebugName(constant.result(), "kc_r"));
            return new SplitPlan(
                    List.of(
                            new IrInstruction.Const(leftValue, leftLiteral),
                            new IrInstruction.Const(rightValue, rightLiteral),
                            new IrInstruction.Binary(constant.result(), IrBinaryOpcode.XOR, leftValue, rightValue)
                    ),
                    nextValueId + 2
            );
        }
        if (constant.result().type() == IrType.LONG) {
            long literal = toLongLiteral(constant.value());
            if (!shouldSplit(literal)) {
                return null;
            }
            long leftLiteral = randomLongMask(literal);
            long rightLiteral = literal ^ leftLiteral;
            IrValue leftValue = new IrValue(nextValueId, IrType.LONG, deriveDebugName(constant.result(), "kc_l"));
            IrValue rightValue = new IrValue(nextValueId + 1, IrType.LONG, deriveDebugName(constant.result(), "kc_r"));
            return new SplitPlan(
                    List.of(
                            new IrInstruction.Const(leftValue, leftLiteral),
                            new IrInstruction.Const(rightValue, rightLiteral),
                            new IrInstruction.Binary(constant.result(), IrBinaryOpcode.XOR, leftValue, rightValue)
                    ),
                    nextValueId + 2
            );
        }
        return null;
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

    private static boolean shouldSplit(int literal) {
        return literal != 0 && literal != 1 && literal != -1;
    }

    private static boolean shouldSplit(long literal) {
        return literal != 0L && literal != 1L && literal != -1L;
    }

    private int randomIntMask(int literal) {
        int mask;
        do {
            mask = random.nextInt();
        } while (mask == 0 || mask == literal || (literal ^ mask) == 0);
        return mask;
    }

    private long randomLongMask(long literal) {
        long mask;
        do {
            mask = random.nextLong();
        } while (mask == 0L || mask == literal || (literal ^ mask) == 0L);
        return mask;
    }

    private static int toIntLiteral(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        throw new IllegalArgumentException("Expected int literal but saw " + value);
    }

    private static long toLongLiteral(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        throw new IllegalArgumentException("Expected long literal but saw " + value);
    }

    private static String deriveDebugName(IrValue original, String suffix) {
        if (original.debugName() == null || original.debugName().isBlank()) {
            return suffix;
        }
        return original.debugName() + "_" + suffix;
    }

    private record SplitPlan(List<IrInstruction> instructions, int nextValueId) {
    }
}
