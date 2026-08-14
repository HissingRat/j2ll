package xyz.melodysky.toolchain;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import org.junit.jupiter.api.Test;

class NativeJniEntryFusionValidatorTest {
    @Test
    void rejectsAHandBuiltDirectPlanThatBypassesFinalIrEvidence() {
        NativeJniEntryTestFixture.Fixture fixture =
                NativeJniEntryTestFixture.proxyWithoutIrEvidence();

        assertEquals(
                java.util.List.of(
                        fixture.method().methodKey()
                                + ":LLVM_JNI_PROXY_IR_MISSING"),
                new NativeJniEntryFusionValidator().validate(
                        fixture.plan(),
                        Map.of()));
    }
}
