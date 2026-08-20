package xyz.melodysky.toolchain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

final class NativeRegistrationControlChunkMutationTest {
    @Test
    void rejectsMissingDuplicateReorderedAndNoncontiguousOwnerSteps() {
        Fixture fixture = fixture();
        NativeRegistrationControlTopologyPlan.Owner first =
                fixture.plan().owners().get(0);
        NativeRegistrationControlTopologyPlan.Owner second =
                fixture.plan().owners().get(1);
        String firstStep = step(first);
        String secondStep = step(second);

        assertRejected(
                fixture,
                replaceOnce(fixture.source(), firstStep, ""),
                "SYMBOL_REFERENCE_CLOSURE");
        assertRejected(
                fixture,
                replaceOnce(
                        fixture.source(),
                        firstStep,
                        firstStep + firstStep),
                "SYMBOL_REFERENCE_CLOSURE");
        assertRejected(
                fixture,
                replaceOnce(
                        fixture.source(),
                        firstStep + secondStep,
                        secondStep + firstStep),
                "CHUNK_CLOSED_SCHEMA");
        assertRejected(
                fixture,
                replaceOnce(
                        fixture.source(),
                        firstStep,
                        firstStep.replace(
                                "&registered_owners[0]",
                                "&registered_owners[2]")),
                "CHUNK_CLOSED_SCHEMA");
    }

    @Test
    void rejectsSkippedDuplicateAndBackwardChunkEdges() {
        Fixture fixture = fixture();
        List<NativeRegistrationControlTopologyPlan.Chunk> chunks =
                fixture.plan().chunks();
        String zeroToOne = edge(chunks.get(0), chunks.get(1).symbol());
        String oneToTwo = edge(chunks.get(1), chunks.get(2).symbol());
        String terminal = "    return JNI_OK;\n";

        assertRejected(
                fixture,
                replaceOnce(
                        fixture.source(),
                        zeroToOne,
                        edge(chunks.get(0), chunks.get(2).symbol())),
                "SYMBOL_REFERENCE_CLOSURE");
        assertRejected(
                fixture,
                replaceOnce(
                        fixture.source(),
                        terminal,
                        edge(chunks.get(2), chunks.get(2).symbol())),
                "SYMBOL_REFERENCE_CLOSURE");
        assertRejected(
                fixture,
                replaceOnce(
                        fixture.source(),
                        oneToTwo,
                        edge(chunks.get(1), chunks.get(0).symbol())),
                "SYMBOL_REFERENCE_CLOSURE");
    }

    @Test
    void rejectsTailShortcutAndMissingVolatileForwardContinuation() {
        Fixture fixture = fixture();
        NativeRegistrationControlTopologyPlan.Chunk chunk =
                fixture.plan().chunks().get(0);
        String next = fixture.plan().chunks().get(1).symbol();
        String forward = edge(chunk, next);
        String call = next
                + "(env, resolver, registered_owners, registered_count)";

        assertRejected(
                fixture,
                replaceOnce(
                        fixture.source(),
                        forward,
                        "    return " + call + ";\n"),
                "CHUNK_CLOSED_SCHEMA");
        assertRejected(
                fixture,
                replaceOnce(
                        fixture.source(),
                        forward,
                        forward.replace("volatile ", "")),
                "CHUNK_CLOSED_SCHEMA");
    }

    @Test
    void rejectsCountBeforeSuccessAndEveryWrongCount() {
        Fixture fixture = fixture();
        NativeRegistrationControlTopologyPlan.Owner first =
                fixture.plan().owners().get(0);
        String step = step(first);
        String count = "    *registered_count = 1u;\n";
        String call = step.substring(0, step.length() - count.length());

        assertRejected(
                fixture,
                replaceOnce(
                        fixture.source(),
                        step,
                        count + call),
                "CHUNK_CLOSED_SCHEMA");
        assertRejected(
                fixture,
                replaceOnce(
                        fixture.source(),
                        step,
                        step.replace(
                                "*registered_count = 1u;",
                                "*registered_count = 2u;")),
                "CHUNK_CLOSED_SCHEMA");
    }

    @Test
    void rejectsJniResolverRollbackAndTableLogicInsideChunks() {
        Fixture fixture = fixture();
        String forward = edge(
                fixture.plan().chunks().get(0),
                fixture.plan().chunks().get(1).symbol());
        for (String injection : List.of(
                "    (void)(*env)->ExceptionCheck(env);\n",
                "    (void)(*env)->UnregisterNatives(env, registered_owners[0]);\n",
                "    (void)j2ll_registration_resolver_open(env, NULL, NULL);\n",
                "    JNINativeMethod forbidden_table[1];\n")) {
            assertRejected(
                    fixture,
                    replaceOnce(
                            fixture.source(),
                            forward,
                            injection + forward),
                    "CHUNK_CLOSED_SCHEMA");
        }
    }

    private Fixture fixture() {
        HostNativeRegistrationSource.Emission emission =
                NativeRegistrationControlTestFixture.emission(
                        11,
                        "registration-chunk-mutations");
        return new Fixture(
                emission.source(),
                emission.topologyPlan());
    }

    private String step(
            NativeRegistrationControlTopologyPlan.Owner owner) {
        return "    if ("
                + owner.symbol()
                + "(env, resolver, &registered_owners["
                + owner.index()
                + "]) != JNI_OK) {\n"
                + "        return JNI_ERR;\n"
                + "    }\n"
                + "    *registered_count = "
                + (owner.index() + 1)
                + "u;\n";
    }

    private String edge(
            NativeRegistrationControlTopologyPlan.Chunk chunk,
            String symbol) {
        String call = symbol
                + "(env, resolver, registered_owners, registered_count)";
        return "    volatile uintptr_t witness = (uintptr_t)(void*)registered_owners\n"
                + "            ^ (uintptr_t)(void*)registered_count\n"
                + "            ^ "
                + NativeRegistrationPostCallCSource.unsignedLong(
                        chunk.witnessSalt())
                + ";\n"
                + new NativeRegistrationChunkPostCallCSource()
                        .callAndReturn(
                                chunk.postCallVariant(),
                                call,
                                chunk.postCallSalt(),
                                "    ");
    }

    private String replaceOnce(
            String source,
            String before,
            String after) {
        assertEquals(1, NativeRegistrationControlTestFixture.occurrences(
                source,
                before));
        return source.replace(before, after);
    }

    private void assertRejected(
            Fixture fixture,
            String source,
            String reason) {
        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> new NativeRegistrationControlSourceVerifier()
                        .verify(source, fixture.plan()));
        assertEquals(
                "native registration control topology audit failed: "
                        + reason,
                failure.getMessage());
    }

    private record Fixture(
            String source,
            NativeRegistrationControlTopologyPlan plan) {}
}
