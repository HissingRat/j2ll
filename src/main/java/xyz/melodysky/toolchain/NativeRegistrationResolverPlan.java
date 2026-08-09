package xyz.melodysky.toolchain;

import java.util.Objects;
import xyz.melodysky.packaging.RuntimeLoaderPlan;
import xyz.melodysky.toolchain.nativetext.NativeTextBuildKey;
import xyz.melodysky.toolchain.nativetext.NativeTextEncoder;
import xyz.melodysky.toolchain.nativetext.NativeTextEncoding;
import xyz.melodysky.toolchain.nativetext.NativeTextPurpose;

/** Immutable Loader-anchor text and local-reference capacity for one JNI_OnLoad activation. */
record NativeRegistrationResolverPlan(
        NativeTextEncoding loaderAnchorText,
        int localCapacity) {
    private static final int RESOLVER_LOCAL_REFERENCE_OVERHEAD = 8;

    NativeRegistrationResolverPlan {
        Objects.requireNonNull(loaderAnchorText, "loaderAnchorText");
        if (localCapacity < RESOLVER_LOCAL_REFERENCE_OVERHEAD) {
            throw new IllegalArgumentException(
                    "registration resolver local capacity is below its fixed overhead");
        }
    }

    static NativeRegistrationResolverPlan create(
            RuntimeLoaderPlan runtimeLoaderPlan,
            NativeTextBuildKey buildKey,
            int ownerCount) {
        Objects.requireNonNull(runtimeLoaderPlan, "runtimeLoaderPlan");
        Objects.requireNonNull(buildKey, "buildKey");
        if (ownerCount < 0) {
            throw new IllegalArgumentException("ownerCount must be non-negative");
        }
        int localCapacity = Math.addExact(
                ownerCount,
                RESOLVER_LOCAL_REFERENCE_OVERHEAD);
        NativeTextEncoding loaderAnchorText = new NativeTextEncoder().encode(
                buildKey,
                NativeTextPurpose.REGISTRATION_LOADER_ANCHOR,
                "registration-loader-anchor:" + runtimeLoaderPlan.internalName(),
                runtimeLoaderPlan.internalName());
        return new NativeRegistrationResolverPlan(
                loaderAnchorText,
                localCapacity);
    }
}
