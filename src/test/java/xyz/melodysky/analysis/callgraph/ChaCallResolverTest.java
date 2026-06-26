package xyz.melodysky.analysis.callgraph;

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
import xyz.melodysky.jvm.MethodSignature;
import xyz.melodysky.testsupport.AsmFixtureBuilder;

class ChaCallResolverTest implements Opcodes {
    private static final MethodSignature RUN = new MethodSignature("run", "()V");
    private static final MethodSignature CALLER = new MethodSignature("caller", "()V");

    @Test
    void finalReceiverProducesSingleTargetWithoutFallback() {
        ClassHierarchy hierarchy = hierarchy(List.of(
                objectEntry(),
                entry("pkg/FinalThing", AsmFixtureBuilder.classWithVoidMethod(
                        "pkg/FinalThing",
                        "java/lang/Object",
                        null,
                        ACC_PUBLIC | ACC_FINAL,
                        "run",
                        ACC_PUBLIC))));

        CallResolution resolution = new ChaCallResolver(hierarchy)
                .resolve(site(InvokeKind.VIRTUAL, "pkg/FinalThing", RUN));

        assertFalse(resolution.conservative());
        assertEquals(List.of("pkg/FinalThing#run!()V"), resolution.targets().stream().map(CallTarget::displayName).toList());
    }

    @Test
    void virtualCallCollectsOverrideTargetsAcrossSubtypes() {
        ClassHierarchy hierarchy = hierarchy(List.of(
                objectEntry(),
                entry("pkg/Base", AsmFixtureBuilder.classWithVoidMethod(
                        "pkg/Base",
                        "java/lang/Object",
                        null,
                        ACC_PUBLIC,
                        "run",
                        ACC_PUBLIC)),
                entry("pkg/Child", AsmFixtureBuilder.classWithVoidMethod(
                        "pkg/Child",
                        "pkg/Base",
                        null,
                        ACC_PUBLIC,
                        "run",
                        ACC_PUBLIC)),
                entry("pkg/Other", AsmFixtureBuilder.classHeader("pkg/Other", "pkg/Base", null, ACC_PUBLIC))));

        CallResolution resolution = new ChaCallResolver(hierarchy)
                .resolve(site(InvokeKind.VIRTUAL, "pkg/Base", RUN));

        assertFalse(resolution.conservative());
        assertEquals(List.of("pkg/Base#run!()V", "pkg/Child#run!()V"), resolution.targets().stream().map(CallTarget::displayName).toList());
    }

    @Test
    void interfaceCallCollectsMultipleImplementors() {
        ClassHierarchy hierarchy = hierarchy(List.of(
                objectEntry(),
                entry("pkg/Api", AsmFixtureBuilder.interfaceHeader("pkg/Api", null)),
                entry("pkg/ImplA", AsmFixtureBuilder.classWithVoidMethod(
                        "pkg/ImplA",
                        "java/lang/Object",
                        new String[] {"pkg/Api"},
                        ACC_PUBLIC,
                        "run",
                        ACC_PUBLIC)),
                entry("pkg/ImplB", AsmFixtureBuilder.classWithVoidMethod(
                        "pkg/ImplB",
                        "java/lang/Object",
                        new String[] {"pkg/Api"},
                        ACC_PUBLIC,
                        "run",
                        ACC_PUBLIC))));

        CallResolution resolution = new ChaCallResolver(hierarchy)
                .resolve(site(InvokeKind.INTERFACE, "pkg/Api", RUN));

        assertEquals(List.of("pkg/ImplA#run!()V", "pkg/ImplB#run!()V"), resolution.targets().stream().map(CallTarget::displayName).toList());
    }

    @Test
    void missingDeclaredOwnerRequiresConservativeUnknownTarget() {
        ClassHierarchy hierarchy = hierarchy(List.of(
                entry("pkg/Child", AsmFixtureBuilder.classHeader("pkg/Child", "missing/Parent", null, ACC_PUBLIC))));

        CallResolution resolution = new ChaCallResolver(hierarchy)
                .resolve(site(InvokeKind.VIRTUAL, "missing/Parent", RUN));

        assertTrue(resolution.conservative());
        assertTrue(resolution.hasUnknownTarget());
    }

    private CallSite site(InvokeKind kind, String owner, MethodSignature target) {
        return new CallSite("site", "pkg/Caller", CALLER, 0, kind, owner, target);
    }

    private ClassHierarchy hierarchy(List<ClassFileEntry> entries) {
        ParsedProgram program = program(entries);
        return new ClassHierarchyBuilder().build(program, AnalysisWorld.PARTIAL_WORLD).artifact().orElseThrow();
    }

    private ParsedProgram program(List<ClassFileEntry> entries) {
        AsmClassParser parser = new AsmClassParser();
        return new ParsedProgram(entries.stream()
                .map(entry -> parser.parse(entry).artifact().orElseThrow())
                .toList());
    }

    private ClassFileEntry objectEntry() {
        return entry("java/lang/Object", AsmFixtureBuilder.classHeader("java/lang/Object", null, null, ACC_PUBLIC));
    }

    private ClassFileEntry entry(String internalName, byte[] bytes) {
        return new ClassFileEntry(internalName + ".class", bytes, "fixture");
    }
}
