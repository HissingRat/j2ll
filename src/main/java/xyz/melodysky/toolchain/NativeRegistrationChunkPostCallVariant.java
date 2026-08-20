package xyz.melodysky.toolchain;

/** Closed structural variants for non-tail forward-chunk calls. */
enum NativeRegistrationChunkPostCallVariant {
    JINT_U16_FOLD,
    JLONG_U32_FOLD,
    JINT_DUAL_WORD,
    JLONG_ORBIT,
    JINT_MIXED_WIDTH,
    JLONG_MIXED_WIDTH,
    JINT_SIGNED_BRAID,
    JLONG_SPLIT_WORD
}
