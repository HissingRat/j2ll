package xyz.melodysky.runtime.jni;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import xyz.melodysky.runtime.RuntimeTokenDomain;
import xyz.melodysky.runtime.RuntimeTokenMapper;

/**
 * Derives a binding-specific internal helper ABI from the invocation build
 * key owned by {@link RuntimeTokenMapper}.
 */
public final class RuntimeLocalAbiPlanner {
    public RuntimeLocalAbiPlan plan(
            RuntimeTokenMapper runtimeTokens,
            RuntimeLocalAbiDomain domain,
            String operation,
            String identity,
            int logicalParameterCount) {
        Objects.requireNonNull(runtimeTokens, "runtimeTokens");
        Objects.requireNonNull(domain, "domain");
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(identity, "identity");
        if (!operation.matches("[a-z0-9_]+")) {
            throw new IllegalArgumentException(
                    "local ABI operation must be a safe identifier token");
        }
        if (logicalParameterCount < 0) {
            throw new IllegalArgumentException(
                    "logicalParameterCount must not be negative");
        }

        String binding = domain.name()
                + "\0"
                + operation
                + "\0"
                + identity;
        ArrayList<Integer> slots = new ArrayList<>(
                logicalParameterCount);
        for (int index = 0; index < logicalParameterCount; index++) {
            slots.add(index);
        }
        List<Integer> physicalSlots = runtimeTokens.physicalOrder(
                RuntimeTokenDomain.JNI_LOCAL_ABI,
                slots,
                slot -> "parameter-order\0"
                        + binding
                        + "\0"
                        + slot);
        return new RuntimeLocalAbiPlan(
                logicalParameterCount,
                physicalSlots);
    }
}
