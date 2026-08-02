package xyz.melodysky.analysis.method;

/** Stable reasons for the fail-closed native-only coalescing boundary. */
public final class NativeOnlyMethodCoalescingReason {
    public static final String COALESCED =
            "NATIVE_ONLY_SINGLE_CALLER_COALESCED";
    public static final String NOT_INTERNALIZED =
            "NATIVE_ONLY_COALESCING_NOT_INTERNALIZED";
    public static final String NOT_SINGLE_CALLER =
            "NATIVE_ONLY_COALESCING_NOT_SINGLE_CALLER";
    public static final String IMPLEMENTATION_NOT_LLVM_INTERNAL_ONLY =
            "NATIVE_ONLY_COALESCING_IMPLEMENTATION_NOT_LLVM_INTERNAL_ONLY";
    public static final String CALLER_NOT_LLVM =
            "NATIVE_ONLY_COALESCING_CALLER_NOT_LLVM";
    public static final String CALLER_INITIALIZER_PLAN_UNSUPPORTED =
            "NATIVE_ONLY_COALESCING_CALLER_INITIALIZER_PLAN_UNSUPPORTED";
    public static final String CALLER_IS_COALESCING_CANDIDATE =
            "NATIVE_ONLY_COALESCING_CALLER_IS_COALESCING_CANDIDATE";
    public static final String IR_BODY_MISSING =
            "NATIVE_ONLY_COALESCING_IR_BODY_MISSING";
    public static final String LOCAL_REFERENCE_SENSITIVE =
            "NATIVE_ONLY_COALESCING_LOCAL_REFERENCE_SENSITIVE";
    public static final String CALL_SITE_NOT_UNIQUE =
            "NATIVE_ONLY_COALESCING_CALL_SITE_NOT_UNIQUE";
    public static final String INVOKE_KIND_UNSUPPORTED =
            "NATIVE_ONLY_COALESCING_INVOKE_KIND_UNSUPPORTED";
    public static final String RESIDUAL_REFERENCE =
            "NATIVE_ONLY_COALESCING_RESIDUAL_REFERENCE";
    public static final String FINAL_EMISSION_MISMATCH =
            "NATIVE_ONLY_COALESCING_FINAL_EMISSION_MISMATCH";
    public static final String NO_CANDIDATE =
            "NATIVE_ONLY_COALESCING_NO_CANDIDATE";

    private NativeOnlyMethodCoalescingReason() {
    }
}
