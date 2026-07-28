package xyz.melodysky.toolchain.nativetext;

/**
 * Build-scoped native-text codec shapes.
 *
 * <p>These codecs only raise the cost of static bulk extraction. They are not
 * a confidentiality boundary because the native artifact necessarily contains
 * enough material to recover text at its use site.</p>
 */
public enum NativeTextCodecFamily {
    WEYL_ARX,
    DUAL_LANE_ARX,
    FEISTEL_32,
    FOLD_ROTATE
}
