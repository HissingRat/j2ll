package xyz.melodysky.toolchain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import xyz.melodysky.toolchain.nativetext.GeneratedCFragmentTextObfuscator;
import xyz.melodysky.toolchain.nativetext.GeneratedNativeHardeningAudit;
import xyz.melodysky.toolchain.nativetext.NativeScratchZeroizerSource;
import xyz.melodysky.toolchain.nativetext.NativeTextBuildKey;
import xyz.melodysky.toolchain.nativetext.NativeTextCEmitter;

final class HostJniLowSensitivityThrowShardEmitterTest {
    @Test
    void thirtyThreeUsesProduceTwoIndependentPhysicalLeaves() {
        Emitted emitted = emit(33);

        assertEquals(2, emitted.plan().shards().size());
        assertEquals(2, cipherNames(emitted.source()).size());
        assertEquals(2, cipherBodies(emitted.source()).size());
        assertEquals(
                2,
                occurrences(
                        emitted.source(),
                        "__attribute__((cleanup("
                                + NativeScratchZeroizerSource
                                        .CLEANUP_FUNCTION_NAME
                                + ")))"));
        assertTrue(
                new GeneratedNativeHardeningAudit()
                        .audit(emitted.source())
                        .passed());
    }

    @Test
    void emitsOneIndependentActivationLocalTuplePerPhysicalShard() {
        Emitted emitted = emit(65);
        String source = emitted.source();

        assertEquals(65, emitted.plan().siteCount());
        assertEquals(3, emitted.plan().shards().size());
        assertEquals(3, occurrences(source, "_cipher[] = {"));
        assertEquals(
                3,
                occurrences(
                        source,
                        "static const unsigned char j2ll_nt_"));
        assertEquals(
                3,
                occurrences(
                        source,
                        "__attribute__((cleanup("
                                + NativeScratchZeroizerSource
                                        .CLEANUP_FUNCTION_NAME
                                + ")))"));
        assertEquals(3, cipherBodies(source).size());
        assertEquals(3, cipherNames(source).size());
        assertEquals(0, occurrences(source, ".ready == 0u"));
        assertEquals(0, occurrences(source, ".ready = 0u;"));
        assertEquals(0, occurrences(source, "unsigned char ready;"));

        int totalCalls = 0;
        for (HostJniLowSensitivityThrowShardPlan.Shard shard
                : emitted.plan().shards()) {
            int calls = occurrences(source, shard.symbol() + "(env);");
            assertEquals(shard.sites().size(), calls, shard.symbol());
            assertTrue(calls <= 32, shard.symbol());
            totalCalls += calls;
        }
        assertEquals(65, totalCalls);
        assertFalse(source.contains("\"java/lang/NullPointerException\""));
        assertFalse(source.contains("\"array is null\""));
        var audit = new GeneratedNativeHardeningAudit().audit(source);
        assertTrue(audit.passed(), audit.findings().toString());
    }

    @Test
    void finalSourceContainsNoRetiredLazyMutableTableOrDispatcherSurface() {
        String source = emit(65).source();

        assertFalse(source.contains("#include <stdatomic.h>"));
        assertFalse(source.contains("_Atomic"));
        assertFalse(source.contains("atomic_"));
        assertFalse(source.contains("j2ll_gcf_low_"));
        assertFalse(source.contains("j2ll_gcf_decode_"));
        assertFalse(source.contains("j2ll_l_"));
        assertFalse(source.contains("low_throw_leaf_table"));
        assertFalse(source.contains("low_throw_dispatch"));
        assertFalse(source.contains(
                HostJniLowSensitivityThrowShardDeriver
                        .placeholderPrefix()));
        assertFalse(source.contains(
                HostJniLowSensitivityThrowShardDeriver.anchorPrefix()));
    }

    @Test
    void finalizationAndVerificationEachHappenExactlyOnce() {
        NativeTextBuildKey buildKey =
                NativeTextBuildKey.fromUtf8("emitter-lifecycle-build");
        StringBuilder source = baseSource();
        HostJniGeneratedCFragmentEmitter emitter =
                new HostJniGeneratedCFragmentEmitter(
                        source,
                        new GeneratedCFragmentTextObfuscator(),
                        buildKey,
                        new HostJniLowSensitivityThrowLeafPool(buildKey));
        emitter.append(
                "lifecycle-fragment",
                HostJniLowSensitivityThrowShardFixture
                        .singleFragment(1)
                        .fragments()
                        .get(0)
                        .source());

        emitter.appendLowSensitivityLeaves();
        assertThrows(
                IllegalStateException.class,
                emitter::appendLowSensitivityLeaves);
        assertThrows(
                IllegalStateException.class,
                () -> emitter.append("late-fragment", "static void late(void) {}"));
        emitter.verifyFinalSource();
        assertThrows(
                IllegalStateException.class,
                emitter::verifyFinalSource);
    }

    private Emitted emit(int useCount) {
        NativeTextBuildKey buildKey = NativeTextBuildKey.fromUtf8(
                "emitter-build-" + useCount);
        StringBuilder source = baseSource();
        HostJniGeneratedCFragmentEmitter emitter =
                new HostJniGeneratedCFragmentEmitter(
                        source,
                        new GeneratedCFragmentTextObfuscator(),
                        buildKey,
                        new HostJniLowSensitivityThrowLeafPool(buildKey));
        HostJniLowSensitivityThrowShardFixture.Scenario scenario =
                useCount == 65
                        ? HostJniLowSensitivityThrowShardFixture
                                .fragments(32, 33)
                        : HostJniLowSensitivityThrowShardFixture
                                .singleFragment(useCount);
        for (HostJniLowSensitivityThrowShardFixture.Fragment fragment
                : scenario.fragments()) {
            emitter.append(fragment.scope(), fragment.source());
        }
        emitter.appendLowSensitivityLeaves();
        emitter.verifyFinalSource();
        return new Emitted(
                emitter.frozenPlan(),
                source.toString());
    }

    private StringBuilder baseSource() {
        return new StringBuilder()
                .append(new NativeTextCEmitter().runtimeSource())
                .append(HostJniRegistrationRuntimeSource.helperSource());
    }

    private HashSet<String> cipherBodies(String source) {
        java.util.regex.Matcher matcher = cipherPattern().matcher(source);
        HashSet<String> bodies = new HashSet<>();
        while (matcher.find()) {
            bodies.add(matcher.group(2));
        }
        return bodies;
    }

    private HashSet<String> cipherNames(String source) {
        java.util.regex.Matcher matcher = cipherPattern().matcher(source);
        HashSet<String> names = new HashSet<>();
        while (matcher.find()) {
            names.add(matcher.group(1));
        }
        return names;
    }

    private Pattern cipherPattern() {
        return Pattern.compile(
                "(?s)static const unsigned char "
                        + "(j2ll_nt_[0-9a-f]{24})_cipher\\[\\] = "
                        + "\\{(.*?)\\};");
    }

    private int occurrences(String value, String needle) {
        return value.split(Pattern.quote(needle), -1).length - 1;
    }

    private record Emitted(
            HostJniLowSensitivityThrowShardPlan plan,
            String source) {}
}
