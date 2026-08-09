package xyz.melodysky.progress;

/** Stable native-workspace preparation rows shown before target compilation. */
public enum NativePreparationStep {
    GENERATE_NATIVE_C,
    AUDIT_NATIVE_C,
    WRITE_NATIVE_IR,
    PREPARE_ZIG_BUILD
}
