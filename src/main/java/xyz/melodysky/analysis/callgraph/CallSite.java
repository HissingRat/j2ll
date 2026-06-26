package xyz.melodysky.analysis.callgraph;

import java.util.Objects;
import xyz.melodysky.jvm.MethodSignature;

public record CallSite(
        String id,
        String callerOwner,
        MethodSignature caller,
        int instructionIndex,
        InvokeKind kind,
        String declaredOwner,
        MethodSignature declaredTarget) implements Comparable<CallSite> {
    public CallSite {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(callerOwner, "callerOwner");
        Objects.requireNonNull(caller, "caller");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(declaredOwner, "declaredOwner");
        Objects.requireNonNull(declaredTarget, "declaredTarget");
    }

    @Override
    public int compareTo(CallSite other) {
        int byCaller = callerOwner.compareTo(other.callerOwner);
        if (byCaller != 0) {
            return byCaller;
        }
        int byMethod = caller.compareTo(other.caller);
        if (byMethod != 0) {
            return byMethod;
        }
        return Integer.compare(instructionIndex, other.instructionIndex);
    }
}
