package xyz.melodysky.runtime;

/**
 * Independent token namespaces for JVM/runtime metadata.
 *
 * <p>Never reuse a token across domains merely because the plaintext identity
 * is equal. That would create a cross-table join key in the native artifact.</p>
 */
public enum RuntimeTokenDomain {
    CLASS_RUNTIME,
    CLASS_OBJECT,
    FIELD_RUNTIME,
    NATIVE_FIELD_SLOT,
    DISPATCH_METHOD,
    REFLECTION_METHOD,
    REFLECTION_FIELD,
    LAMBDA,
    CONSTANT_DYNAMIC,
    BUSINESS_STRING_CARRIER,
    JNI_LOCAL_ABI,
    LOW_SENSITIVITY_RUNTIME
}
