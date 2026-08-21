package xyz.melodysky.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import xyz.melodysky.analysis.callgraph.CallGraph;
import xyz.melodysky.analysis.callgraph.CallResolution;
import xyz.melodysky.analysis.callgraph.CallSite;
import xyz.melodysky.analysis.callgraph.CallTarget;
import xyz.melodysky.analysis.callgraph.DevirtualizationPlanner;
import xyz.melodysky.analysis.callgraph.InvokeKind;
import xyz.melodysky.analysis.hierarchy.AnalysisWorld;
import xyz.melodysky.analysis.runtime.ReachabilityResult;
import xyz.melodysky.analysis.runtime.RuntimeTypeResult;
import xyz.melodysky.jvm.MethodSignature;
import xyz.melodysky.pipeline.ProgramCallGraphAnalysis;

class CallAnalysisReportWriterTest {
    @Test
    void loweringReportPreservesEveryExactCallSiteDecision() {
        MethodSignature caller = new MethodSignature("call", "()V");
        MethodSignature target = new MethodSignature("run", "()V");
        CallTarget child = CallTarget.known("pkg/Child", target);
        CallGraph graph = new CallGraph(List.of(
                resolution(new CallSite(
                        "pkg/Caller#call!()V@3",
                        "pkg/Caller",
                        caller,
                        3,
                        InvokeKind.VIRTUAL,
                        "pkg/Base",
                        target), child),
                resolution(new CallSite(
                        "pkg/Caller#call!()V@7",
                        "pkg/Caller",
                        caller,
                        7,
                        InvokeKind.VIRTUAL,
                        "pkg/Base",
                        target), child)));
        ProgramCallGraphAnalysis analysis = new ProgramCallGraphAnalysis(
                graph,
                new RuntimeTypeResult(Set.of("pkg/Child"), false, List.of()),
                new ReachabilityResult(
                        Set.of("pkg/Caller#call!()V"),
                        Set.of("pkg/Caller#call!()V", "pkg/Child#run!()V"),
                        Set.of(),
                        2),
                new DevirtualizationPlanner().plan(graph),
                true);

        var root = JsonParser.parseString(new ReportJsonWriter().loweringJson(
                        List.of(),
                        List.of(),
                        List.of(),
                        analysis,
                        AnalysisWorld.CLOSED_WORLD))
                .getAsJsonObject();
        var callAnalysis = root.getAsJsonObject("callAnalysis");
        assertEquals("completed", callAnalysis.get("status").getAsString());
        assertTrue(callAnalysis.get("rtaApplied").getAsBoolean());
        assertEquals(2, callAnalysis.get("fixedPointIterations").getAsInt());
        assertEquals(2, callAnalysis.get("callSiteCount").getAsInt());
        assertEquals(2, callAnalysis.get("directCallSiteCount").getAsInt());
        assertEquals(2, callAnalysis.getAsJsonArray("decisions").size());
        assertEquals(
                List.of("pkg/Caller#call!()V@3", "pkg/Caller#call!()V@7"),
                callAnalysis.getAsJsonArray("decisions").asList().stream()
                        .map(element -> element.getAsJsonObject()
                                .get("callSiteId").getAsString())
                        .toList());
        assertTrue(callAnalysis.getAsJsonArray("decisions").asList().stream()
                .allMatch(element -> element.getAsJsonObject()
                        .get("callerReachable").getAsBoolean()));
        assertFalse(callAnalysis.get("runtimeTypesConservative").getAsBoolean());
    }

    private CallResolution resolution(CallSite site, CallTarget target) {
        return new CallResolution(site, List.of(target), false, "RTA_REFINED");
    }
}
