package xyz.melodysky.runtime.metadata;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import xyz.melodysky.frontend.classfile.AsmClassParser;
import xyz.melodysky.frontend.classfile.ClassFileEntry;
import xyz.melodysky.frontend.classfile.ParsedClass;
import xyz.melodysky.frontend.classfile.ParsedProgram;
import xyz.melodysky.pipeline.StageResult;
import xyz.melodysky.testsupport.AsmFixtureBuilder;

class RuntimeMetadataIndexBuilderTest {
    @Test
    void buildsMetadataForOrdinaryClass() {
        RuntimeMetadataIndex index = metadataIndex(classEntry(
                "sample/Ordinary",
                AsmFixtureBuilder.classWithMetadataOrdinary("sample/Ordinary")));

        ClassMetadata metadata = index.findClass("sample/Ordinary").orElseThrow();
        assertEquals("sample.Ordinary", metadata.binaryName());
        assertEquals(List.of("java/io/Serializable"), metadata.interfaces());
        assertEquals("java/lang/Object", metadata.superName());
        assertTrue(metadata.accessFlags().contains("public"));
        assertTrue(metadata.fields().stream().anyMatch(field ->
                field.name().equals("value") && field.descriptor().equals("I")));
        assertTrue(metadata.methods().stream().anyMatch(method ->
                method.name().equals("value") && method.descriptor().equals("()I") && method.hasCode()));
        assertFalse(metadata.classInitMetadata().hasClassInitializer());
        assertTrue(metadata.classInitMetadata().classObjectHandle().startsWith("j2ll_meta_class_object_sample_Ordinary_"));
    }

    @Test
    void preservesGenericSignatureMetadata() {
        RuntimeMetadataIndex index = metadataIndex(classEntry(
                "sample/Generic",
                AsmFixtureBuilder.classWithGenericSignature("sample/Generic")));

        ClassMetadata metadata = index.findClass("sample/Generic").orElseThrow();
        assertEquals("<T:Ljava/lang/Object;>Ljava/lang/Object;", metadata.signature().signature());
        assertEquals("TT;", metadata.fields().stream()
                .filter(field -> field.name().equals("value"))
                .findFirst()
                .orElseThrow()
                .signature()
                .signature());
        assertEquals("<T:Ljava/lang/Object;>(TT;)TT;", metadata.methods().stream()
                .filter(method -> method.name().equals("identity"))
                .findFirst()
                .orElseThrow()
                .signature()
                .signature());
    }

    @Test
    void preservesRuntimeVisibleAndInvisibleAnnotations() {
        RuntimeMetadataIndex index = metadataIndex(classEntry(
                "sample/Annotated",
                AsmFixtureBuilder.classWithRuntimeAnnotations("sample/Annotated")));

        ClassMetadata metadata = index.findClass("sample/Annotated").orElseThrow();
        AnnotationMetadata visible = metadata.annotations().stream()
                .filter(annotation -> annotation.descriptor().equals("Ltest/Visible;"))
                .findFirst()
                .orElseThrow();
        AnnotationMetadata invisible = metadata.annotations().stream()
                .filter(annotation -> annotation.descriptor().equals("Ltest/Invisible;"))
                .findFirst()
                .orElseThrow();
        assertTrue(visible.runtimeVisible());
        assertFalse(invisible.runtimeVisible());
        assertEquals("class", visible.values().get("value"));
        assertTrue(metadata.fields().stream()
                .flatMap(field -> field.annotations().stream())
                .anyMatch(annotation -> annotation.descriptor().equals("Ltest/FieldVisible;")
                        && annotation.runtimeVisible()));
        assertTrue(metadata.methods().stream()
                .flatMap(method -> method.annotations().stream())
                .anyMatch(annotation -> annotation.descriptor().equals("Ltest/MethodInvisible;")
                        && !annotation.runtimeVisible()));
    }

    @Test
    void preservesInnerAndNestMetadata() {
        RuntimeMetadataIndex index = metadataIndex(
                classEntry("sample/Outer$Inner", AsmFixtureBuilder.classWithInnerAndNestMember(
                        "sample/Outer$Inner",
                        "sample/Outer")),
                classEntry("sample/Outer", AsmFixtureBuilder.classWithInnerAndNestHost(
                        "sample/Outer",
                        "sample/Outer$Inner")));

        ClassMetadata outer = index.findClass("sample/Outer").orElseThrow();
        assertEquals(List.of("sample/Outer$Inner"), outer.nestMetadata().nestMembers());
        assertTrue(outer.innerClasses().stream().anyMatch(inner ->
                inner.name().equals("sample/Outer$Inner")
                        && inner.outerName().equals("sample/Outer")
                        && inner.innerName().equals("Inner")));

        ClassMetadata inner = index.findClass("sample/Outer$Inner").orElseThrow();
        assertEquals("sample/Outer", inner.nestMetadata().nestHost());
        assertEquals("sample/Outer", inner.nestMetadata().outerClass());
    }

    @Test
    void preservesRecordMetadataWhenPresent() {
        RuntimeMetadataIndex index = metadataIndex(classEntry(
                "sample/NameRecord",
                AsmFixtureBuilder.recordClassWithMetadata("sample/NameRecord")));

        ClassMetadata metadata = index.findClass("sample/NameRecord").orElseThrow();
        assertTrue(metadata.recordMetadata().recordClass());
        assertTrue(metadata.compilerFlags().contains("record"));
        assertEquals("name", metadata.recordMetadata().components().get(0).name());
        assertEquals("Ljava/lang/String;", metadata.recordMetadata().components().get(0).descriptor());
        assertTrue(metadata.methods().stream()
                .filter(method -> method.name().equals("name"))
                .findFirst()
                .orElseThrow()
                .compilerFlags()
                .contains("recordGenerated"));
    }

    @Test
    void preservesBridgeAndSyntheticFlags() {
        RuntimeMetadataIndex index = metadataIndex(classEntry(
                "sample/Bridge",
                AsmFixtureBuilder.classWithBridgeSyntheticMethod("sample/Bridge")));

        MethodMetadata bridge = index.findMethod(
                "sample/Bridge",
                "bridgeValue",
                "(Ljava/lang/Object;)Ljava/lang/Object;").orElseThrow();
        assertTrue(bridge.accessFlags().contains("bridge"));
        assertTrue(bridge.accessFlags().contains("synthetic"));
        assertTrue(bridge.compilerFlags().contains("bridge"));
        assertTrue(bridge.compilerFlags().contains("synthetic"));
    }

    @Test
    void metadataDumpIsDeterministicAndSorted() {
        RuntimeMetadataIndex index = metadataIndex(
                classEntry("sample/Zeta", AsmFixtureBuilder.minimalClass("sample/Zeta")),
                classEntry("sample/Alpha", AsmFixtureBuilder.minimalClass("sample/Alpha")));

        RuntimeMetadataDumpWriter writer = new RuntimeMetadataDumpWriter();
        String first = writer.write(index);
        String second = writer.write(index);

        assertEquals(first, second);
        assertTrue(first.indexOf("\"internalName\": \"sample/Alpha\"")
                < first.indexOf("\"internalName\": \"sample/Zeta\""));
        assertTrue(first.contains("\"classInit\""));
        assertTrue(first.contains("\"record\""));
    }

    private RuntimeMetadataIndex metadataIndex(ClassFixture... fixtures) {
        AsmClassParser parser = new AsmClassParser();
        ArrayList<ParsedClass> classes = new ArrayList<>();
        for (ClassFixture fixture : fixtures) {
            StageResult<ParsedClass> result = parser.parse(new ClassFileEntry(
                    fixture.internalName() + ".class",
                    fixture.bytes(),
                    "test-fixture"));
            assertTrue(result.isComplete(), () -> result.diagnostics().toString());
            classes.add(result.artifact().orElseThrow());
        }
        StageResult<RuntimeMetadataIndex> result = new RuntimeMetadataIndexBuilder().build(new ParsedProgram(classes));
        assertTrue(result.isComplete(), () -> result.diagnostics().toString());
        return result.artifact().orElseThrow();
    }

    private ClassFixture classEntry(String internalName, byte[] bytes) {
        return new ClassFixture(internalName, bytes);
    }

    private record ClassFixture(String internalName, byte[] bytes) {
    }
}
