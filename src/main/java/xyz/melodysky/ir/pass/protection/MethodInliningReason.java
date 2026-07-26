package xyz.melodysky.ir.pass.protection;

public final class MethodInliningReason {
    public static final String INLINED = "METHOD_INLINING";
    public static final String DISABLED = "METHOD_INLINING_DISABLED";
    public static final String NO_CANDIDATE = "METHOD_INLINING_NO_CANDIDATE";
    public static final String NOT_SINGLE_TARGET = "METHOD_INLINING_NOT_SINGLE_TARGET";
    public static final String NON_NATIVE_PATH = "METHOD_INLINING_NON_NATIVE_PATH";
    public static final String REFLECTION_SENSITIVE = "METHOD_INLINING_REFLECTION_SENSITIVE";
    public static final String FALLBACK_SENSITIVE = "METHOD_INLINING_FALLBACK_SENSITIVE";
    public static final String RECURSIVE = "METHOD_INLINING_RECURSIVE";
    public static final String CALLEE_TOO_LARGE = "METHOD_INLINING_CALLEE_TOO_LARGE";
    public static final String EXCEPTION_SENSITIVE = "METHOD_INLINING_EXCEPTION_SENSITIVE";
    public static final String MONITOR_JMM_SENSITIVE = "METHOD_INLINING_MONITOR_JMM_SENSITIVE";
    public static final String UNSUPPORTED_CALLEE_SHAPE = "METHOD_INLINING_UNSUPPORTED_CALLEE_SHAPE";
    public static final String UNSAFE_CALL_SITE = "METHOD_INLINING_UNSAFE_CALL_SITE";
    public static final String SITE_LIMIT = "METHOD_INLINING_SITE_LIMIT";
    public static final String VALIDATION_FAILED = "METHOD_INLINING_VALIDATION_FAILED";

    private MethodInliningReason() {
    }
}
