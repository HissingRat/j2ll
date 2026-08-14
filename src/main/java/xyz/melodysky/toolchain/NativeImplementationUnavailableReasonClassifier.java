package xyz.melodysky.toolchain;

import java.util.Objects;
import java.util.Optional;
import xyz.melodysky.ir.model.IrMethod;
import xyz.melodysky.ir.model.IrOpcode;

/** Stable reason codes for IR shapes with no final native implementation. */
public final class NativeImplementationUnavailableReasonClassifier {
    public static final String MULTIANEWARRAY_UNSUPPORTED =
            "MULTIANEWARRAY_UNSUPPORTED";

    public Optional<String> classify(IrMethod method) {
        Objects.requireNonNull(method, "method");
        if (method.blocks().stream()
                .flatMap(block -> block.instructions().stream())
                .anyMatch(instruction ->
                        instruction.opcode() == IrOpcode.NEW_MULTI_ARRAY)) {
            return Optional.of(MULTIANEWARRAY_UNSUPPORTED);
        }
        return Optional.empty();
    }
}
