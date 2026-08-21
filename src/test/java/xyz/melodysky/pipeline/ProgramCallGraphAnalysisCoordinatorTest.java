package xyz.melodysky.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
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
                .filter(resolution -> resolution.callSite().declaredTarget().name()
                        .equals("run"))
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
        assertEquals(
                java.util.Set.of("pkg/Child"),
                analysis.runtimeTypes().instantiatedClasses());
        assertTrue(analysis.reachability().reachableMethodKeys()
                .contains("pkg/Caller#call!()V"));
        assertTrue(analysis.reachability().unreachableMethodKeys()
                .contains("pkg/Allocator#make!()V"));
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
                .filter(resolution -> resolution.callSite().declaredTarget().name()
                        .equals("run"))
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
                world,
                program.classes().stream()
                        .filter(parsedClass -> parsedClass.internalName().equals("pkg/Caller"))
                        .flatMap(parsedClass -> parsedClass.methods().stream())
                        .filter(method -> method.hasCode())
                        .toList());
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
                        callerAllocatingChild()),
                entry(
                        "pkg/Allocator",
                        AsmFixtureBuilder.classWithAllocation(
                                "pkg/Allocator",
                                "pkg/Other")))
                .stream()
                .map(entry -> parser.parse(entry).artifact().orElseThrow())
                .toList());
    }

    private byte[] callerAllocatingChild() {
        ClassWriter writer = new ClassWriter(
                ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(V17, ACC_PUBLIC | ACC_SUPER, "pkg/Caller", null, "java/lang/Object", null);
        MethodVisitor method = writer.visitMethod(
                ACC_PUBLIC | ACC_STATIC,
                "call",
                "()V",
                null,
                null);
        method.visitCode();
        method.visitTypeInsn(NEW, "pkg/Child");
        method.visitInsn(DUP);
        method.visitMethodInsn(INVOKESPECIAL, "pkg/Child", "<init>", "()V", false);
        method.visitMethodInsn(INVOKEVIRTUAL, "pkg/Base", "run", "()V", false);
        method.visitInsn(RETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
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
