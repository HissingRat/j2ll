package xyz.melodysky.ir.ssa;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import xyz.melodysky.diagnostic.DiagnosticCode;
import xyz.melodysky.frontend.cfg.MethodCfgBuilder;
import xyz.melodysky.frontend.cfg.MethodCfgResult;
import xyz.melodysky.frontend.classfile.AsmClassParser;
import xyz.melodysky.frontend.classfile.ClassFileEntry;
import xyz.melodysky.frontend.classfile.ParsedClass;
import xyz.melodysky.frontend.classfile.ParsedMethod;
import xyz.melodysky.ir.validate.IrMethodValidator;
import xyz.melodysky.pipeline.LoweringStatus;
import xyz.melodysky.report.OpcodeSupportMatrixWriter;
import xyz.melodysky.testsupport.AsmFixtureBuilder;

class WeirdBytecodeSeedCorpusTest {
    @Test
    void supportedWeirdBytecodeSeedsLowerWithoutSilentSkip() {
        for (SupportedSeed seed : List.of(
                new SupportedSeed("stack-dup-swap", AsmFixtureBuilder.classWithStackPermutationMethods("pkg/StackOps"), "stackInt"),
                new SupportedSeed("category2-dup-x2", AsmFixtureBuilder.classWithStackPermutationMethods("pkg/StackOps"), "dupX2Long"),
                new SupportedSeed("stack-dup2", AsmFixtureBuilder.classWithStackPermutationMethods("pkg/StackOps"), "dup2X2Int"),
                new SupportedSeed("wide-iinc", AsmFixtureBuilder.classWithWideLocalIincMethod("pkg/WideLocals"), "wideIinc"),
                new SupportedSeed("unreachable-block", AsmFixtureBuilder.classWithUnreachableBlockMethod("pkg/Unreachable"), "unreachable"),
                new SupportedSeed("table-switch", AsmFixtureBuilder.classWithTableSwitchMethod("pkg/TableSwitch"), "select"),
                new SupportedSeed("lookup-switch", AsmFixtureBuilder.classWithLookupSwitchMethod("pkg/LookupSwitch"), "lookup"),
                new SupportedSeed("catch-all-rethrow", AsmFixtureBuilder.classWithCatchAllFinallyShape("pkg/FinallyShape"), "cleanup"))) {
            SsaMethodResult result = lower(seed.classBytes(), seed.methodName());

            assertEquals(LoweringStatus.LOWERED, result.status(), seed.name());
            assertTrue(result.irMethod().isPresent(), seed.name());
            assertTrue(new IrMethodValidator().validate(result.irMethod().orElseThrow()).isEmpty(), seed.name());
        }
    }

    @Test
    void unsupportedWeirdBytecodeSeedsProducePreciseFrontendDiagnostics() {
        for (UnsupportedSeed seed : List.of(
                new UnsupportedSeed(
                        "multi-exit-finally",
                        AsmFixtureBuilder.classWithUnsupportedMultiExitFinallyShape("pkg/BadFinally"),
                        "badFinally",
                        LoweringDiagnostics.UNSUPPORTED_MULTI_EXIT_FINALLY),
                new UnsupportedSeed(
                        "exception-state-merge-finally",
                        AsmFixtureBuilder.classWithUnsupportedExceptionStateMergeFinallyShape("pkg/StateMergeFinally"),
                        "badStateMergeFinally",
                        LoweringDiagnostics.UNSUPPORTED_EXCEPTION_STATE_MERGE),
                new UnsupportedSeed(
                        "monitor-finally-interaction",
                        AsmFixtureBuilder.classWithUnsupportedMonitorFinallyInteraction("pkg/MonitorFinally"),
                        "badMonitorFinally",
                        LoweringDiagnostics.UNSUPPORTED_MONITOR_FINALLY_INTERACTION),
                new UnsupportedSeed(
                        "nested-finally",
                        AsmFixtureBuilder.classWithUnsupportedNestedFinallyShape("pkg/NestedFinally"),
                        "badNestedFinally",
                        LoweringDiagnostics.UNSUPPORTED_NESTED_FINALLY),
                new UnsupportedSeed(
                        "legacy-jsr-ret",
                        AsmFixtureBuilder.classWithLegacyJsrRetSubroutine("pkg/LegacySubroutine"),
                        "legacyFinally",
                        LoweringDiagnostics.UNSUPPORTED_FINALLY_SUBROUTINE))) {
            ParsedMethod parsedMethod = parseMethod(seed.classBytes(), seed.methodName());
            MethodCfgResult cfg = new MethodCfgBuilder().build(parsedMethod).artifact().orElseThrow();
            var result = new BytecodeToSsaLowerer().lower(cfg);

            assertFalse(result.hasErrors(), seed.name());
            assertEquals(LoweringStatus.FRONTEND_SKIPPED, result.artifact().orElseThrow().status(), seed.name());
            assertEquals(seed.reasonCode(), result.diagnostics().get(0).code(), seed.name());
        }
    }

    @Test
    void opcodeSupportMatrixNamesSeededStackSwitchAndFinallyBoundaries() {
        String json = new OpcodeSupportMatrixWriter().json();

        assertTrue(json.contains("\"opcode\": \"dup/dup_x1/dup2/swap\""));
        assertTrue(json.contains("\"opcode\": \"tableswitch/lookupswitch\""));
        assertTrue(json.contains("\"opcode\": \"jsr/ret\""));
        assertTrue(json.contains("\"reasonCode\": \"UNSUPPORTED_FINALLY_SUBROUTINE\""));
        assertTrue(json.contains("\"reasonCode\": \"UNSUPPORTED_EXCEPTION_STATE_MERGE\""));
    }

    private SsaMethodResult lower(byte[] classBytes, String methodName) {
        ParsedMethod parsedMethod = parseMethod(classBytes, methodName);
        MethodCfgResult cfg = new MethodCfgBuilder().build(parsedMethod).artifact().orElseThrow();
        var result = new BytecodeToSsaLowerer().lower(cfg);
        assertFalse(result.hasErrors());
        return result.artifact().orElseThrow();
    }

    private ParsedMethod parseMethod(byte[] classBytes, String methodName) {
        ParsedClass parsedClass = new AsmClassParser()
                .parse(new ClassFileEntry("fixture.class", classBytes, "fixture"))
                .artifact()
                .orElseThrow();
        return parsedClass.methods().stream()
                .filter(method -> method.name().equals(methodName))
                .findFirst()
                .orElseThrow();
    }

    private record SupportedSeed(String name, byte[] classBytes, String methodName) {}

    private record UnsupportedSeed(
            String name,
            byte[] classBytes,
            String methodName,
            DiagnosticCode reasonCode) {}
}
