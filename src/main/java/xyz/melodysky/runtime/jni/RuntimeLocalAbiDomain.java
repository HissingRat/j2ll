package xyz.melodysky.runtime.jni;

/**
 * Closed set of concrete-binding helper families that may use a build-local
 * native ABI.
 */
public enum RuntimeLocalAbiDomain {
    FIELD,
    DISPATCH,
    REFLECTION,
    JDK
}
