package xyz.melodysky.toolchain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import org.junit.jupiter.api.Test;

final class NativeRegistrationControlEmissionTest {
    @Test
    void finalVerifierAcceptsZeroOneElevenAndFallbackTopologies() {
        for (int ownerCount : List.of(0, 1, 11, 33)) {
            HostNativeRegistrationSource.Emission emission =
                    NativeRegistrationControlTestFixture.emission(
                            ownerCount,
                            "registration-emission-" + ownerCount);

            new NativeRegistrationControlSourceVerifier().verify(
                    emission.source(),
                    emission.topologyPlan());
        }
    }

    @Test
    void onLoadCallsOnlyTheUniqueAggregateAndAggregateCallsOnlyFirstChunk() {
        HostNativeRegistrationSource.Emission emission =
                NativeRegistrationControlTestFixture.emission(
                        11,
                        "registration-root-closure");
        NativeRegistrationControlTopologyPlan plan =
                emission.topologyPlan();
        String source = emission.source();
        String onLoad = NativeRegistrationControlTestFixture.functionAtHeader(
                source,
                "JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {");
        String aggregate = NativeRegistrationControlTestFixture.function(
                source,
                plan.aggregateSymbol());

        assertEquals(
                1,
                NativeRegistrationControlTestFixture.occurrences(
                        source,
                        "JNIEXPORT jint JNICALL JNI_OnLoad"));
        assertEquals(
                "JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {\n"
                        + "    (void)reserved;\n"
                        + "    return "
                        + plan.aggregateSymbol()
                        + "(vm);\n}",
                onLoad);
        assertTrue(aggregate.contains(
                plan.chunks().get(0).symbol()
                        + "(env, &resolver, registered_owners, &registered_count)"));
        for (NativeRegistrationControlTopologyPlan.Owner owner
                : plan.owners()) {
            assertFalse(aggregate.contains(owner.symbol()));
            assertFalse(onLoad.contains(owner.symbol()));
        }
        for (int ordinal = 1; ordinal < plan.chunks().size(); ordinal++) {
            assertFalse(aggregate.contains(
                    plan.chunks().get(ordinal).symbol()));
            assertFalse(onLoad.contains(
                    plan.chunks().get(ordinal).symbol()));
        }
    }

    @Test
    void aggregateChunksOwnersAndFailureLeavesAreHashOnlyNoinlineAndTableFree() {
        HostNativeRegistrationSource.Emission emission =
                NativeRegistrationControlTestFixture.emission(
                        11,
                        "registration-control-surface");
        NativeRegistrationControlTopologyPlan plan =
                emission.topologyPlan();
        String source = emission.source();
        List<String> symbols =
                NativeRegistrationControlTestFixture.controlSymbols(plan);

        assertEquals(symbols.size(), new HashSet<>(symbols).size());
        assertTrue(symbols.stream().allMatch(symbol ->
                symbol.matches("[a-p]{32}")));
        assertTrue(source.contains(
                "static jint "
                        + plan.aggregateSymbol()
                        + "(JavaVM* vm) __attribute__((noinline));"));
        for (NativeRegistrationControlTopologyPlan.Owner owner
                : plan.owners()) {
            assertTrue(source.contains(
                    "static jint "
                            + owner.symbol()
                            + "(JNIEnv* env, const j2ll_registration_resolver* resolver, jclass* registered_owner) __attribute__((noinline));"));
        }
        for (NativeRegistrationControlTopologyPlan.Chunk chunk
                : plan.chunks()) {
            assertTrue(source.contains(
                    "static jint "
                            + chunk.symbol()
                            + "(JNIEnv* env, const j2ll_registration_resolver* resolver, jclass* registered_owners, size_t* registered_count) __attribute__((noinline));"));
        }
        for (String failure : plan.failureSymbols().symbols()) {
            assertTrue(source.contains(
                    "static void "
                            + failure
                            + "(JNIEnv* env) __attribute__((noinline, cold));"));
        }

        assertFalse(source.contains("j2ll_register_"));
        assertFalse(source.contains("j2ll_registration_failure_"));
        assertFalse(source.contains("dispatcher"));
        assertFalse(source.matches(
                "(?s).*static\\s+(?:const\\s+)?JNINativeMethod\\s+.*"));
        assertFalse(source.matches(
                "(?s).*static\\s+[^;{}]*\\(\\*[^)]*\\)\\s*\\[[^]]*].*"));
    }
}
