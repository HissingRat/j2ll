package xyz.melodysky.runtime.jni;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/**
 * Per-binding physical parameter layout for an internal JNI helper.
 *
 * <p>The logical parameters retain their original representation and
 * ownership. The plan only permutes those real parameters; it does not add
 * marker or integrity-check arguments.</p>
 */
public record RuntimeLocalAbiPlan(
        int logicalParameterCount,
        List<Integer> physicalSlots) {
    public RuntimeLocalAbiPlan {
        if (logicalParameterCount < 0) {
            throw new IllegalArgumentException(
                    "logicalParameterCount must not be negative");
        }
        physicalSlots = List.copyOf(
                Objects.requireNonNull(physicalSlots, "physicalSlots"));
        if (physicalSlots.size() != logicalParameterCount) {
            throw new IllegalArgumentException(
                    "local ABI must contain every logical parameter");
        }
        HashSet<Integer> seen = new HashSet<>();
        for (int slot : physicalSlots) {
            if (slot < 0
                    || slot >= logicalParameterCount
                    || !seen.add(slot)) {
                throw new IllegalArgumentException(
                        "local ABI physical slots must be a permutation");
            }
        }
    }

    /**
     * Arranges typed declarations or operands into the physical call order.
     */
    public <T> List<T> arrange(List<T> logicalParameters) {
        Objects.requireNonNull(logicalParameters, "logicalParameters");
        if (logicalParameters.size() != logicalParameterCount) {
            throw new IllegalArgumentException(
                    "logical parameter count does not match local ABI plan");
        }
        ArrayList<T> arranged = new ArrayList<>(physicalSlots.size());
        for (int slot : physicalSlots) {
            arranged.add(logicalParameters.get(slot));
        }
        return List.copyOf(arranged);
    }
}
