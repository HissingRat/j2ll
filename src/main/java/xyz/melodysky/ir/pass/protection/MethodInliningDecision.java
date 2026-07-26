package xyz.melodysky.ir.pass.protection;

import java.util.Objects;

public record MethodInliningDecision(
        String callerMethodKey,
        String calleeMethodKey,
        String callSite,
        Status status,
        String reasonCode) implements Comparable<MethodInliningDecision> {
    public MethodInliningDecision {
        Objects.requireNonNull(callerMethodKey, "callerMethodKey");
        Objects.requireNonNull(calleeMethodKey, "calleeMethodKey");
        Objects.requireNonNull(callSite, "callSite");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(reasonCode, "reasonCode");
    }

    @Override
    public int compareTo(MethodInliningDecision other) {
        int byCaller = callerMethodKey.compareTo(other.callerMethodKey);
        if (byCaller != 0) {
            return byCaller;
        }
        int bySite = callSite.compareTo(other.callSite);
        if (bySite != 0) {
            return bySite;
        }
        return calleeMethodKey.compareTo(other.calleeMethodKey);
    }

    public enum Status {
        INLINED,
        SKIPPED,
        FAILED
    }
}
