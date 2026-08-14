package xyz.melodysky.toolchain;

import java.util.Objects;
import java.util.function.Consumer;
import xyz.melodysky.toolchain.nativetext.GeneratedCFragmentTextObfuscator;
import xyz.melodysky.toolchain.nativetext.GeneratedCTextPolicy;
import xyz.melodysky.toolchain.nativetext.NativeTextBuildKey;
import xyz.melodysky.toolchain.nativetext.NativeTextPurpose;

/**
 * Applies the common generated-C text and low-sensitivity outlining policy to
 * independent runtime fragments.
 */
final class HostJniGeneratedCFragmentEmitter {
    private final StringBuilder output;
    private final GeneratedCFragmentTextObfuscator textObfuscator;
    private final NativeTextBuildKey buildKey;
    private final HostJniLowSensitivityThrowLeafPool lowSensitivityLeaves;
    private final HostJniLowSensitivityThrowShardMaterializer materializer =
            new HostJniLowSensitivityThrowShardMaterializer();
    private final HostJniLowSensitivityThrowShardSource shardSource =
            new HostJniLowSensitivityThrowShardSource();
    private final HostJniLowSensitivityThrowShardSourceVerifier verifier =
            new HostJniLowSensitivityThrowShardSourceVerifier();
    private HostJniLowSensitivityThrowShardPlan frozenPlan;
    private boolean finalSourceVerified;

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
        requireCollecting();
        output.append(textObfuscator.obfuscate(
                buildKey,
                scope,
                lowSensitivityLeaves.rewrite(scope, fragment)));
    }

    void append(
            String scope,
            Consumer<StringBuilder> sourceEmitter) {
        requireCollecting();
        Objects.requireNonNull(sourceEmitter, "sourceEmitter");
        StringBuilder fragment = new StringBuilder();
        sourceEmitter.accept(fragment);
        append(scope, fragment.toString());
    }

    void appendLowSensitivityLeaves() {
        requireCollecting();
        frozenPlan = lowSensitivityLeaves.freeze();
        materializer.materialize(output, frozenPlan);
        for (HostJniLowSensitivityThrowShardPlan.Shard shard
                : frozenPlan.shards()) {
            output.append(textObfuscator.obfuscate(
                    buildKey,
                    "low-sensitivity-throw-shard:" + shard.symbol(),
                    shardSource.definition(shard),
                    GeneratedCTextPolicy.sensitive(
                            NativeTextPurpose.RUNTIME_ERROR)));
        }
    }

    void verifyFinalSource() {
        if (frozenPlan == null) {
            throw new IllegalStateException(
                    "low-sensitivity shard plan has not been frozen");
        }
        if (finalSourceVerified) {
            throw new IllegalStateException(
                    "low-sensitivity final source was already verified");
        }
        verifier.verify(output.toString(), frozenPlan);
        finalSourceVerified = true;
    }

    HostJniLowSensitivityThrowShardPlan frozenPlan() {
        if (frozenPlan == null) {
            throw new IllegalStateException(
                    "low-sensitivity shard plan has not been frozen");
        }
        return frozenPlan;
    }

    private void requireCollecting() {
        if (frozenPlan != null) {
            throw new IllegalStateException(
                    "low-sensitivity generated-C fragments are already frozen");
        }
    }
}
