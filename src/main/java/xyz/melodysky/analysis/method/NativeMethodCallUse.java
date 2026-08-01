package xyz.melodysky.analysis.method;

import java.util.Objects;
import xyz.melodysky.analysis.callgraph.InvokeKind;

public record NativeMethodCallUse(
        String callSiteId,
        String callerMethodKey,
        InvokeKind invokeKind,
        boolean exactInScopeTarget,
        boolean hasUnknownExternalTarget) implements Comparable<NativeMethodCallUse> {
    public NativeMethodCallUse {
        Objects.requireNonNull(callSiteId, "callSiteId");
        Objects.requireNonNull(callerMethodKey, "callerMethodKey");
        Objects.requireNonNull(invokeKind, "invokeKind");
    }

    @Override
    public int compareTo(NativeMethodCallUse other) {
        int byCaller = callerMethodKey.compareTo(other.callerMethodKey);
        return byCaller != 0
                ? byCaller
                : callSiteId.compareTo(other.callSiteId);
    }
}
