package xyz.melodysky.frontend.classfile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import xyz.melodysky.pipeline.PipelineContext;
import xyz.melodysky.pipeline.StageValidation;
import xyz.melodysky.testsupport.AsmFixtureBuilder;

class ClassParseStageTest {
    @Test
    void parsesSourceIntoDeterministicallyOrderedProgram() {
        ClassFileSource source = new InMemoryClassFileSource(List.of(
                new ClassFileEntry("pkg/Zed.class", AsmFixtureBuilder.minimalClass("pkg/Zed"), "memory"),
                new ClassFileEntry("pkg/Alpha.class", AsmFixtureBuilder.minimalClass("pkg/Alpha"), "memory")));

        var result = new ClassParseStage().run(source, PipelineContext.bootstrap());

        assertFalse(result.hasErrors());
        assertEquals(
                List.of("pkg/Alpha", "pkg/Zed"),
                result.artifact().orElseThrow().program().classes().stream().map(ParsedClass::internalName).toList());
    }

    @Test
    void validatorReportsDuplicateMethodFacts() {
        ClassFileSource source = new InMemoryClassFileSource(List.of(
                new ClassFileEntry("pkg/Alpha.class", AsmFixtureBuilder.minimalClass("pkg/Alpha"), "memory"),
                new ClassFileEntry("pkg/AlphaCopy.class", AsmFixtureBuilder.minimalClass("pkg/Alpha"), "memory")));

        var parsed = new ClassParseStage().run(source, PipelineContext.bootstrap());
        var validated = StageValidation.validate(parsed, new ParsedProgramValidator());

        assertTrue(validated.hasErrors());
        assertEquals(ClassParseDiagnostics.DUPLICATE_CLASS, validated.diagnostics().get(0).code());
    }

    private record InMemoryClassFileSource(List<ClassFileEntry> entries) implements ClassFileSource {
        @Override
        public String description() {
            return "memory";
        }

        @Override
        public List<ClassFileEntry> entries() {
            return entries;
        }
    }
}
