package xyz.melodysky.analysis.field;

public enum FieldDynamicBoundaryKind {
    REFLECTION,
    UNSAFE,
    VAR_HANDLE,
    METHOD_HANDLE,
    NATIVE_JNI,
    SERIALIZATION,
    AGENT_INSTRUMENTATION,
    DYNAMIC_CLASS_LOADING
}
