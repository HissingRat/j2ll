package xyz.melodysky.toolchain;

/** Closed post-call continuation shapes used to prevent tail-call collapse. */
enum NativeRegistrationPostCallRecipe {
    XOR_JINT,
    ADD_JLONG,
    MIRROR_JINT
}
