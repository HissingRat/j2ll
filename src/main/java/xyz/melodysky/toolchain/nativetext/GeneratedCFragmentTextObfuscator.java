package xyz.melodysky.toolchain.nativetext;

import java.util.Objects;

/**
 * Applies an explicit plaintext-lifetime policy to one generated-C fragment.
 *
 * <p>The default overload is fail-safe: it uses activation-local scratch.
 * Process-lifetime lazy decoding requires the caller to opt into the
 * low-sensitivity runtime-error policy explicitly.</p>
 */
public final class GeneratedCFragmentTextObfuscator {
    private final GeneratedCSensitiveTextObfuscator sensitive =
            new GeneratedCSensitiveTextObfuscator();
    private final GeneratedCLazyRuntimeTextObfuscator lazyRuntime =
            new GeneratedCLazyRuntimeTextObfuscator();

    public String obfuscate(
            NativeTextBuildKey buildKey,
            String scope,
            String fragment) {
        return obfuscate(
                buildKey,
                scope,
                fragment,
                GeneratedCTextPolicy.sensitive(
                        NativeTextPurpose.GENERATED_C_FRAGMENT));
    }

    public String obfuscate(
            NativeTextBuildKey buildKey,
            String scope,
            String fragment,
            GeneratedCTextPolicy policy) {
        Objects.requireNonNull(policy, "policy");
        return switch (policy.lifetime()) {
            case CALL_LOCAL_SCRATCH -> sensitive.obfuscate(
                    buildKey,
                    scope,
                    fragment,
                    policy.purpose());
            case LOW_SENSITIVITY_LAZY_ONCE -> lazyRuntime.obfuscate(
                    buildKey,
                    scope,
                    fragment);
        };
    }
}
