package xyz.melodysky.ir.pass.protection;

import java.util.Objects;
import xyz.melodysky.ir.model.IrOpcode;

/**
 * Immutable analysis facts for one caller/callee edge.
 *
 * <p>The program-level planner is responsible for deriving these facts from
 * access flags, call graph/devirtualization results, reflection analysis and
 * the final native implementation plan. The IR rewriter still verifies the
 * structural facts it can observe before changing a call site.
 */
public record MethodInliningCandidate(
        String callerMethodKey,
        String calleeMethodKey,
        IrOpcode invokeOpcode,
        MethodInliningAccess access,
        boolean singleTarget,
        boolean callerUsesFinalNativePath,
        boolean calleeUsesFinalNativePath,
        boolean reflectionSensitive) implements Comparable<MethodInliningCandidate> {
    public MethodInliningCandidate {
        Objects.requireNonNull(callerMethodKey, "callerMethodKey");
        Objects.requireNonNull(calleeMethodKey, "calleeMethodKey");
        Objects.requireNonNull(invokeOpcode, "invokeOpcode");
        Objects.requireNonNull(access, "access");
        if (invokeOpcode != IrOpcode.CALL_STATIC && invokeOpcode != IrOpcode.CALL_SPECIAL) {
            throw new IllegalArgumentException("method inlining only accepts direct static/special calls");
        }
        if (access == MethodInliningAccess.STATIC && invokeOpcode != IrOpcode.CALL_STATIC) {
            throw new IllegalArgumentException("static inlining access requires CALL_STATIC");
        }
        if (access == MethodInliningAccess.PRIVATE_INSTANCE_SELF && invokeOpcode != IrOpcode.CALL_SPECIAL) {
            throw new IllegalArgumentException("private self inlining access requires CALL_SPECIAL");
        }
    }

    @Override
    public int compareTo(MethodInliningCandidate other) {
        int byCaller = callerMethodKey.compareTo(other.callerMethodKey);
        if (byCaller != 0) {
            return byCaller;
        }
        int byCallee = calleeMethodKey.compareTo(other.calleeMethodKey);
        if (byCallee != 0) {
            return byCallee;
        }
        return invokeOpcode.compareTo(other.invokeOpcode);
    }
}
