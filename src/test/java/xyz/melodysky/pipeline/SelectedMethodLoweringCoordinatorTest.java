package xyz.melodysky.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import xyz.melodysky.analysis.callgraph.DevirtualizationPlan;
import xyz.melodysky.frontend.classfile.AsmClassParser;
import xyz.melodysky.frontend.classfile.ClassFileEntry;
import xyz.melodysky.frontend.classfile.ParsedProgram;
import xyz.melodysky.ir.ssa.BytecodeToSsaLowerer;
import xyz.melodysky.ir.ssa.LoweringDiagnostics;
import xyz.melodysky.packaging.MethodRewritePlanner;
import xyz.melodysky.progress.BuildProgressListener;
import xyz.melodysky.testsupport.AsmFixtureBuilder;
import xyz.melodysky.testsupport.TestProtectionMaterials;

class SelectedMethodLoweringCoordinatorTest {
    @Test
    void failedAuthoritativeCallPlanBecomesStageDiagnosticInsteadOfAnException() {
        var parsedClass = new AsmClassParser()
                .parse(new ClassFileEntry(
                        "pkg/Caller.class",
                        AsmFixtureBuilder.classWithVirtualCall(
                                "pkg/Caller",
                                "pkg/Receiver"),
                        "fixture"))
                .artifact()
                .orElseThrow();
        var method = parsedClass.methods().stream()
                .filter(candidate -> candidate.name().equals("call"))
                .findFirst()
                .orElseThrow();

        SelectedMethodLoweringCoordinator.Result result =
                new SelectedMethodLoweringCoordinator(new MethodRewritePlanner()).run(
                        new ParsedProgram(List.of(parsedClass)),
                        List.of(method),
                        Set.of(),
                        new BytecodeToSsaLowerer(
                                TestProtectionMaterials.runtimeTokens(),
                                new DevirtualizationPlan(List.of())),
                        TestProtectionMaterials.initializerPlanner(),
                        17L,
                        new MainlineProgress(BuildProgressListener.none()));

        assertEquals(1, result.cfgByMethod().size());
        assertTrue(result.ssaResults().isEmpty());
        assertTrue(result.rawIr().isEmpty());
        assertTrue(result.optimizedIr().isEmpty());
        assertEquals(LoweringDiagnostics.CALL_ANALYSIS_PLAN_MISMATCH,
                result.diagnostics().get(0).code());
    }
}
