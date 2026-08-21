package xyz.melodysky.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import xyz.melodysky.analysis.hierarchy.AnalysisWorld;
import xyz.melodysky.analysis.hierarchy.ClassHierarchy;
import xyz.melodysky.analysis.hierarchy.ClassHierarchyBuilder;
import xyz.melodysky.frontend.classfile.AsmClassParser;
import xyz.melodysky.frontend.classfile.ClassFileEntry;
import xyz.melodysky.frontend.classfile.ParsedProgram;
import xyz.melodysky.runtime.metadata.RuntimeMetadataIndexBuilder;
import xyz.melodysky.testsupport.AsmFixtureBuilder;

class ProgramCallGraphAnalysisCoordinatorTest implements Opcodes {
    @Test
    void closedWorldRtaFeedsSingleTargetDevirtualization() {
        ParsedProgram program = program();
        ProgramCallGraphAnalysis analysis = analyze(
                program,
                AnalysisWorld.CLOSED_WORLD);

        var virtual = analysis.callGraph().resolutions().stream()
                .filter(resolution -> resolution.callSite().callerOwner()
                        .equals("pkg/Caller"))
                .findFirst()
                .orElseThrow();
        var decision = analysis.devirtualizationPlan()
                .decisionFor(virtual.callSite().id())
                .orElseThrow();

        assertTrue(analysis.rtaApplied());
        assertEquals(
                List.of("pkg/Child#run!()V"),
                virtual.targets().stream()
                        .map(target -> target.displayName())
                        .toList());
        assertEquals(
                "pkg/Child#run!()V",
                decision.directTarget().orElseThrow().displayName());
        assertFalse(decision.jvmDispatchRequired());
    }

    @Test
    void partialWorldKeepsChaDispatchConservative() {
        ParsedProgram program = program();
        ProgramCallGraphAnalysis analysis = analyze(
                program,
                AnalysisWorld.PARTIAL_WORLD);

        var virtual = analysis.callGraph().resolutions().stream()
                .filter(resolution -> resolution.callSite().callerOwner()
                        .equals("pkg/Caller"))
                .findFirst()
                .orElseThrow();
        var decision = analysis.devirtualizationPlan()
                .decisionFor(virtual.callSite().id())
                .orElseThrow();

        assertFalse(analysis.rtaApplied());
        assertTrue(virtual.targets().size() > 1 || virtual.hasUnknownTarget());
        assertTrue(decision.directTarget().isEmpty());
        assertTrue(decision.jvmDispatchRequired());
    }

    private ProgramCallGraphAnalysis analyze(
            ParsedProgram program,
            AnalysisWorld world) {
        ClassHierarchy hierarchy = new ClassHierarchyBuilder()
                .build(program, world)
                .artifact()
                .orElseThrow();
        var metadata = new RuntimeMetadataIndexBuilder()
                .build(program)
                .artifact()
                .orElseThrow();
        return new ProgramCallGraphAnalysisCoordinator().analyze(
                program,
                hierarchy,
                metadata,
                world);
    }

    private ParsedProgram program() {
        AsmClassParser parser = new AsmClassParser();
        return new ParsedProgram(List.of(
                entry(
                        "java/lang/Object",
                        AsmFixtureBuilder.classHeader(
                                "java/lang/Object",
                                null,
                                null,
                                ACC_PUBLIC)),
                entry("pkg/Base", methodClass("pkg/Base", "java/lang/Object")),
                entry("pkg/Child", methodClass("pkg/Child", "pkg/Base")),
                entry("pkg/Other", methodClass("pkg/Other", "pkg/Base")),
                entry(
                        "pkg/Caller",
                        AsmFixtureBuilder.classWithVirtualCall(
                                "pkg/Caller",
                                "pkg/Base")),
                entry(
                        "pkg/Allocator",
                        AsmFixtureBuilder.classWithAllocation(
                                "pkg/Allocator",
                                "pkg/Child")))
                .stream()
                .map(entry -> parser.parse(entry).artifact().orElseThrow())
                .toList());
    }

    private byte[] methodClass(String owner, String superName) {
        return AsmFixtureBuilder.classWithVoidMethod(
                owner,
                superName,
                null,
                ACC_PUBLIC,
                "run",
                ACC_PUBLIC);
    }

    private ClassFileEntry entry(String owner, byte[] bytes) {
        return new ClassFileEntry(owner + ".class", bytes, "fixture");
    }
}
