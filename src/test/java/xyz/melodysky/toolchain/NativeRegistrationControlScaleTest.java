package xyz.melodysky.toolchain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeout;

import java.time.Duration;
import org.junit.jupiter.api.Test;

final class NativeRegistrationControlScaleTest {
    @Test
    void twoHundredFiftySixOwnersRemainBounded() {
        assertTimeout(
                Duration.ofSeconds(15),
                () -> emitAndVerify(256));
    }

    @Test
    void oneThousandTwentyFourOwnersDoNotRegressToQuadraticVerification() {
        assertTimeout(
                Duration.ofSeconds(30),
                () -> emitAndVerify(1024));
    }

    private void emitAndVerify(int ownerCount) {
        HostNativeRegistrationSource.Emission emission =
                NativeRegistrationControlTestFixture.emission(
                        ownerCount,
                        "registration-control-scale-" + ownerCount);
        NativeRegistrationControlTopologyPlan plan =
                emission.topologyPlan();
        assertEquals(
                NativeRegistrationControlTopologyPlan.MAX_CHUNKS,
                plan.chunks().size());
        assertEquals(ownerCount, plan.owners().size());
        new NativeRegistrationControlSourceVerifier().verify(
                emission.source(),
                plan);
    }
}
