package xyz.melodysky.analysis.hierarchy;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import xyz.melodysky.frontend.classfile.AsmClassParser;
import xyz.melodysky.frontend.classfile.ClassFileEntry;
import xyz.melodysky.frontend.classfile.ParsedProgram;
import xyz.melodysky.testsupport.AsmFixtureBuilder;

class DefaultInterfaceAnalyzerTest implements Opcodes {
    private final DefaultInterfaceAnalyzer analyzer = new DefaultInterfaceAnalyzer();

    @Test
    void recordsDefaultMethodsAndAmbiguousInheritedSignatures() {
        ParsedProgram program = program(
                entry("pkg/Left", AsmFixtureBuilder.interfaceWithAbstractAndDefault("pkg/Left")),
                entry("pkg/Right", AsmFixtureBuilder.interfaceWithAbstractAndDefault("pkg/Right")),
                entry(
                        "pkg/Ambiguous",
                        AsmFixtureBuilder.classHeader(
                                "pkg/Ambiguous",
                                "java/lang/Object",
                                new String[] {"pkg/Left", "pkg/Right"},
                                ACC_PUBLIC | ACC_SUPER)));

        DefaultInterfaceAnalysis analysis = analyzer.analyze(program);

        assertEquals(
                Set.of("pkg/Left#answer!()I", "pkg/Right#answer!()I"),
                analysis.methodKeys());
        assertEquals(Set.of("answer!()I"), analysis.conflictSignatures());
    }

    @Test
    void excludesAbstractInterfaceDeclarationsFromDefaultMethodFacts() {
        ParsedProgram program = program(entry(
                "pkg/Api",
                AsmFixtureBuilder.interfaceWithAbstractAndDefault("pkg/Api")));

        DefaultInterfaceAnalysis analysis = analyzer.analyze(program);

        assertEquals(Set.of("pkg/Api#answer!()I"), analysis.methodKeys());
        assertEquals(Set.of(), analysis.conflictSignatures());
    }

    private ParsedProgram program(ClassFileEntry... entries) {
        AsmClassParser parser = new AsmClassParser();
        return new ParsedProgram(List.of(entries).stream()
                .map(entry -> parser.parse(entry).artifact().orElseThrow())
                .toList());
    }

    private ClassFileEntry entry(String internalName, byte[] bytes) {
        return new ClassFileEntry(internalName + ".class", bytes, "fixture");
    }
}
