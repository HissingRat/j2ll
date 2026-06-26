package xyz.melodysky.frontend.cfg;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import xyz.melodysky.frontend.classfile.AsmClassParser;
import xyz.melodysky.frontend.classfile.ClassFileEntry;
import xyz.melodysky.frontend.classfile.ParsedClass;
import xyz.melodysky.frontend.classfile.ParsedMethod;
import xyz.melodysky.pipeline.StageValidation;
import xyz.melodysky.testsupport.AsmFixtureBuilder;

class MethodCfgBuilderTest {
    private final MethodCfgBuilder builder = new MethodCfgBuilder();

    @Test
    void buildsStraightLineCfg() {
        MethodCfgResult result = build(
                AsmFixtureBuilder.classWithIntMethod("pkg/Straight", "answer", 42),
                "answer");

        BytecodeCfg cfg = result.cfg().orElseThrow();
        assertEquals(1, cfg.blocks().size());
        assertEquals(0, cfg.edges().size());
        assertTrue(cfg.blocks().get(0).reachable());
        assertValidatorPasses(result);
    }

    @Test
    void buildsConditionalBranchEdges() {
        MethodCfgResult result = build(
                AsmFixtureBuilder.classWithConditionalMethod("pkg/Branch"),
                "choose");

        BytecodeCfg cfg = result.cfg().orElseThrow();
        assertEquals(3, cfg.blocks().size());
        assertEquals(2, cfg.edges().size());
        assertEquals(1, countEdges(cfg, BytecodeEdgeKind.BRANCH));
        assertEquals(1, countEdges(cfg, BytecodeEdgeKind.FALLTHROUGH));
        assertTrue(cfg.blocks().stream().allMatch(BytecodeBasicBlock::reachable));
        assertValidatorPasses(result);
    }

    @Test
    void preservesUnreachableBlockAfterGoto() {
        MethodCfgResult result = build(
                AsmFixtureBuilder.classWithGotoAndDeadCode("pkg/Dead"),
                "jump");

        BytecodeCfg cfg = result.cfg().orElseThrow();
        assertEquals(3, cfg.blocks().size());
        assertEquals(1, countEdges(cfg, BytecodeEdgeKind.BRANCH));
        assertFalse(cfg.blocks().get(1).reachable());
        assertValidatorPasses(result);
    }

    @Test
    void buildsSwitchEdges() {
        MethodCfgResult result = build(
                AsmFixtureBuilder.classWithTableSwitchMethod("pkg/Switchy"),
                "select");

        BytecodeCfg cfg = result.cfg().orElseThrow();
        assertEquals(4, cfg.blocks().size());
        assertEquals(3, countEdges(cfg, BytecodeEdgeKind.SWITCH));
        assertValidatorPasses(result);
    }

    @Test
    void buildsTryCatchExceptionEdges() {
        MethodCfgResult result = build(
                AsmFixtureBuilder.classWithTryCatchMethod("pkg/TryCatch"),
                "guarded");

        BytecodeCfg cfg = result.cfg().orElseThrow();
        assertEquals(1, cfg.exceptionRegions().size());
        assertEquals("java/lang/RuntimeException", cfg.exceptionRegions().get(0).catchType());
        assertTrue(cfg.blocks().stream().anyMatch(BytecodeBasicBlock::isExceptionHandler));
        assertEquals(1, countEdges(cfg, BytecodeEdgeKind.EXCEPTION));
        assertValidatorPasses(result);
    }

    @Test
    void noCodeMethodProducesExplicitNoCodeFact() {
        ParsedMethod method = parseMethod(
                AsmFixtureBuilder.interfaceWithAbstractAndDefault("pkg/Api"),
                "call");

        var result = builder.build(method);

        assertEquals(CfgMethodStatus.NO_CODE, result.artifact().orElseThrow().status());
        assertTrue(result.artifact().orElseThrow().cfg().isEmpty());
        assertEquals(CfgDiagnostics.METHOD_HAS_NO_CODE, result.diagnostics().get(0).code());
    }

    private MethodCfgResult build(byte[] classBytes, String methodName) {
        ParsedMethod method = parseMethod(classBytes, methodName);
        var result = builder.build(method);
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

    private long countEdges(BytecodeCfg cfg, BytecodeEdgeKind kind) {
        return cfg.edges().stream().filter(edge -> edge.kind() == kind).count();
    }

    private void assertValidatorPasses(MethodCfgResult result) {
        var validated = StageValidation.validate(
                xyz.melodysky.pipeline.StageResult.complete(xyz.melodysky.diagnostic.DiagnosticStage.CFG, result),
                new BytecodeCfgValidator());
        assertEquals(List.of(), validated.diagnostics());
    }
}
