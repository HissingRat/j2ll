package xyz.melodysky.toolchain;

/**
 * Independently emitted host-JNI runtime source fragments.
 *
 * <p>The order here is not the source order. {@link HostJniCSourceGenerator}
 * keeps the dependency-safe physical emission order explicit.</p>
 */
enum HostJniRuntimeSourceFamily {
    ALLOCATION,
    CLASS_INIT,
    ARITHMETIC,
    NUMERIC,
    EXCEPTION,
    MATH,
    JDK_OBJECT,
    PURE_NATIVE_JDK,
    THREAD,
    MONITOR,
    ARRAY,
    TYPE,
    STRING,
    LAMBDA,
    VAR_HANDLE,
    REFLECTION,
    DISPATCH
}
