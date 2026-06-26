package xyz.melodysky.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import xyz.melodysky.frontend.classfile.AsmClassParser;
import xyz.melodysky.frontend.classfile.ClassFileEntry;
import xyz.melodysky.frontend.classfile.ParsedClass;
import xyz.melodysky.frontend.classfile.ParsedProgram;
import xyz.melodysky.pipeline.LoweringStatus;
import xyz.melodysky.testsupport.AsmFixtureBuilder;

class SelectorMatcherTest implements Opcodes {
    private final SelectorParser parser = new SelectorParser();
    private final SelectorMatcher matcher = new SelectorMatcher();

    @Test
    void expandsWhitelistMethodSelector() {
        ParsedProgram program = program(parsed("pkg/Mathy", AsmFixtureBuilder.classWithAddMethod("pkg/Mathy")));

        SelectorMatchResult result = matcher.expand(
                program,
                List.of(parser.parse("pkg/Mathy#add!(II)I")),
                List.of());

        assertTrue(result.diagnostics().isEmpty());
        assertEquals(1, result.requestedMethods().size());
        assertEquals("add", result.requestedMethods().get(0).name());
    }

    @Test
    void unmatchedWhitelistIsErrorAndUnmatchedBlacklistIsWarning() {
        ParsedProgram program = program(parsed("pkg/Mathy", AsmFixtureBuilder.classWithAddMethod("pkg/Mathy")));

        SelectorMatchResult result = matcher.expand(
                program,
                List.of(parser.parse("missing/**")),
                List.of(parser.parse("other/**")));

        assertEquals(ConfigDiagnostics.UNMATCHED_WHITELIST_SELECTOR, result.diagnostics().get(0).code());
        assertEquals(ConfigDiagnostics.UNMATCHED_BLACKLIST_SELECTOR, result.diagnostics().get(1).code());
    }

    @Test
    void blacklistWinsOverWhitelist() {
        ParsedProgram program = program(parsed("pkg/Mathy", AsmFixtureBuilder.classWithAddMethod("pkg/Mathy")));

        SelectorMatchResult result = matcher.expand(
                program,
                List.of(parser.parse("pkg/**")),
                List.of(parser.parse("pkg/Mathy#add!(II)I")));

        assertTrue(result.requestedMethods().stream().noneMatch(method -> method.name().equals("add")));
        assertEquals(1, result.excluded().size());
        assertEquals(LoweringStatus.EXCLUDED, result.excluded().get(0).status());
    }

    @Test
    void recordsNotApplicableForAbstractNativeAndNoCodeInterfaceMethods() {
        ParsedProgram program = program(
                parsed("pkg/Api", AsmFixtureBuilder.interfaceWithAbstractAndDefault("pkg/Api")),
                parsed("pkg/NativeApi", AsmFixtureBuilder.classWithVoidMethod(
                        "pkg/NativeApi",
                        "java/lang/Object",
                        null,
                        ACC_PUBLIC,
                        "call",
                        ACC_PUBLIC | ACC_NATIVE)));

        SelectorMatchResult result = matcher.expand(
                program,
                List.of(parser.parse("pkg/**")),
                List.of());

        assertEquals(1, result.requestedMethods().stream()
                .filter(method -> method.owner().equals("pkg/Api") && method.name().equals("answer"))
                .count());
        assertEquals(2, result.notApplicable().size());
        assertTrue(result.notApplicable().stream()
                .allMatch(eligibility -> eligibility.status() == LoweringStatus.NOT_APPLICABLE));
    }

    private ParsedProgram program(ParsedClass... classes) {
        return new ParsedProgram(List.of(classes));
    }

    private ParsedClass parsed(String internalName, byte[] bytes) {
        return new AsmClassParser()
                .parse(new ClassFileEntry(internalName + ".class", bytes, "fixture"))
                .artifact()
                .orElseThrow();
    }
}
