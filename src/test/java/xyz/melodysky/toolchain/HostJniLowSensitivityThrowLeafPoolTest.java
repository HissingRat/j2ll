package xyz.melodysky.toolchain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import xyz.melodysky.runtime.RuntimeTokenMapper;
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

        String rewritten = pool.rewrite(fragment);

        assertFalse(rewritten.contains("\"array is null\""));
        assertTrue(rewritten.contains("\"secret/Owner\""));
        assertTrue(rewritten.contains("\"secret member descriptor\""));
        assertEquals(1, pool.usedSymbols().size());
        String symbol = pool.usedSymbols().iterator().next();
        assertTrue(symbol.matches("j2ll_l_[0-9a-f]{16}"));
        assertEquals(3, occurrences(rewritten, symbol));
        assertTrue(pool.definitions().contains(
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

        first.rewrite(fragment);
        repeated.rewrite(fragment);
        other.rewrite(fragment);

        assertEquals(first.usedSymbols(), repeated.usedSymbols());
        assertNotEquals(first.usedSymbols(), other.usedSymbols());
        assertFalse(first.usedSymbols().iterator().next().contains("field"));
    }

    @Test
    void emittedLeavesUsePerLeafActivationLocalTuplesAndCleanup() {
        NativeTextBuildKey buildKey =
                NativeTextBuildKey.fromUtf8("fixed-build");
        RuntimeTokenMapper tokens =
                RuntimeTokenMapper.fromBytes(buildKey.bytes());
        HostJniLowSensitivityThrowLeafPool pool =
                new HostJniLowSensitivityThrowLeafPool(tokens);
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
                RuntimeTokenMapper.fromBytes(
                        NativeTextBuildKey.fromUtf8(key).bytes()));
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
