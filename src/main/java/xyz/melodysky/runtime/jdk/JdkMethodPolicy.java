package xyz.melodysky.runtime.jdk;

public enum JdkMethodPolicy {
    INTRINSIC,
    RUNTIME_HELPER,
    DIRECT_NATIVE_LOWERING,
    JVM_HELPER_FALLBACK,
    UNSUPPORTED
}
