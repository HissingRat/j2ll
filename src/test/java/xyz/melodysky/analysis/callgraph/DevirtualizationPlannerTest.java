package xyz.melodysky.analysis.callgraph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import xyz.melodysky.jvm.MethodSignature;

class DevirtualizationPlannerTest {
    private static final MethodSignature RUN = new MethodSignature("run", "()V");
    private static final MethodSignature CALLER = new MethodSignature("caller", "()V");

    @Test
    void singleTargetBecomesDirectDecision() {
        CallSite site = site("single");
        CallGraph graph = new CallGraph(List.of(new CallResolution(
                site,
                List.of(CallTarget.known("pkg/Only", RUN)),
                false,
                "CHA")));

        DevirtualizationDecision decision = new DevirtualizationPlanner().plan(graph)
                .decisionFor("single")
                .orElseThrow();

        assertFalse(decision.fallbackRequired());
        assertEquals("SINGLE_TARGET", decision.reason());
        assertEquals("pkg/Only#run!()V", decision.directTarget().orElseThrow().displayName());
    }

    @Test
    void multipleTargetsRecordFallbackReason() {
        CallSite site = site("multi");
        CallGraph graph = new CallGraph(List.of(new CallResolution(
                site,
                List.of(CallTarget.known("pkg/A", RUN), CallTarget.known("pkg/B", RUN)),
                false,
                "CHA")));

        DevirtualizationDecision decision = new DevirtualizationPlanner().plan(graph)
                .decisionFor("multi")
                .orElseThrow();

        assertTrue(decision.fallbackRequired());
        assertEquals("MULTIPLE_TARGETS", decision.reason());
        assertTrue(decision.jvmHelperFallbackRequired());
    }

    @Test
    void unknownTargetRecordsJvmHelperFallback() {
        CallSite site = site("unknown");
        CallGraph graph = new CallGraph(List.of(new CallResolution(
                site,
                List.of(CallTarget.unknownExternal("HIERARCHY_INCOMPLETE")),
                true,
                "CHA")));

        DevirtualizationDecision decision = new DevirtualizationPlanner().plan(graph)
                .decisionFor("unknown")
                .orElseThrow();

        assertTrue(decision.fallbackRequired());
        assertEquals("UNKNOWN_EXTERNAL_TARGET", decision.reason());
        assertTrue(decision.jvmHelperFallbackRequired());
    }

    private CallSite site(String id) {
        return new CallSite(id, "pkg/Caller", CALLER, 0, InvokeKind.VIRTUAL, "pkg/Base", RUN);
    }
}
