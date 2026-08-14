package xyz.melodysky.toolchain;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import xyz.melodysky.toolchain.nativetext.GeneratedCFragmentTextObfuscator;
import xyz.melodysky.toolchain.nativetext.NativeTextBuildKey;
import xyz.melodysky.toolchain.nativetext.NativeTextCEmitter;

final class HostJniLowSensitivityThrowShardSourceVerifierTest {
    private final HostJniLowSensitivityThrowShardSourceVerifier verifier =
            new HostJniLowSensitivityThrowShardSourceVerifier();

    @Test
    void acceptsExactDefinitionReferenceAndUseClosure() {
        CompleteSource complete = completeSource(65);

        assertDoesNotThrow(() -> verifier.verify(
                complete.source(),
                complete.plan()));
    }

    @Test
    void rejectsMissingOrDuplicatePrototypeAndDefinition() {
        CompleteSource complete = completeSource(33);
        HostJniLowSensitivityThrowShardPlan.Shard shard =
                complete.plan().shards().get(0);
        String prototype = prototype(shard);
        String definitionPrefix =
                "static void " + shard.symbol() + "(JNIEnv* env) {";

        assertClosureFailure(
                complete,
                complete.source().replace(prototype, ""));
        assertClosureFailure(
                complete,
                complete.source().replace(
                        definitionPrefix,
                        "static void removed_physical_leaf(JNIEnv* env) {"));
        assertClosureFailure(
                complete,
                complete.source() + '\n' + prototype);
        assertClosureFailure(
                complete,
                complete.source()
                        + '\n'
                        + definitionPrefix
                        + "}\n");
    }

    @Test
    void rejectsMissingDuplicatedOrCrossShardCalls() {
        CompleteSource complete = completeSource(33);
        HostJniLowSensitivityThrowShardPlan.Shard first =
                complete.plan().shards().get(0);
        HostJniLowSensitivityThrowShardPlan.Shard second =
                complete.plan().shards().get(1);
        String firstCall = first.symbol() + "(env);";
        String secondCall = second.symbol() + "(env);";

        assertFanoutFailure(
                complete,
                replaceFirst(complete.source(), firstCall, ""));
        assertFanoutFailure(
                complete,
                complete.source() + '\n' + firstCall + '\n');
        assertFanoutFailure(
                complete,
                complete.source().replace(secondCall, firstCall));
    }

    @Test
    void rejectsFunctionPointerTableAndCentralDispatcherReferences() {
        CompleteSource complete = completeSource(1);
        HostJniLowSensitivityThrowShardPlan.Shard shard =
                complete.plan().shards().get(0);

        assertThrows(
                IllegalStateException.class,
                () -> verifier.verify(
                        complete.source()
                                + "\nstatic void (*leaf_table[])(JNIEnv*) = { "
                                + shard.symbol()
                                + " };\n",
                        complete.plan()));
        assertFanoutFailure(
                complete,
                complete.source()
                        + "\nstatic void central_dispatch(JNIEnv* env) { "
                + shard.symbol()
                        + "(env); }\n");
    }

    @Test
    void rejectsMissingOrDuplicatedThrowTupleAndCleanup() {
        CompleteSource complete = completeSource(1);
        String throwCall = "j2ll_throw_new(env, ";
        String cleanup = "__attribute__((cleanup("
                + xyz.melodysky.toolchain.nativetext
                        .NativeScratchZeroizerSource.CLEANUP_FUNCTION_NAME
                + ")))";

        assertTupleFailure(
                complete,
                replaceFirst(
                        complete.source(),
                        throwCall,
                        "removed_throw_new(env, "));
        assertTupleFailure(
                complete,
                replaceFirst(
                        complete.source(),
                        throwCall,
                        throwCall + throwCall));
        assertTupleFailure(
                complete,
                replaceFirst(complete.source(), cleanup, ""));
    }

    @Test
    void rejectsPrototypeThatAppearsOnlyAfterItsFirstCall() {
        CompleteSource complete = completeSource(1);
        String prototype = prototype(complete.plan().shards().get(0));
        String reordered = complete.source().replace(prototype, "")
                + '\n'
                + prototype;

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> verifier.verify(reordered, complete.plan()));
        assertTrue(failure.getMessage().contains("ordering"));
    }

    @Test
    void rejectsResidualPlaceholdersButIgnoresCommentsAndStrings() {
        CompleteSource complete = completeSource(1);
        String placeholder = complete.plan().sites().get(0).placeholder();

        assertThrows(
                IllegalStateException.class,
                () -> verifier.verify(
                        complete.source()
                                + '\n'
                                + placeholder
                                + "(env);\n",
                        complete.plan()));
        assertThrows(
                IllegalStateException.class,
                () -> verifier.verify(
                        complete.source()
                                + '\n'
                                + HostJniLowSensitivityThrowShardDeriver
                                        .placeholderPrefix()
                                + "q".repeat(32)
                                + "(env);\n",
                        complete.plan()));
        assertDoesNotThrow(() -> verifier.verify(
                complete.source()
                        + "\n// " + placeholder
                        + "\nstatic const char* marker = \""
                        + placeholder
                        + "\";\n",
                complete.plan()));
    }

    private CompleteSource completeSource(int useCount) {
        NativeTextBuildKey buildKey = NativeTextBuildKey.fromUtf8(
                "source-verifier-build-" + useCount);
        StringBuilder source = new StringBuilder()
                .append(new NativeTextCEmitter().runtimeSource())
                .append(HostJniRegistrationRuntimeSource.helperSource());
        HostJniGeneratedCFragmentEmitter emitter =
                new HostJniGeneratedCFragmentEmitter(
                        source,
                        new GeneratedCFragmentTextObfuscator(),
                        buildKey,
                        new HostJniLowSensitivityThrowLeafPool(buildKey));
        HostJniLowSensitivityThrowShardFixture.Scenario scenario =
                HostJniLowSensitivityThrowShardFixture
                        .singleFragment(useCount);
        for (HostJniLowSensitivityThrowShardFixture.Fragment fragment
                : scenario.fragments()) {
            emitter.append(fragment.scope(), fragment.source());
        }
        emitter.appendLowSensitivityLeaves();
        return new CompleteSource(
                emitter.frozenPlan(),
                source.toString());
    }

    private String prototype(
            HostJniLowSensitivityThrowShardPlan.Shard shard) {
        return "static void "
                + shard.symbol()
                + "(JNIEnv* env) __attribute__((noinline, cold));\n";
    }

    private void assertClosureFailure(
            CompleteSource complete,
            String mutated) {
        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> verifier.verify(mutated, complete.plan()));
        assertTrue(failure.getMessage().contains("definition closure"));
    }

    private void assertFanoutFailure(
            CompleteSource complete,
            String mutated) {
        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> verifier.verify(mutated, complete.plan()));
        assertTrue(failure.getMessage().contains("fanout"));
    }

    private void assertTupleFailure(
            CompleteSource complete,
            String mutated) {
        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> verifier.verify(mutated, complete.plan()));
        assertTrue(failure.getMessage().contains("activation-local throw tuple"));
    }

    private String replaceFirst(
            String source,
            String target,
            String replacement) {
        return source.replaceFirst(
                java.util.regex.Pattern.quote(target),
                java.util.regex.Matcher.quoteReplacement(replacement));
    }

    private record CompleteSource(
            HostJniLowSensitivityThrowShardPlan plan,
            String source) {}
}
