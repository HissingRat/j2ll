package xyz.melodysky.analysis.method;

import java.util.List;
import java.util.Objects;

public record NativeMethodInternalizationDecision(
        NativeMethodId method,
        NativeMethodInternalizationStatus status,
        boolean staticMethod,
        String access,
        List<String> callerMethodKeys,
        List<NativeMethodInternalizationReason> reasons)
        implements Comparable<NativeMethodInternalizationDecision> {
    public NativeMethodInternalizationDecision {
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(access, "access");
        callerMethodKeys = Objects.requireNonNull(
                        callerMethodKeys,
                        "callerMethodKeys")
                .stream()
                .filter(Objects::nonNull)
                .distinct()
                .sorted()
                .toList();
        reasons = Objects.requireNonNull(reasons, "reasons")
                .stream()
                .filter(Objects::nonNull)
                .distinct()
                .sorted()
                .toList();
        if (status == NativeMethodInternalizationStatus.INTERNALIZED
                && !reasons.equals(List.of(
                        NativeMethodInternalizationReason
                                .METHOD_INTERNALIZATION_ELIGIBLE))) {
            throw new IllegalArgumentException(
                    "internalized method must have only the eligible reason");
        }
        if (reasons.isEmpty()) {
            throw new IllegalArgumentException(
                    "method internalization decision requires a reason");
        }
    }

    public boolean internalized() {
        return status == NativeMethodInternalizationStatus.INTERNALIZED;
    }

    @Override
    public int compareTo(NativeMethodInternalizationDecision other) {
        return method.compareTo(other.method);
    }
}
