package xyz.melodysky.toolchain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import xyz.melodysky.toolchain.nativetext.GeneratedCFragmentTextObfuscator;
import xyz.melodysky.toolchain.nativetext.GeneratedNativeHardeningAudit;
import xyz.melodysky.toolchain.nativetext.NativeTextBuildKey;
import xyz.melodysky.toolchain.nativetext.NativeTextCEmitter;
import xyz.melodysky.toolchain.nativetext.NativeScratchZeroizerSource;

final class HostJniLowSensitivityThrowLeafPoolTest {
    @Test
    void outlinesOnlyAllowlistedMetadataFreeErrors() {
        HostJniLowSensitivityThrowLeafPool pool =
                pool("fixed-build");
        String fragment = """
                static void first(JNIEnv* env) {
                    j2ll_throw_new(env, "java/lang/NullPointerException", "array is null");
                }
                static void second(JNIEnv* env) {
                    j2ll_throw_new(env, "java/lang/NullPointerException", "array is null");
                    j2ll_throw_new(env, "secret/Owner", "secret member descriptor");
                }
                """;

        String rewritten = pool.rewrite("allowlist-fragment", fragment);
        HostJniLowSensitivityThrowShardPlan plan = pool.freeze();
        String materialized =
                new HostJniLowSensitivityThrowShardMaterializer()
                        .materialize(rewritten, plan);

        assertFalse(rewritten.contains("\"array is null\""));
        assertTrue(rewritten.contains("\"secret/Owner\""));
        assertTrue(rewritten.contains("\"secret member descriptor\""));
        assertEquals(1, plan.shards().size());
        String symbol = plan.shards().get(0).symbol();
        assertTrue(symbol.matches("[a-p]{32}"));
        assertEquals(3, occurrences(materialized, symbol));
        assertTrue(plan.declarations().contains(
                "__attribute__((noinline, cold))"));
    }

    @Test
    void leafSymbolsAreBuildScopedAndMetadataFree() {
        HostJniLowSensitivityThrowLeafPool first =
                pool("build-a");
        HostJniLowSensitivityThrowLeafPool repeated =
                pool("build-a");
        HostJniLowSensitivityThrowLeafPool other =
                pool("build-b");
        String fragment = """
                static void fail(JNIEnv* env) {
                    j2ll_throw_new(env, "java/lang/NullPointerException", "field receiver is null");
                }
                """;

        first.rewrite("metadata-free-leaf", fragment);
        repeated.rewrite("metadata-free-leaf", fragment);
        other.rewrite("metadata-free-leaf", fragment);

        var firstSymbols = first.freeze().shards().stream()
                .map(HostJniLowSensitivityThrowShardPlan.Shard::symbol)
                .toList();
        var repeatedSymbols = repeated.freeze().shards().stream()
                .map(HostJniLowSensitivityThrowShardPlan.Shard::symbol)
                .toList();
        var otherSymbols = other.freeze().shards().stream()
                .map(HostJniLowSensitivityThrowShardPlan.Shard::symbol)
                .toList();
        assertEquals(firstSymbols, repeatedSymbols);
        assertNotEquals(firstSymbols, otherSymbols);
        assertFalse(firstSymbols.get(0).contains("field"));
    }

    @Test
    void emittedLeavesUsePerLeafActivationLocalTuplesAndCleanup() {
        NativeTextBuildKey buildKey =
                NativeTextBuildKey.fromUtf8("fixed-build");
        HostJniLowSensitivityThrowLeafPool pool =
                new HostJniLowSensitivityThrowLeafPool(buildKey);
        StringBuilder source = new StringBuilder();
        source.append(new NativeTextCEmitter().runtimeSource())
                .append(HostJniRegistrationRuntimeSource.helperSource());
        HostJniGeneratedCFragmentEmitter fragments =
                new HostJniGeneratedCFragmentEmitter(
                        source,
                        new GeneratedCFragmentTextObfuscator(),
                        buildKey,
                        pool);
        fragments.append(
                "two-errors",
                """
                static void first(JNIEnv* env) {
                    j2ll_throw_new(env, "java/lang/NullPointerException", "array is null");
                }
                static void second(JNIEnv* env) {
                    j2ll_throw_new(env, "java/lang/NullPointerException", "field receiver is null");
                }
                """);
        fragments.appendLowSensitivityLeaves();
        fragments.verifyFinalSource();
        String output = source.toString();

        assertFalse(output.contains("\"java/lang/NullPointerException\""));
        assertFalse(output.contains("\"array is null\""));
        assertFalse(output.contains("\"field receiver is null\""));
        assertEquals(2, occurrences(output, "_cipher[] = {"));
        assertEquals(2, occurrences(
                output,
                "static const unsigned char j2ll_nt_"));
        assertEquals(2, occurrences(
                output,
                "__attribute__((cleanup("
                        + NativeScratchZeroizerSource.CLEANUP_FUNCTION_NAME
                        + ")))"));
        assertFalse(output.contains("#include <stdatomic.h>"));
        assertFalse(output.contains("_Atomic"));
        assertFalse(output.contains("atomic_"));
        assertFalse(output.contains("j2ll_gcf_low_"));
        assertFalse(output.matches(
                "(?s).*static\\s+unsigned\\s+char\\s+j2ll_nt_[0-9a-f]{24}_cipher.*"));
        assertEquals(2, occurrences(
                output,
                "__attribute__((noinline, cold))"));
        var audit = new GeneratedNativeHardeningAudit().audit(output);
        assertTrue(audit.passed(), audit.findings().toString());
        assertTrue(audit.evidence().contains(
                GeneratedNativeHardeningAudit
                        .EVIDENCE_CALL_LOCAL_TEXT_SCRATCH));
        assertTrue(audit.evidence().contains(
                GeneratedNativeHardeningAudit
                        .EVIDENCE_CALL_LOCAL_TEXT_CLEANUP));
        assertFalse(audit.evidence().contains(
                "LOW_SENSITIVITY_RUNTIME_TEXT_LAZY_ONCE"));
    }

    private HostJniLowSensitivityThrowLeafPool pool(String key) {
        return new HostJniLowSensitivityThrowLeafPool(
                NativeTextBuildKey.fromUtf8(key));
    }

    private int occurrences(
            String value,
            String needle) {
        return value.split(
                        java.util.regex.Pattern.quote(needle),
                        -1)
                .length
                - 1;
    }
}
