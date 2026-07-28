package xyz.melodysky.backend.llvm;

import java.util.Objects;
import xyz.melodysky.ir.model.IrOpcode;

/**
 * Shared LLVM/JNI ABI rules that must agree between native planning, LLVM
 * lowering and the generated C bridge.
 */
public final class LlvmFunctionAbiPolicy {
    private LlvmFunctionAbiPolicy() {
    }

    public static boolean literalOrClassObjectRequiresJniEnv(IrOpcode opcode) {
        return switch (Objects.requireNonNull(opcode, "opcode")) {
            case CONST_STRING, CONST_CLASS, CLASS_OBJECT -> true;
            default -> false;
        };
    }
}
