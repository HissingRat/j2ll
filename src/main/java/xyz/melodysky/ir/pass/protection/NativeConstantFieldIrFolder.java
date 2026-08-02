package xyz.melodysky.ir.pass.protection;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import xyz.melodysky.analysis.field.FieldReferenceKind;
import xyz.melodysky.analysis.field.NativeFieldConstant;
import xyz.melodysky.analysis.field.NativeFieldInternalizationDecision;
import xyz.melodysky.analysis.field.NativeFieldInternalizationPlan;
import xyz.melodysky.ir.model.IrInstruction;
import xyz.melodysky.ir.model.IrMethod;
import xyz.melodysky.ir.model.IrOpcode;
import xyz.melodysky.ir.model.IrType;

/** Fail-closed verifier and folder for approved ConstantValue fields. */
final class NativeConstantFieldIrFolder {
    List<String> verifyInput(
            Map<String, IrMethod> methods,
            NativeFieldInternalizationPlan plan,
            Set<String> llvmMethodKeys) {
        Map<String, NativeFieldInternalizationDecision> constants = constants(plan);
        if (constants.isEmpty()) {
            return List.of();
        }
        TreeSet<String> issues = new TreeSet<>();
        TreeMap<AccessKey, Integer> expected = expected(plan, llvmMethodKeys, issues);
        TreeMap<AccessKey, Integer> actual = new TreeMap<>();
        for (IrMethod method : methods.values()) {
            for (IrInstruction instruction : instructions(method)) {
                String fieldKey = instruction.symbol().orElse(null);
                NativeFieldInternalizationDecision decision =
                        fieldKey == null ? null : constants.get(fieldKey);
                if (decision == null) {
                    continue;
                }
                if (!llvmMethodKeys.contains(method.methodKey())) {
                    issues.add("constant field access is outside the LLVM method set: "
                            + fieldKey + " in " + method.methodKey());
                    continue;
                }
                if (instruction.opcode() != IrOpcode.GET_STATIC) {
                    issues.add("constant field has unexpected input opcode "
                            + instruction.opcode() + ": " + fieldKey
                            + " in " + method.methodKey());
                    continue;
                }
                if (decision.constant().orElseThrow().stringConstant()) {
                    issues.add("String ConstantValue GETSTATIC cannot be folded without "
                            + "intern-preserving identity: " + fieldKey
                            + " in " + method.methodKey());
                    continue;
                }
                verifyResultType(instruction, decision.constant().orElseThrow(), method, issues);
                increment(actual, new AccessKey(method.methodKey(), fieldKey));
            }
        }
        compare(expected, actual, "input", issues);
        return List.copyOf(issues);
    }

    List<String> verifyOutput(
            Map<String, IrMethod> methods,
            NativeFieldInternalizationPlan plan) {
        Set<String> fieldKeys = constants(plan).keySet();
        if (fieldKeys.isEmpty()) {
            return List.of();
        }
        TreeSet<String> issues = new TreeSet<>();
        for (IrMethod method : methods.values()) {
            for (IrInstruction instruction : instructions(method)) {
                if (instruction.symbol().map(fieldKeys::contains).orElse(false)
                        && isFieldOpcode(instruction.opcode())) {
                    issues.add("constant field access remains after fold: "
                            + instruction.symbol().orElseThrow()
                            + " in " + method.methodKey());
                }
            }
        }
        return List.copyOf(issues);
    }

    FoldMethodResult fold(
            IrInstruction instruction,
            NativeFieldInternalizationDecision decision) {
        NativeFieldConstant constant = decision.constant().orElseThrow();
        var result = instruction.result().orElseThrow();
        if (constant.stringConstant()) {
            throw new IllegalStateException(
                    "String ConstantValue reads require intern-preserving identity");
        }
        IrInstruction folded = switch (constant.descriptor()) {
            case "Z", "B", "S", "C", "I" ->
                    IrInstruction.constInt(result, constant.intValue());
            case "J" -> IrInstruction.constLong(result, constant.longValue());
            case "F" -> IrInstruction.constFloat(result, constant.floatValue());
            case "D" -> IrInstruction.constDouble(result, constant.doubleValue());
            default -> throw new IllegalStateException(
                    "unsupported approved field constant descriptor: "
                            + constant.descriptor());
        };
        return new FoldMethodResult(List.of(folded));
    }

    private TreeMap<AccessKey, Integer> expected(
            NativeFieldInternalizationPlan plan,
            Set<String> llvmMethodKeys,
            Set<String> issues) {
        TreeMap<AccessKey, Integer> expected = new TreeMap<>();
        for (NativeFieldInternalizationDecision decision
                : plan.constantFoldedFields()) {
            for (var access : decision.accesses()) {
                if (!llvmMethodKeys.contains(access.methodKey())) {
                    issues.add("approved constant field accessor is not LLVM native: "
                            + decision.field().fieldKey() + " in " + access.methodKey());
                    continue;
                }
                if (access.referenceKind() != FieldReferenceKind.BYTECODE_STATIC_READ) {
                    issues.add("approved constant field access is not a direct static read: "
                            + decision.field().fieldKey() + " in " + access.methodKey());
                    continue;
                }
                increment(
                        expected,
                        new AccessKey(
                                access.methodKey(),
                                decision.field().fieldKey()));
            }
        }
        return expected;
    }

    private void verifyResultType(
            IrInstruction instruction,
            NativeFieldConstant constant,
            IrMethod method,
            Set<String> issues) {
        IrType expected = switch (constant.descriptor()) {
            case "Z", "B", "S", "C", "I" -> IrType.I32;
            case "J" -> IrType.I64;
            case "F" -> IrType.F32;
            case "D" -> IrType.F64;
            case "Ljava/lang/String;" -> IrType.REFERENCE;
            default -> throw new IllegalStateException(
                    "unsupported approved constant descriptor");
        };
        if (instruction.result().isEmpty()
                || instruction.result().orElseThrow().type() != expected
                || !instruction.operands().isEmpty()) {
            issues.add("constant field read has invalid SSA shape: "
                    + instruction.symbol().orElse("<missing>")
                    + " in " + method.methodKey());
        }
    }

    private Map<String, NativeFieldInternalizationDecision> constants(
            NativeFieldInternalizationPlan plan) {
        HashMap<String, NativeFieldInternalizationDecision> result =
                new HashMap<>();
        plan.constantFoldedFields().forEach(decision ->
                result.put(decision.field().fieldKey(), decision));
        return Map.copyOf(result);
    }

    private List<IrInstruction> instructions(IrMethod method) {
        return method.blocks().stream()
                .flatMap(block -> block.instructions().stream())
                .toList();
    }

    private boolean isFieldOpcode(IrOpcode opcode) {
        return opcode == IrOpcode.GET_STATIC
                || opcode == IrOpcode.PUT_STATIC
                || opcode == IrOpcode.GET_FIELD
                || opcode == IrOpcode.PUT_FIELD
                || opcode == IrOpcode.GET_NATIVE_STATIC
                || opcode == IrOpcode.PUT_NATIVE_STATIC;
    }

    private void increment(Map<AccessKey, Integer> counts, AccessKey key) {
        counts.merge(key, 1, Integer::sum);
    }

    private void compare(
            Map<AccessKey, Integer> expected,
            Map<AccessKey, Integer> actual,
            String stage,
            Set<String> issues) {
        TreeSet<AccessKey> keys = new TreeSet<>(expected.keySet());
        keys.addAll(actual.keySet());
        for (AccessKey key : keys) {
            int expectedCount = expected.getOrDefault(key, 0);
            int actualCount = actual.getOrDefault(key, 0);
            if (expectedCount != actualCount) {
                issues.add(stage + " constant-read count mismatch for "
                        + key.fieldKey() + " in " + key.methodKey()
                        + ": expected=" + expectedCount
                        + ", actual=" + actualCount);
            }
        }
    }

    record FoldMethodResult(List<IrInstruction> instructions) {
        FoldMethodResult {
            instructions = List.copyOf(instructions);
        }
    }

    private record AccessKey(
            String methodKey,
            String fieldKey) implements Comparable<AccessKey> {
        @Override
        public int compareTo(AccessKey other) {
            int byMethod = methodKey.compareTo(other.methodKey);
            return byMethod != 0 ? byMethod : fieldKey.compareTo(other.fieldKey);
        }
    }
}
