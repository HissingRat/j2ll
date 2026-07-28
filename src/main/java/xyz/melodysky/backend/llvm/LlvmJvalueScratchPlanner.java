package xyz.melodysky.backend.llvm;

import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import xyz.melodysky.ir.model.IrInstruction;
import xyz.melodysky.ir.model.IrMethod;
import xyz.melodysky.ir.model.IrValue;

/**
 * Computes the maximum synchronous JNI bridge argument count per function.
 */
final class LlvmJvalueScratchPlanner {
    LlvmJvalueScratchPlan plan(
            IrMethod method,
            Function<IrInstruction, List<IrValue>> argumentsAt) {
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(argumentsAt, "argumentsAt");
        int capacity = method.blocks().stream()
                .flatMap(block -> block.instructions().stream())
                .map(argumentsAt)
                .mapToInt(List::size)
                .max()
                .orElse(0);
        return new LlvmJvalueScratchPlan(capacity);
    }
}
