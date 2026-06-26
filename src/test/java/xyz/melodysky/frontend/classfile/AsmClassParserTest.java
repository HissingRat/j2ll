package xyz.melodysky.frontend.classfile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import xyz.melodysky.diagnostic.DiagnosticStage;
import xyz.melodysky.pipeline.StageResult;
import xyz.melodysky.testsupport.AsmFixtureBuilder;

class AsmClassParserTest {
    private final AsmClassParser parser = new AsmClassParser();

    @Test
    void parsesMinimalClassFacts() {
        ClassFileEntry entry = new ClassFileEntry(
                "pkg/Sample.class",
                AsmFixtureBuilder.classWithIntMethod("pkg/Sample", "answer", 42),
                "fixture");

        StageResult<ParsedClass> result = parser.parse(entry);

        assertTrue(result.artifact().isPresent());
        ParsedClass parsedClass = result.artifact().orElseThrow();
        assertEquals("pkg/Sample", parsedClass.internalName());
        assertEquals("java/lang/Object", parsedClass.superName());
        assertEquals("pkg/Sample.class", parsedClass.sourceEntry());
        assertTrue(parsedClass.accessFlags().isPublic());
        assertEquals(2, parsedClass.methods().size());
        assertTrue(parsedClass.methods().stream().anyMatch(method -> method.name().equals("answer") && method.hasCode()));
    }

    @Test
    void representsInterfaceNoCodeAndDefaultMethodsExplicitly() {
        ClassFileEntry entry = new ClassFileEntry(
                "pkg/Api.class",
                AsmFixtureBuilder.interfaceWithAbstractAndDefault("pkg/Api"),
                "fixture");

        ParsedClass parsedClass = parser.parse(entry).artifact().orElseThrow();

        assertTrue(parsedClass.isInterface());
        ParsedMethod abstractMethod = method(parsedClass, "call");
        ParsedMethod defaultMethod = method(parsedClass, "answer");
        assertFalse(abstractMethod.hasCode());
        assertTrue(defaultMethod.hasCode());
        assertTrue(abstractMethod.accessFlags().isAbstract());
    }

    @Test
    void parsesAnnotationAsInterfaceWithoutCodeBodies() {
        ClassFileEntry entry = new ClassFileEntry(
                "pkg/Marker.class",
                AsmFixtureBuilder.annotationClass("pkg/Marker"),
                "fixture");

        ParsedClass parsedClass = parser.parse(entry).artifact().orElseThrow();

        assertTrue(parsedClass.isAnnotation());
        assertTrue(parsedClass.isInterface());
        assertEquals(List.of("java/lang/annotation/Annotation"), parsedClass.interfaces());
        assertFalse(method(parsedClass, "value").hasCode());
    }

    @Test
    void invalidClassBytesProduceParseDiagnostic() {
        ClassFileEntry entry = new ClassFileEntry("bad.class", new byte[] {1, 2, 3}, "fixture");

        StageResult<ParsedClass> result = parser.parse(entry);

        assertTrue(result.artifact().isEmpty());
        assertTrue(result.hasErrors());
        assertEquals(DiagnosticStage.PARSE, result.diagnostics().get(0).stage());
        assertEquals(ClassParseDiagnostics.CLASS_PARSE_FAILED, result.diagnostics().get(0).code());
    }

    private ParsedMethod method(ParsedClass parsedClass, String name) {
        return parsedClass.methods().stream()
                .filter(method -> method.name().equals(name))
                .findFirst()
                .orElseThrow();
    }
}
