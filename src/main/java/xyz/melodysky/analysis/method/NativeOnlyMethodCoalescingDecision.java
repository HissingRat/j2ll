package xyz.melodysky.analysis.method;

import java.util.Objects;
import java.util.Optional;

/** Immutable final decision for one internal-native-only method body. */
public record NativeOnlyMethodCoalescingDecision(
        String calleeMethodKey,
        Optional<String> callerMethodKey,
        Status status,
        String reasonCode) implements Comparable<NativeOnlyMethodCoalescingDecision> {
    public NativeOnlyMethodCoalescingDecision {
        Objects.requireNonNull(calleeMethodKey, "calleeMethodKey");
        callerMethodKey = Objects.requireNonNull(callerMethodKey, "callerMethodKey");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(reasonCode, "reasonCode");
        if (calleeMethodKey.isBlank() || reasonCode.isBlank()) {
            throw new IllegalArgumentException(
                    "native-only coalescing identity and reason must not be blank");
        }
        if (status == Status.COALESCED && callerMethodKey.isEmpty()) {
            throw new IllegalArgumentException(
                    "coalesced native-only method requires its caller identity");
        }
    }

    public static NativeOnlyMethodCoalescingDecision kept(
            String calleeMethodKey,
            Optional<String> callerMethodKey,
            String reasonCode) {
        return new NativeOnlyMethodCoalescingDecision(
                calleeMethodKey,
                callerMethodKey,
                Status.KEPT_STANDALONE,
                reasonCode);
    }

    public static NativeOnlyMethodCoalescingDecision coalesced(
            String calleeMethodKey,
            String callerMethodKey) {
        return new NativeOnlyMethodCoalescingDecision(
                calleeMethodKey,
                Optional.of(callerMethodKey),
                Status.COALESCED,
                NativeOnlyMethodCoalescingReason.COALESCED);
    }

    public boolean coalesced() {
        return status == Status.COALESCED;
    }

    @Override
    public int compareTo(NativeOnlyMethodCoalescingDecision other) {
        return calleeMethodKey.compareTo(other.calleeMethodKey);
    }

    public enum Status {
        COALESCED,
        KEPT_STANDALONE
    }
}
