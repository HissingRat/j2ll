package xyz.melodysky.analysis.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import xyz.melodysky.frontend.classfile.AsmClassParser;
import xyz.melodysky.frontend.classfile.ClassFileEntry;
import xyz.melodysky.frontend.classfile.ParsedProgram;
import xyz.melodysky.testsupport.AsmFixtureBuilder;

class RuntimeAnalysisPipelineTest {
    @Test
    void collectsNewAllocationSites() {
        ParsedProgram program = program(List.of(new ClassFileEntry(
                "pkg/Factory.class",
                AsmFixtureBuilder.classWithAllocation("pkg/Factory", "pkg/Product"),
                "fixture")));

        RuntimeTypeResult result = new RuntimeAnalysisPipeline().analyze(program);

        assertEquals(Set.of("pkg/Product"), result.instantiatedClasses());
        assertEquals(1, result.allocationSites().size());
    }

    @Test
    void referenceArrayAllocationTriggersConservativeRuntimeTypes() {
        ParsedProgram program = program(List.of(new ClassFileEntry(
                "pkg/Factory.class",
                AsmFixtureBuilder.classWithReferenceArrayAllocation("pkg/Factory", "pkg/Product"),
                "fixture")));

        RuntimeTypeResult result = new RuntimeAnalysisPipeline().analyze(program);

        assertTrue(result.conservative());
        assertTrue(result.allocationSites().get(0).unknown());
    }

    private ParsedProgram program(List<ClassFileEntry> entries) {
        AsmClassParser parser = new AsmClassParser();
        return new ParsedProgram(entries.stream()
                .map(entry -> parser.parse(entry).artifact().orElseThrow())
                .toList());
    }
}
