package xyz.melodysky.backend.llvm;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.ToIntFunction;
import xyz.melodysky.backend.llvm.model.LlvmInstruction;
import xyz.melodysky.ir.model.IrValue;

/**
 * One fixed JNI {@code jvalue[]} scratch slot reused by a native activation.
 */
record LlvmJvalueScratchPlan(int capacity) {
    static final String STORAGE = "%j2ll_jvalue_scratch";

    LlvmJvalueScratchPlan {
        if (capacity < 0) {
            throw new IllegalArgumentException(
                    "jvalue scratch capacity must not be negative");
        }
    }

    boolean required() {
        return capacity > 0;
    }

    LlvmInstruction allocation() {
        if (!required()) {
            throw new IllegalStateException(
                    "empty jvalue scratch plan has no allocation");
        }
        return LlvmInstruction.raw(
                Optional.of(STORAGE),
                "alloca [" + capacity + " x i64], align 8");
    }

    ScratchUse use(
            List<IrValue> arguments,
            String suffix,
            Function<IrValue, String> typedOperand,
            ToIntFunction<IrValue> storeAlignment) {
        arguments = List.copyOf(arguments);
        if (arguments.isEmpty()) {
            return new ScratchUse("ptr null", List.of());
        }
        if (arguments.size() > capacity) {
            throw new IllegalArgumentException(
                    "jvalue scratch capacity "
                            + capacity
                            + " is smaller than call-site arity "
                            + arguments.size());
        }

        String arrayType = "[" + capacity + " x i64]";
        String base = "%j2ll_args_base_" + suffix;
        ArrayList<LlvmInstruction> instructions = new ArrayList<>();
        instructions.add(LlvmInstruction.raw(
                Optional.of(base),
                "getelementptr inbounds "
                        + arrayType
                        + ", ptr "
                        + STORAGE
                        + ", i32 0, i32 0"));
        for (int index = 0; index < arguments.size(); index++) {
            IrValue argument = arguments.get(index);
            String slot = "%j2ll_arg_" + suffix + "_" + index;
            instructions.add(LlvmInstruction.raw(
                    Optional.of(slot),
                    "getelementptr inbounds i64, ptr "
                            + base
                            + ", i32 "
                            + index));
            instructions.add(LlvmInstruction.raw(
                    Optional.empty(),
                    "store "
                            + typedOperand.apply(argument)
                            + ", ptr "
                            + slot
                            + ", align "
                            + storeAlignment.applyAsInt(argument)));
        }
        return new ScratchUse(
                "ptr " + base,
                instructions);
    }

    record ScratchUse(
            String pointerOperand,
            List<LlvmInstruction> instructions) {
        ScratchUse {
            instructions = List.copyOf(instructions);
        }
    }
}
