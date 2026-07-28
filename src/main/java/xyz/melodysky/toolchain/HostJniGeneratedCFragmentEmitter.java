package xyz.melodysky.toolchain;

import java.util.Objects;
import java.util.function.Consumer;
import xyz.melodysky.toolchain.nativetext.GeneratedCFragmentTextObfuscator;
import xyz.melodysky.toolchain.nativetext.GeneratedCTextPolicy;
import xyz.melodysky.toolchain.nativetext.NativeTextBuildKey;

/**
 * Applies the common generated-C text and low-sensitivity outlining policy to
 * independent runtime fragments.
 */
final class HostJniGeneratedCFragmentEmitter {
    private final StringBuilder output;
    private final GeneratedCFragmentTextObfuscator textObfuscator;
    private final NativeTextBuildKey buildKey;
    private final HostJniLowSensitivityThrowLeafPool lowSensitivityLeaves;

    HostJniGeneratedCFragmentEmitter(
            StringBuilder output,
            GeneratedCFragmentTextObfuscator textObfuscator,
            NativeTextBuildKey buildKey,
            HostJniLowSensitivityThrowLeafPool lowSensitivityLeaves) {
        this.output = Objects.requireNonNull(output, "output");
        this.textObfuscator = Objects.requireNonNull(
                textObfuscator,
                "textObfuscator");
        this.buildKey = Objects.requireNonNull(buildKey, "buildKey");
        this.lowSensitivityLeaves = Objects.requireNonNull(
                lowSensitivityLeaves,
                "lowSensitivityLeaves");
    }

    void append(
            String scope,
            String fragment) {
        output.append(textObfuscator.obfuscate(
                buildKey,
                scope,
                lowSensitivityLeaves.rewrite(fragment)));
    }

    void append(
            String scope,
            Consumer<StringBuilder> sourceEmitter) {
        Objects.requireNonNull(sourceEmitter, "sourceEmitter");
        StringBuilder fragment = new StringBuilder();
        sourceEmitter.accept(fragment);
        append(scope, fragment.toString());
    }

    void appendLowSensitivityLeaves() {
        if (lowSensitivityLeaves.isEmpty()) {
            return;
        }
        output.append(textObfuscator.obfuscate(
                buildKey,
                "low-sensitivity-throw-leaves",
                lowSensitivityLeaves.definitions(),
                GeneratedCTextPolicy.lowSensitivityRuntimeError()));
    }
}
