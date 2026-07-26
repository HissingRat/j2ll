package xyz.melodysky.ir.pass.protection;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import xyz.melodysky.analysis.field.FieldReferenceKind;
import xyz.melodysky.analysis.field.NativeFieldInternalizationDecision;
import xyz.melodysky.analysis.field.NativeFieldInternalizationPlan;
import xyz.melodysky.ir.model.IrInstruction;
import xyz.melodysky.ir.model.IrMethod;
import xyz.melodysky.ir.model.IrOpcode;
import xyz.melodysky.ir.model.NativeFieldSlotRef;

final class NativeFieldIrAccessVerifier {
    List<String> verifyInput(
            Map<String, IrMethod> methods,
            NativeFieldInternalizationPlan plan) {
        return verifyInput(methods, plan, methods.keySet());
    }

    List<String> verifyInput(
            Map<String, IrMethod> methods,
            NativeFieldInternalizationPlan plan,
            Set<String> llvmMethodKeys) {
        ExpectedAccesses expected = expectedAccesses(plan, llvmMethodKeys);
        TreeSet<String> issues = new TreeSet<>(expected.issues());
        TreeMap<AccessCountKey, Integer> actual = new TreeMap<>();
        for (IrMethod method : methods.values()) {
            if (!llvmMethodKeys.contains(method.methodKey())) {
                continue;
            }
            for (IrInstruction instruction : instructions(method)) {
                String symbol = instruction.symbol().orElse(null);
                String slot = symbol == null ? null : expected.slotByField().get(symbol);
                if (slot == null) {
                    if (symbol != null
                            && expected.approvedSlots().contains(symbol)
                            && isNativeFieldOpcode(instruction.opcode())) {
                        issues.add("approved slot is already rewritten before field internalization: "
                                + slotLabel(symbol, method.methodKey()));
                    }
                    continue;
                }
                AccessOperation operation = rawOperation(instruction.opcode());
                if (operation == null) {
                    issues.add("approved field has unexpected input opcode "
                            + instruction.opcode() + ": " + slotLabel(slot, method.methodKey()));
                    continue;
                }
                increment(actual, new AccessCountKey(method.methodKey(), slot, operation));
            }
        }
        compare(expected.counts(), actual, "input", issues);
        return List.copyOf(issues);
    }

    List<String> verifyOutput(
            Map<String, IrMethod> methods,
            NativeFieldInternalizationPlan plan) {
        return verifyOutput(methods, plan, methods.keySet());
    }

    List<String> verifyOutput(
            Map<String, IrMethod> methods,
            NativeFieldInternalizationPlan plan,
            Set<String> llvmMethodKeys) {
        ExpectedAccesses expected = expectedAccesses(plan, llvmMethodKeys);
        TreeSet<String> issues = new TreeSet<>(expected.issues());
        TreeMap<AccessCountKey, Integer> actual = new TreeMap<>();
        for (IrMethod method : methods.values()) {
            if (!llvmMethodKeys.contains(method.methodKey())) {
                continue;
            }
            for (IrInstruction instruction : instructions(method)) {
                String symbol = instruction.symbol().orElse(null);
                if (symbol == null) {
                    continue;
                }
                String approvedSlot = expected.slotByField().get(symbol);
                if (approvedSlot != null && isRawFieldOpcode(instruction.opcode())) {
                    issues.add("approved field access remains after rewrite: "
                            + slotLabel(approvedSlot, method.methodKey()));
                    continue;
                }
                if (!expected.approvedSlots().contains(symbol)) {
                    continue;
                }
                AccessOperation operation = nativeOperation(instruction.opcode());
                if (operation == null) {
                    issues.add("approved slot has unexpected output opcode "
                            + instruction.opcode() + ": " + slotLabel(symbol, method.methodKey()));
                    continue;
                }
                increment(actual, new AccessCountKey(method.methodKey(), symbol, operation));
            }
        }
        compare(expected.counts(), actual, "output", issues);
        return List.copyOf(issues);
    }

    private ExpectedAccesses expectedAccesses(
            NativeFieldInternalizationPlan plan,
            Set<String> llvmMethodKeys) {
        HashMap<String, String> slotByField = new HashMap<>();
        TreeSet<String> approvedSlots = new TreeSet<>();
        TreeMap<AccessCountKey, Integer> counts = new TreeMap<>();
        TreeSet<String> issues = new TreeSet<>();
        for (NativeFieldInternalizationDecision decision : plan.internalizedFields()) {
            String slot = new NativeFieldSlotRef(
                            plan.storageKind(decision),
                            decision.nativeSlotId().orElseThrow(),
                            plan.referenceIndex(decision))
                    .encoded();
            slotByField.put(decision.field().fieldKey(), slot);
            approvedSlots.add(slot);
            if (decision.accesses().isEmpty()) {
                issues.add("approved slot has no planned accesses: " + slot);
            }
            for (var access : decision.accesses()) {
                if (!llvmMethodKeys.contains(access.methodKey())) {
                    continue;
                }
                AccessOperation operation = plannedOperation(access.referenceKind());
                if (operation == null) {
                    issues.add("approved access is not a direct static field operation: "
                            + slotLabel(slot, access.methodKey()));
                    continue;
                }
                increment(counts, new AccessCountKey(access.methodKey(), slot, operation));
            }
        }
        return new ExpectedAccesses(
                Map.copyOf(slotByField),
                Set.copyOf(approvedSlots),
                Map.copyOf(counts),
                List.copyOf(issues));
    }

    private void compare(
            Map<AccessCountKey, Integer> expected,
            Map<AccessCountKey, Integer> actual,
            String stage,
            Set<String> issues) {
        TreeSet<AccessCountKey> keys = new TreeSet<>(expected.keySet());
        keys.addAll(actual.keySet());
        for (AccessCountKey key : keys) {
            int expectedCount = expected.getOrDefault(key, 0);
            int actualCount = actual.getOrDefault(key, 0);
            if (expectedCount != actualCount) {
                issues.add(stage + " access count mismatch for "
                        + slotLabel(key.slot(), key.methodKey())
                        + " " + key.operation().wireName()
                        + ": expected=" + expectedCount + ", actual=" + actualCount);
            }
        }
    }

    private List<IrInstruction> instructions(IrMethod method) {
        return method.blocks().stream()
                .flatMap(block -> block.instructions().stream())
                .toList();
    }

    private void increment(Map<AccessCountKey, Integer> counts, AccessCountKey key) {
        counts.merge(key, 1, Integer::sum);
    }

    private AccessOperation plannedOperation(FieldReferenceKind kind) {
        return switch (kind) {
            case BYTECODE_STATIC_READ -> AccessOperation.READ;
            case BYTECODE_STATIC_WRITE -> AccessOperation.WRITE;
            default -> null;
        };
    }

    private AccessOperation rawOperation(IrOpcode opcode) {
        return switch (opcode) {
            case GET_STATIC -> AccessOperation.READ;
            case PUT_STATIC -> AccessOperation.WRITE;
            default -> null;
        };
    }

    private AccessOperation nativeOperation(IrOpcode opcode) {
        return switch (opcode) {
            case GET_NATIVE_STATIC -> AccessOperation.READ;
            case PUT_NATIVE_STATIC -> AccessOperation.WRITE;
            default -> null;
        };
    }

    private boolean isRawFieldOpcode(IrOpcode opcode) {
        return opcode == IrOpcode.GET_STATIC
                || opcode == IrOpcode.PUT_STATIC
                || opcode == IrOpcode.GET_FIELD
                || opcode == IrOpcode.PUT_FIELD;
    }

    private boolean isNativeFieldOpcode(IrOpcode opcode) {
        return opcode == IrOpcode.GET_NATIVE_STATIC || opcode == IrOpcode.PUT_NATIVE_STATIC;
    }

    private String slotLabel(String slot, String methodKey) {
        return slot + " in " + methodKey;
    }

    private enum AccessOperation {
        READ("read"),
        WRITE("write");

        private final String wireName;

        AccessOperation(String wireName) {
            this.wireName = wireName;
        }

        String wireName() {
            return wireName;
        }
    }

    private record AccessCountKey(
            String methodKey,
            String slot,
            AccessOperation operation) implements Comparable<AccessCountKey> {
        @Override
        public int compareTo(AccessCountKey other) {
            int byMethod = methodKey.compareTo(other.methodKey);
            if (byMethod != 0) {
                return byMethod;
            }
            int bySlot = slot.compareTo(other.slot);
            return bySlot != 0 ? bySlot : operation.compareTo(other.operation);
        }
    }

    private record ExpectedAccesses(
            Map<String, String> slotByField,
            Set<String> approvedSlots,
            Map<AccessCountKey, Integer> counts,
            List<String> issues) {}
}
