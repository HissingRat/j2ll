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

    private NativeRegistrationEntry entry(NativeRegistrationPlanner planner, ParsedClass parsedClass) {
        return entry(planner, parsedClass, null);
    }

    private NativeRegistrationEntry entry(
            NativeRegistrationPlanner planner,
            ParsedClass parsedClass,
            Long buildScopedSeed) {
        MethodRewriteDecision decision = new MethodRewritePlanner().planClass(parsedClass).stream()
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
