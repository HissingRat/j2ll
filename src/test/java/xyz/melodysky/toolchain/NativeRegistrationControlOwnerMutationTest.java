package xyz.melodysky.toolchain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

final class NativeRegistrationControlOwnerMutationTest {
    @Test
    void failureLeavesMustRemainExactDirectCallsRatherThanAddressReferences() {
        HostNativeRegistrationSource.Emission emission =
                NativeRegistrationControlTestFixture.emission(
                        11,
                        "registration-owner-leaf-call-closure");
        NativeRegistrationControlTopologyPlan plan = emission.topologyPlan();
        NativeRegistrationControlTopologyPlan.Owner owner =
                plan.owners().get(0);
        String definition = NativeRegistrationControlTestFixture.function(
                emission.source(),
                owner.symbol());

        for (String leaf : List.of(
                plan.failureSymbols().ownerRollback(),
                plan.failureSymbols().ownerExceptionRestore())) {
            String direct = leaf + "(env);";
            assertEquals(
                    1,
                    NativeRegistrationControlTestFixture.occurrences(
                            definition,
                            direct));
            String mutatedDefinition = definition.replace(
                    direct,
                    "(void)&" + leaf + ";");
            IllegalStateException failure = assertThrows(
                    IllegalStateException.class,
                    () -> new NativeRegistrationControlSourceVerifier()
                            .verify(
                                    replaceOnce(
                                            emission.source(),
                                            definition,
                                            mutatedDefinition),
                                    plan));
            assertEquals(
                    "native registration control topology audit failed: "
                            + "OWNER_HELPER_CLOSURE",
                    failure.getMessage());
        }
    }

    private String replaceOnce(
            String source,
            String before,
            String after) {
        assertEquals(
                1,
                NativeRegistrationControlTestFixture.occurrences(
                        source,
                        before));
        return source.replace(before, after);
    }
}
