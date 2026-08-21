package xyz.melodysky.packaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import xyz.melodysky.frontend.classfile.AsmClassParser;
import xyz.melodysky.frontend.classfile.ClassFileEntry;
import xyz.melodysky.frontend.classfile.ParsedClass;
import xyz.melodysky.testsupport.AsmFixtureBuilder;
import xyz.melodysky.testsupport.InterfaceMethodAsmFixtures;

class NativeRegistrationPlannerTest {
    @Test
    void nativeWrapperSymbolsAreDeterministicHashOnlyIdentifiers() {
        NativeRegistrationPlanner planner = new NativeRegistrationPlanner();
        NativeRegistrationEntry first = entry(planner, parsed("sensitive/acme/PlainOwner", "secretMethod"));
        NativeRegistrationEntry repeated = entry(planner, parsed("sensitive/acme/PlainOwner", "secretMethod"));
        NativeRegistrationEntry distinct = entry(planner, parsed("sensitive/acme/PlainOwner", "otherMethod"));

        assertTrue(first.nativeSymbol().matches("j2ll_n_[0-9a-f]{32}"));
        assertFalse(first.nativeSymbol().contains("PlainOwner"));
        assertFalse(first.nativeSymbol().contains("secretMethod"));
        assertEquals(first.nativeSymbol(), repeated.nativeSymbol());
        assertNotEquals(first.nativeSymbol(), distinct.nativeSymbol());
    }

    @Test
    void buildScopedSeedKeepsSymbolsStableWithinABuildAndVariesAcrossBuilds() {
        NativeRegistrationPlanner planner = new NativeRegistrationPlanner();
        ParsedClass parsedClass = parsed("sensitive/acme/PlainOwner", "secretMethod");

        NativeRegistrationEntry first = entry(planner, parsedClass, 0x1122334455667788L);
        NativeRegistrationEntry repeated = entry(planner, parsedClass, 0x1122334455667788L);
        NativeRegistrationEntry nextBuild = entry(planner, parsedClass, 0x8877665544332211L);
        NativeRegistrationEntry legacy = entry(planner, parsedClass);

        assertTrue(first.nativeSymbol().matches("j2ll_n_[0-9a-f]{32}"));
        assertFalse(first.nativeSymbol().contains("PlainOwner"));
        assertFalse(first.nativeSymbol().contains("secretMethod"));
        assertEquals(first.nativeSymbol(), repeated.nativeSymbol());
        assertNotEquals(first.nativeSymbol(), nextBuild.nativeSymbol());
        assertNotEquals(first.nativeSymbol(), legacy.nativeSymbol());
    }

    @Test
    void defaultInterfaceRegistrationMakesTheReceiverExplicit() {
        ParsedClass parsedClass = new AsmClassParser()
                .parse(new ClassFileEntry(
                        "pkg/Api.class",
                        AsmFixtureBuilder.interfaceWithAbstractAndDefault("pkg/Api"),
                        "fixture"))
                .artifact()
                .orElseThrow();
        MethodRewriteDecision decision = new MethodRewritePlanner().planClass(parsedClass, 0x6a326c6cL).stream()
                .filter(candidate -> candidate.method().name().equals("answer"))
                .findFirst()
                .orElseThrow();

        NativeRegistrationEntry entry = new NativeRegistrationPlanner()
                .plan(List.of(decision))
                .entries()
                .get(0);

        assertEquals("(Lpkg/Api;)I", entry.descriptor());
        assertEquals(decision.registrationOwner(), entry.registrationOwner());
    }

    @Test
    void interfaceStaticAndPrivateDescriptorsMatchTheirStubAbi() {
        ParsedClass parsedClass = new AsmClassParser()
                .parse(new ClassFileEntry(
                        "pkg/CodeApi.class",
                        InterfaceMethodAsmFixtures.interfaceWithDefaultStaticAndPrivate(
                                "pkg/CodeApi"),
                        "fixture"))
                .artifact()
                .orElseThrow();
        var decisions = new MethodRewritePlanner().planClass(parsedClass, 0x6a326c6cL);
        var entries = new NativeRegistrationPlanner().plan(decisions).entries();

        assertEquals(
                "()I",
                entryFor(decisions, entries, "staticAnswer").descriptor());
        assertEquals(
                "(Lpkg/CodeApi;)I",
                entryFor(decisions, entries, "privateAnswer").descriptor());
    }

    private NativeRegistrationEntry entryFor(
            List<MethodRewriteDecision> decisions,
            List<NativeRegistrationEntry> entries,
            String sourceName) {
        MethodRewriteDecision decision = decisions.stream()
                .filter(candidate -> candidate.method().name().equals(sourceName))
                .findFirst()
                .orElseThrow();
        return entries.stream()
                .filter(entry -> entry.methodName().equals(
                        decision.generatedHelperName().orElseThrow()))
                .findFirst()
                .orElseThrow();
    }

    private NativeRegistrationEntry entry(NativeRegistrationPlanner planner, ParsedClass parsedClass) {
        return entry(planner, parsedClass, null);
    }

    private NativeRegistrationEntry entry(
            NativeRegistrationPlanner planner,
            ParsedClass parsedClass,
            Long buildScopedSeed) {
        MethodRewriteDecision decision = new MethodRewritePlanner().planClass(parsedClass, 0x6a326c6cL).stream()
                .filter(candidate -> !candidate.method().name().equals("<init>"))
                .findFirst()
                .orElseThrow();
        NativeRegistrationPlan plan = buildScopedSeed == null
                ? planner.plan(List.of(decision))
                : planner.plan(List.of(decision), buildScopedSeed);
        return plan.entries().get(0);
    }

    private ParsedClass parsed(String owner, String methodName) {
        return new AsmClassParser()
                .parse(new ClassFileEntry(
                        owner + ".class",
                        AsmFixtureBuilder.classWithIntMethod(owner, methodName, 7),
                        "fixture"))
                .artifact()
                .orElseThrow();
    }
}
