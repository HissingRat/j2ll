package xyz.melodysky.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import xyz.melodysky.analysis.runtime.RuntimeHelperSite;

final class HelperBackedSiteReportFactoryTest {
    private final HelperBackedSiteReportFactory factory =
            new HelperBackedSiteReportFactory();

    @Test
    void removesBusinessStringAndMemberIdentityFromReportEvidence() {
        String businessString = "Disconnected from MelodySky-WS";
        HelperBackedSiteReport stringSite = factory.create(
                new RuntimeHelperSite(
                        "j2ll_rt_string_constant|string:" + businessString,
                        "STRING_CONSTANT_HELPER"));
        HelperBackedSiteReport callSite = factory.create(
                new RuntimeHelperSite(
                        "call:secret/Owner#sensitiveMethod!()V",
                        "CALL_HELPER"));

        assertEquals(
                "j2ll_rt_string_constant",
                stringSite.helperKind());
        assertEquals("call", callSite.helperKind());
        assertTrue(stringSite.helperIdentityHash().matches("[0-9a-f]{64}"));
        assertTrue(callSite.helperIdentityHash().matches("[0-9a-f]{64}"));
        assertNotEquals(
                stringSite.helperIdentityHash(),
                callSite.helperIdentityHash());
        String json = new ReportJsonWriter().loweringJson(
                java.util.List.of(new LoweringReportMethod(
                        "pkg/Fixture",
                        "run",
                        "()V",
                        "run__fixture",
                        xyz.melodysky.pipeline.LoweringStatus.NATIVE_LOWERED,
                        xyz.melodysky.packaging.MethodRewriteStrategy.NATIVE_ORIGINAL,
                        java.util.List.of(),
                        java.util.List.of(),
                        null,
                        null,
                        "LLVM_NATIVE_PATH",
                        java.util.List.of(stringSite, callSite),
                        null,
                        null)),
                java.util.List.of(),
                java.util.List.of());
        assertFalse(json.contains(businessString), json);
        assertFalse(json.contains("secret/Owner"), json);
        assertFalse(json.contains("sensitiveMethod"), json);
        assertFalse(json.contains("\"helper\""), json);
    }
}
