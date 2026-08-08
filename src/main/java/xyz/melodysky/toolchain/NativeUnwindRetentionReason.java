package xyz.melodysky.toolchain;

public enum NativeUnwindRetentionReason {
    WINDOWS_SEH_REQUIRED,
    DEBUG_MODE,
    CONFIG_RETAINED,
    CONFIG_DISABLED,
    LLVM_MODULE_PROOF_RETAINED,
    UNMODELED_OBJECT_INPUT_RETAINED
}
