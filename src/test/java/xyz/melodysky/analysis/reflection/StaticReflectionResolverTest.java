package xyz.melodysky.analysis.reflection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import xyz.melodysky.analysis.callgraph.CallGraph;
import xyz.melodysky.analysis.callgraph.CallGraphBuilder;
import xyz.melodysky.analysis.hierarchy.AnalysisWorld;
import xyz.melodysky.analysis.hierarchy.ClassHierarchy;
import xyz.melodysky.analysis.hierarchy.ClassHierarchyBuilder;
import xyz.melodysky.frontend.classfile.AsmClassParser;
import xyz.melodysky.frontend.classfile.ClassFileEntry;
import xyz.melodysky.frontend.classfile.ParsedClass;
import xyz.melodysky.frontend.classfile.ParsedProgram;
import xyz.melodysky.runtime.metadata.RuntimeMetadataDumpWriter;
import xyz.melodysky.runtime.metadata.RuntimeMetadataIndex;
import xyz.melodysky.runtime.metadata.RuntimeMetadataIndexBuilder;
import xyz.melodysky.testsupport.AsmFixtureBuilder;

class StaticReflectionResolverTest {
    @Test
    void resolvesClassLiteralAndClassForNameConstants() {
        Fixture fixture = fixture();
        ReflectionPlan plan = new StaticReflectionResolver().resolve(fixture.program(), fixture.metadataIndex());

        assertTrue(plan.resolvedClasses().stream().anyMatch(target ->
                target.internalName().equals("sample/ReflectTarget")
                        && !target.requiresClassInitialization()
                        && target.sourceSite().contains("classLiteral")));
        assertTrue(plan.resolvedClasses().stream().anyMatch(target ->
                target.internalName().equals("sample/ReflectTarget")
                        && target.requiresClassInitialization()
                        && target.sourceSite().contains("forName")));
        assertTrue(plan.resolvedClasses().stream().anyMatch(target ->
                target.internalName().equals("sample/ReflectTarget")
                        && !target.requiresClassInitialization()
                        && target.sourceSite().contains("forNameNoInit")));
    }

    @Test
    void resolvesDeclaredMethodFieldConstructorAndReflectiveInvoke() {
        Fixture fixture = fixture();
        ReflectionPlan plan = new StaticReflectionResolver().resolve(fixture.program(), fixture.metadataIndex());

        assertTrue(plan.resolvedMethods().stream().anyMatch(target ->
                target.kind() == ReflectionMethodKind.DECLARED_METHOD
                        && target.methodKey().equals("sample/ReflectTarget#target!(Ljava/lang/String;)Ljava/lang/String;")));
        assertTrue(plan.resolvedFields().stream().anyMatch(target ->
                target.fieldKey().equals("sample/ReflectTarget#field!Ljava/lang/String;")));
        assertTrue(plan.resolvedMethods().stream().anyMatch(target ->
                target.kind() == ReflectionMethodKind.DECLARED_CONSTRUCTOR
                        && target.methodKey().equals("sample/ReflectTarget#<init>!(Ljava/lang/String;)V")));
        assertTrue(plan.resolvedMethods().stream().anyMatch(target ->
                target.kind() == ReflectionMethodKind.REFLECTIVE_INVOKE
                        && target.methodKey().equals("sample/ReflectTarget#invokeTarget!()Ljava/lang/String;")));
    }

    @Test
    void dynamicStringReflectionProducesFallbackPlan() {
        Fixture fixture = fixture();
        ReflectionPlan plan = new StaticReflectionResolver().resolve(fixture.program(), fixture.metadataIndex());

        assertTrue(plan.hasFallbacks());
        assertTrue(plan.fallbackSites().stream().anyMatch(fallback ->
                fallback.method().equals("dynamicForName")
                        && fallback.reasonCode().equals(StaticReflectionDiagnostics.DYNAMIC_REFLECTION_STRING)));
        assertFalse(plan.fallbackSites().stream().anyMatch(fallback -> fallback.reasonCode().isBlank()));
    }

    @Test
    void callGraphIncludesStaticallyResolvedReflectiveTarget() {
        Fixture fixture = fixture();
        ClassHierarchy hierarchy = new ClassHierarchyBuilder()
                .build(fixture.program(), AnalysisWorld.PARTIAL_WORLD)
                .artifact()
                .orElseThrow();

        CallGraph graph = new CallGraphBuilder().buildCha(fixture.program(), hierarchy, fixture.metadataIndex());

        assertTrue(graph.resolutions().stream()
                .flatMap(resolution -> resolution.targets().stream())
                .anyMatch(target -> target.displayName()
                        .equals("sample/ReflectTarget#invokeTarget!()Ljava/lang/String;")));
    }

    @Test
    void metadataDumpIncludesReflectionReachableMethods() {
        Fixture fixture = fixture();
        ReflectionPlan plan = new StaticReflectionResolver().resolve(fixture.program(), fixture.metadataIndex());

        String dump = new RuntimeMetadataDumpWriter().write(fixture.metadataIndex(), plan);

        assertTrue(dump.contains("\"reflectionReachability\""));
        assertTrue(dump.contains("\"method\": \"invokeTarget\""));
        assertTrue(dump.contains("\"kind\": \"REFLECTIVE_INVOKE\""));
        assertTrue(dump.contains("\"field\": \"field\""));
    }

    private Fixture fixture() {
        ParsedProgram program = parsedProgram(
                classEntry(
                        "sample/ReflectCaller",
                        AsmFixtureBuilder.classWithStaticReflectionMethods(
                                "sample/ReflectCaller",
                                "sample/ReflectTarget")),
                classEntry(
                        "sample/ReflectTarget",
                        AsmFixtureBuilder.classWithReflectionTarget("sample/ReflectTarget")));
        RuntimeMetadataIndex metadataIndex = new RuntimeMetadataIndexBuilder().build(program).artifact().orElseThrow();
        return new Fixture(program, metadataIndex);
    }

    private ParsedProgram parsedProgram(ClassFixture... fixtures) {
        AsmClassParser parser = new AsmClassParser();
        ArrayList<ParsedClass> classes = new ArrayList<>();
        for (ClassFixture fixture : fixtures) {
            classes.add(parser.parse(new ClassFileEntry(
                            fixture.internalName() + ".class",
                            fixture.bytes(),
                            "fixture"))
                    .artifact()
                    .orElseThrow());
        }
        return new ParsedProgram(classes);
    }

    private ClassFixture classEntry(String internalName, byte[] bytes) {
        return new ClassFixture(internalName, bytes);
    }

    private record ClassFixture(String internalName, byte[] bytes) {
    }

    private record Fixture(ParsedProgram program, RuntimeMetadataIndex metadataIndex) {
    }
}
