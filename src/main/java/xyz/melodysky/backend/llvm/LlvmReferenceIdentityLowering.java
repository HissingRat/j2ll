package xyz.melodysky.backend.llvm;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import xyz.melodysky.backend.llvm.model.LlvmInstruction;
import xyz.melodysky.ir.model.IrInstruction;
import xyz.melodysky.ir.model.IrMethod;
import xyz.melodysky.ir.model.IrOpcode;
import xyz.melodysky.ir.model.IrType;

/** Lowers Java reference identity without comparing opaque JNI handles. */
final class LlvmReferenceIdentityLowering {
    private final Set<String> directNullValues;
    private final Set<String> reservedValueNames;
    private final String helperSymbol;

    LlvmReferenceIdentityLowering(
            IrMethod method,
            LlvmFunctionAbi functionAbi,
            String helperSymbol) {
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(functionAbi, "functionAbi");
        this.helperSymbol = Objects.requireNonNull(helperSymbol, "helperSymbol");
        if (helperSymbol.isBlank()) {
            throw new IllegalArgumentException(
                    "reference identity helper symbol must not be blank");
        }
        directNullValues = LlvmFunctionAbiPolicy
                .directNullReferenceValueNames(method);
        reservedValueNames = collectValueNames(method);
        if (LlvmFunctionAbiPolicy.referenceComparisonsRequireJniEnv(method)
                && !functionAbi.passesJniEnv()) {
            throw new IllegalArgumentException(
                    "LLVM reference identity lowering requires JNIEnv*: "
                            + method.methodKey());
        }
    }

    List<LlvmInstruction> lower(IrInstruction instruction) {
        requireReferenceComparison(instruction);
        String result = instruction.result().orElseThrow().name();
        String left = instruction.operands().get(0).name();
        String right = instruction.operands().get(1).name();
        if (!LlvmFunctionAbiPolicy.referenceComparisonRequiresJniEnv(
                instruction,
                directNullValues)) {
            return List.of(LlvmInstruction.rawProvenNoNativeUnwind(
                    Optional.of(result),
                    "icmp " + pointerPredicate(instruction.opcode())
                            + " ptr " + left + ", " + right));
        }

        String identityResult = freshTemporary(result + ".j2ll.is_same");
        return List.of(
                LlvmInstruction.rawProvenNoNativeUnwind(
                        Optional.of(identityResult),
                        "call i32 @" + helperSymbol
                                + "(ptr %j2ll_env, ptr " + left
                                + ", ptr " + right + ")"),
                LlvmInstruction.rawProvenNoNativeUnwind(
                        Optional.of(result),
                        "icmp " + normalizedResultPredicate(
                                instruction.opcode())
                                + " i32 " + identityResult + ", 0"));
    }

    private void requireReferenceComparison(IrInstruction instruction) {
        Objects.requireNonNull(instruction, "instruction");
        if (instruction.opcode() != IrOpcode.CMP_EQ_REF
                && instruction.opcode() != IrOpcode.CMP_NE_REF) {
            throw new IllegalArgumentException(
                    "not a reference identity comparison: "
                            + instruction.opcode());
        }
        if (instruction.operands().size() != 2
                || instruction.operands().stream()
                        .anyMatch(value -> value.type() != IrType.REFERENCE)
                || instruction.result()
                        .map(value -> value.type() != IrType.I1)
                        .orElse(true)) {
            throw new IllegalArgumentException(
                    "invalid reference identity comparison shape");
        }
    }

    private String pointerPredicate(IrOpcode opcode) {
        return opcode == IrOpcode.CMP_EQ_REF ? "eq" : "ne";
    }

    private String normalizedResultPredicate(IrOpcode opcode) {
        return opcode == IrOpcode.CMP_EQ_REF ? "ne" : "eq";
    }

    private String freshTemporary(String base) {
        String candidate = base;
        int suffix = 1;
        while (!reservedValueNames.add(candidate)) {
            candidate = base + "." + suffix++;
        }
        return candidate;
    }

    private Set<String> collectValueNames(IrMethod method) {
        HashSet<String> names = new HashSet<>();
        method.parameters().forEach(value -> names.add(value.name()));
        method.blocks().forEach(block -> {
            block.parameters().forEach(value -> names.add(value.name()));
            block.instructions().stream()
                    .flatMap(instruction -> instruction.result().stream())
                    .forEach(value -> names.add(value.name()));
        });
        return names;
    }
}
