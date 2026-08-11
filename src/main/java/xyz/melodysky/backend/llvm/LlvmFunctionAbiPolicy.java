package xyz.melodysky.backend.llvm;

import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import xyz.melodysky.ir.model.IrInstruction;
import xyz.melodysky.ir.model.IrMethod;
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

    /**
     * Returns the exact SSA values produced by {@link IrOpcode#CONST_NULL} in
     * {@code method}. Only these direct values may use a native pointer/null
     * comparison; arbitrary JNI reference handles require {@code IsSameObject}.
     */
    public static Set<String> directNullReferenceValueNames(IrMethod method) {
        Objects.requireNonNull(method, "method");
        return method.blocks().stream()
                .flatMap(block -> block.instructions().stream())
                .filter(instruction -> instruction.opcode() == IrOpcode.CONST_NULL)
                .flatMap(instruction -> instruction.result().stream())
                .map(value -> value.name())
                .collect(Collectors.toUnmodifiableSet());
    }

    /**
     * Whether one reference-identity instruction must receive {@code JNIEnv*}
     * so lowering can call JNI {@code IsSameObject}.
     */
    public static boolean referenceComparisonRequiresJniEnv(
            IrInstruction instruction,
            Set<String> directNullReferenceValueNames) {
        Objects.requireNonNull(instruction, "instruction");
        Objects.requireNonNull(
                directNullReferenceValueNames,
                "directNullReferenceValueNames");
        if (instruction.opcode() != IrOpcode.CMP_EQ_REF
                && instruction.opcode() != IrOpcode.CMP_NE_REF) {
            return false;
        }
        return instruction.operands().stream()
                .noneMatch(operand -> directNullReferenceValueNames.contains(
                        operand.name()));
    }

    public static boolean referenceComparisonsRequireJniEnv(IrMethod method) {
        Objects.requireNonNull(method, "method");
        Set<String> directNullValues = directNullReferenceValueNames(method);
        return method.blocks().stream()
                .flatMap(block -> block.instructions().stream())
                .anyMatch(instruction -> referenceComparisonRequiresJniEnv(
                        instruction,
                        directNullValues));
    }
}
