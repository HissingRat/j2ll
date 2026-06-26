package xyz.melodysky.runtime.jni;

import java.util.List;
import java.util.Objects;

public record JniLocalFramePlan(
        int capacity,
        boolean enterFrame,
        boolean exitFrame,
        JniPendingExceptionPolicy pendingExceptionPolicy,
        List<JniReferencePolicy> references) {
    public JniLocalFramePlan {
        if (capacity < 0) {
            throw new IllegalArgumentException("local frame capacity must be non-negative");
        }
        Objects.requireNonNull(pendingExceptionPolicy, "pendingExceptionPolicy");
        references = references.stream().filter(Objects::nonNull).sorted().toList();
    }

    public static JniLocalFramePlan forNativeCall(int capacity, List<JniReferencePolicy> references) {
        return new JniLocalFramePlan(
                capacity,
                capacity > 0,
                capacity > 0,
                JniPendingExceptionPolicy.PROPAGATE_TO_JVM,
                references);
    }
}
