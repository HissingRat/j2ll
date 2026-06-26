package xyz.melodysky.analysis.hierarchy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import xyz.melodysky.frontend.classfile.AsmClassParser;
import xyz.melodysky.frontend.classfile.ClassFileEntry;
import xyz.melodysky.frontend.classfile.ParsedProgram;
import xyz.melodysky.jvm.MethodSignature;
import xyz.melodysky.testsupport.AsmFixtureBuilder;

class ClassHierarchyBuilderTest implements Opcodes {
    private final ClassHierarchyBuilder builder = new ClassHierarchyBuilder();

    @Test
    void buildsSingleInheritanceSubtypes() {
        ClassHierarchy hierarchy = build(List.of(
                entry("pkg/Base", AsmFixtureBuilder.classHeader("pkg/Base", "java/lang/Object", null, ACC_PUBLIC)),
                entry("pkg/Child", AsmFixtureBuilder.classHeader("pkg/Child", "pkg/Base", null, ACC_PUBLIC))));

        assertEquals("pkg/Base", hierarchy.superClassOf("pkg/Child").orElseThrow());
        assertEquals(List.of("pkg/Child"), hierarchy.subtypesOf("pkg/Base"));
    }

    @Test
    void tracksTransitiveInterfaceImplementors() {
        ClassHierarchy hierarchy = build(List.of(
                entry("pkg/Api", AsmFixtureBuilder.interfaceHeader("pkg/Api", null)),
                entry("pkg/SubApi", AsmFixtureBuilder.interfaceHeader("pkg/SubApi", new String[] {"pkg/Api"})),
                entry("pkg/Impl", AsmFixtureBuilder.classHeader(
                        "pkg/Impl",
                        "java/lang/Object",
                        new String[] {"pkg/SubApi"},
                        ACC_PUBLIC))));

        assertEquals(List.of("pkg/Api"), hierarchy.interfacesOf("pkg/SubApi"));
        assertEquals(List.of("pkg/Impl"), hierarchy.implementorsOf("pkg/Api"));
    }

    @Test
    void resolvesVirtualMethodToNearestDeclaration() {
        MethodSignature run = new MethodSignature("run", "()V");
        ClassHierarchy hierarchy = build(List.of(
                entry("pkg/Base", AsmFixtureBuilder.classWithVoidMethod(
                        "pkg/Base",
                        "java/lang/Object",
                        null,
                        ACC_PUBLIC,
                        "run",
                        ACC_PUBLIC)),
                entry("pkg/Child", AsmFixtureBuilder.classHeader("pkg/Child", "pkg/Base", null, ACC_PUBLIC)),
                entry("pkg/OverrideChild", AsmFixtureBuilder.classWithVoidMethod(
                        "pkg/OverrideChild",
                        "pkg/Base",
                        null,
                        ACC_PUBLIC,
                        "run",
                        ACC_PUBLIC))));

        assertEquals("pkg/Base", hierarchy.resolveVirtualMethod("pkg/Child", run).orElseThrow().owner());
        assertEquals("pkg/OverrideChild", hierarchy.resolveVirtualMethod("pkg/OverrideChild", run).orElseThrow().owner());
    }

    @Test
    void reportsFinalClassAndFinalMethodFacts() {
        MethodSignature run = new MethodSignature("run", "()V");
        ClassHierarchy hierarchy = build(List.of(
                entry("pkg/FinalThing", AsmFixtureBuilder.classWithVoidMethod(
                        "pkg/FinalThing",
                        "java/lang/Object",
                        null,
                        ACC_PUBLIC | ACC_FINAL,
                        "run",
                        ACC_PUBLIC | ACC_FINAL))));

        assertTrue(hierarchy.isFinalClass("pkg/FinalThing"));
        assertTrue(hierarchy.isFinalMethod("pkg/FinalThing", run));
    }

    @Test
    void missingExternalParentCreatesPlaceholderAndConservativeWarning() {
        var result = builder.build(program(List.of(
                        entry("pkg/Child", AsmFixtureBuilder.classHeader(
                                "pkg/Child",
                                "missing/Parent",
                                null,
                                ACC_PUBLIC)))),
                AnalysisWorld.PARTIAL_WORLD);

        assertTrue(result.isConservative());
        ClassHierarchy hierarchy = result.artifact().orElseThrow();
        assertFalse(hierarchy.isComplete());
        assertTrue(hierarchy.lookupClass("missing/Parent").orElseThrow().external());
        assertTrue(result.diagnostics().stream()
                .anyMatch(diagnostic -> diagnostic.code().equals(HierarchyDiagnostics.MISSING_EXTERNAL_CLASS)));
    }

    @Test
    void hierarchyCycleIsDiagnosticError() {
        var result = builder.build(program(List.of(
                        entry("pkg/A", AsmFixtureBuilder.classHeader("pkg/A", "pkg/B", null, ACC_PUBLIC)),
                        entry("pkg/B", AsmFixtureBuilder.classHeader("pkg/B", "pkg/A", null, ACC_PUBLIC)))),
                AnalysisWorld.PARTIAL_WORLD);

        assertTrue(result.hasErrors());
        assertTrue(result.diagnostics().stream()
                .anyMatch(diagnostic -> diagnostic.code().equals(HierarchyDiagnostics.HIERARCHY_CYCLE)));
    }

    private ClassHierarchy build(List<ClassFileEntry> entries) {
        var result = builder.build(program(entries), AnalysisWorld.PARTIAL_WORLD);
        assertFalse(result.hasErrors());
        return result.artifact().orElseThrow();
    }

    private ParsedProgram program(List<ClassFileEntry> entries) {
        AsmClassParser parser = new AsmClassParser();
        return new ParsedProgram(entries.stream()
                .map(entry -> parser.parse(entry).artifact().orElseThrow())
                .toList());
    }

    private ClassFileEntry entry(String internalName, byte[] bytes) {
        return new ClassFileEntry(internalName + ".class", bytes, "fixture");
    }
}
