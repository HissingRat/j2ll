package xyz.melodysky.toolchain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import org.junit.jupiter.api.Test;

final class HostNativeRegistrationChunkSourceTest {
    @Test
    void zeroOwnersEmitNoChunkSurface() {
        NativeRegistrationControlTopologyPlan plan =
                NativeRegistrationControlTestFixture.plan(
                        0,
                        "registration-empty-chunks");

        assertEquals("", new HostNativeRegistrationChunkSource().emit(plan));
    }

    @Test
    void chunksFormOneExactForwardOnlyContiguousFailFastClosure() {
        NativeRegistrationControlTopologyPlan plan =
                NativeRegistrationControlTestFixture.plan(
                        11,
                        "registration-forward-chunks");
        String source = new HostNativeRegistrationChunkSource().emit(plan);

        assertEquals(3, plan.chunks().size());
        assertEquals(
                plan.chunks().size(),
                source.lines()
                        .filter(line -> line.contains(
                                NativeRegistrationControlCFunctionPolicy
                                        .ATTRIBUTES))
                        .count());

        HashSet<String> observedOwners = new HashSet<>();
        for (int chunkIndex = 0;
                chunkIndex < plan.chunks().size();
                chunkIndex++) {
            NativeRegistrationControlTopologyPlan.Chunk chunk =
                    plan.chunks().get(chunkIndex);
            String body = NativeRegistrationControlTestFixture.function(
                    source,
                    chunk.symbol());

            assertNoDirectJniOrRegistrationStateMachine(body);
            assertTrue(chunk.symbol().matches("[a-p]{32}"));

            int previousCount = -1;
            for (NativeRegistrationControlTopologyPlan.Owner owner
                    : chunk.owners()) {
                String call = owner.symbol()
                        + "(env, resolver, &registered_owners["
                        + owner.index()
                        + "]) != JNI_OK";
                String failure = "return JNI_ERR;";
                String count = "*registered_count = "
                        + (owner.index() + 1)
                        + "u;";
                int callOffset = body.indexOf(call);
                int failureOffset = body.indexOf(failure, callOffset);
                int countOffset = body.indexOf(count, failureOffset);

                assertTrue(callOffset > previousCount, owner.symbol());
                assertTrue(failureOffset > callOffset, owner.symbol());
                assertTrue(countOffset > failureOffset, owner.symbol());
                assertEquals(
                        1,
                        NativeRegistrationControlTestFixture.occurrences(
                                body,
                                call));
                assertTrue(observedOwners.add(owner.symbol()));
                previousCount = countOffset;
            }

            for (NativeRegistrationControlTopologyPlan.Owner owner
                    : plan.owners()) {
                assertEquals(
                        chunk.owners().contains(owner),
                        body.contains(owner.symbol() + "(env, resolver,"),
                        owner.symbol());
            }

            if (chunkIndex + 1 < plan.chunks().size()) {
                String expectedNext = plan.chunks()
                        .get(chunkIndex + 1)
                        .symbol()
                        + "(env, resolver, registered_owners, registered_count)";
                assertEquals(
                        1,
                        NativeRegistrationControlTestFixture.occurrences(
                                body,
                                expectedNext));
                assertFalse(body.contains("return " + expectedNext + ";"));
                assertTrue(body.contains("volatile uintptr_t witness"));
                assertTrue(body.contains(
                        new NativeRegistrationChunkPostCallCSource()
                                .callAndReturn(
                                        chunk.postCallVariant(),
                                        expectedNext,
                                        chunk.postCallSalt(),
                                        "    ")));
            } else {
                assertTrue(body.contains("return JNI_OK;"));
            }
            for (int other = 0; other < plan.chunks().size(); other++) {
                if (other != chunkIndex + 1) {
                    assertFalse(
                            body.contains(plan.chunks().get(other).symbol()
                                    + "(env, resolver, registered_owners, registered_count)"),
                            "unexpected chunk edge " + chunkIndex + " -> " + other);
                }
            }
        }
        assertEquals(plan.owners().size(), observedOwners.size());
    }

    private void assertNoDirectJniOrRegistrationStateMachine(String body) {
        assertFalse(body.contains("(*env)->"));
        assertFalse(body.contains("RegisterNatives"));
        assertFalse(body.contains("UnregisterNatives"));
        assertFalse(body.contains("ExceptionCheck"));
        assertFalse(body.contains("ExceptionOccurred"));
        assertFalse(body.contains("ExceptionClear"));
        assertFalse(body.contains("j2ll_registration_resolver_open"));
        assertFalse(body.contains("j2ll_registration_resolver_close"));
        assertFalse(body.contains("JNINativeMethod"));
        assertFalse(body.contains("rollback:"));
    }
}
