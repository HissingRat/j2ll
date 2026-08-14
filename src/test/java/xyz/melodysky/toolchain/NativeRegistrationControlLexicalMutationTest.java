package xyz.melodysky.toolchain;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

final class NativeRegistrationControlLexicalMutationTest {
    @Test
    void ignoresCompleteControlDeclarationsInsideCommentsAndStrings() {
        Fixture fixture = fixture();
        String ownerPrototype = ownerPrototype(
                fixture.plan().owners().get(0).symbol());
        String chunkPrototype = chunkPrototype(
                fixture.plan().chunks().get(0).symbol());
        String aggregatePrototype = aggregatePrototype(
                fixture.plan().aggregateSymbol());
        String failurePrototype = failurePrototype(
                fixture.plan().failureSymbols().ownerRollback());
        String decoys = "\n/* "
                + ownerPrototype
                + "\n"
                + chunkPrototype
                + "\n*/\n"
                + "static const char* registration_control_decoy = \""
                + cString(aggregatePrototype + " " + failurePrototype)
                + "\";\n";

        assertDoesNotThrow(() -> verify(
                fixture,
                fixture.source() + decoys));
    }

    @Test
    void commentAndStringPrototypeDecoysCannotReplaceCodeDeclarations() {
        Fixture fixture = fixture();
        for (String prototype : prototypes(fixture.plan())) {
            String without = replaceOnce(fixture.source(), prototype, "");

            assertRejected(
                    fixture,
                    without + "\n/* " + prototype + " */\n");
            assertRejected(
                    fixture,
                    without
                            + "\nstatic const char* registration_control_decoy = \""
                            + cString(prototype)
                            + "\";\n");
        }
    }

    @Test
    void everyControlDeclarationRequiresItsExactNoinlineAttributes() {
        Fixture fixture = fixture();
        for (String prototype : prototypes(fixture.plan())) {
            assertRejected(
                    fixture,
                    replaceOnce(
                            fixture.source(),
                            prototype,
                            prototype.replace("noinline", "inline")));
        }
        String failure = failurePrototype(
                fixture.plan().failureSymbols().ownerRollback());
        assertRejected(
                fixture,
                replaceOnce(
                        fixture.source(),
                        failure,
                        failure.replace(", cold", "")));
    }

    @Test
    void commentBodiesCannotReplaceDriftedCodeDefinitions() {
        Fixture fixture = fixture();
        for (FunctionSurface surface : surfaces(fixture)) {
            String drifted = surface.definition().replaceFirst(
                    java.util.regex.Pattern.quote(surface.symbol()),
                    "registration_control_drift");
            String source = replaceOnce(
                    fixture.source(),
                    surface.definition(),
                    drifted);

            assertRejected(
                    fixture,
                    source + "\n/*\n" + surface.definition() + "\n*/\n");
        }
    }

    @Test
    void inactivePreprocessorDeclarationsAndBodiesCannotSatisfyClosure() {
        Fixture fixture = fixture();
        for (String prototype : prototypes(fixture.plan())) {
            assertRejected(
                    fixture,
                    replaceOnce(fixture.source(), prototype, "")
                            + "\n#if 0\n"
                            + prototype
                            + "\n#endif\n");
        }
        for (FunctionSurface surface : surfaces(fixture)) {
            String drifted = surface.definition().replaceFirst(
                    java.util.regex.Pattern.quote(surface.symbol()),
                    "registration_control_drift");
            assertRejected(
                    fixture,
                    replaceOnce(
                            fixture.source(),
                            surface.definition(),
                            drifted)
                            + "\n#if 0\n"
                            + surface.definition()
                            + "\n#endif\n");
        }
    }

    @Test
    void digraphAndTrigraphInactiveBranchesCannotSatisfyClosure() {
        Fixture fixture = fixture();
        String prototype = aggregatePrototype(
                fixture.plan().aggregateSymbol());
        String without = replaceOnce(fixture.source(), prototype, "");

        for (String inactive : List.of("%:if 0", "??=if 0")) {
            assertRejected(
                    fixture,
                    without
                            + "\n"
                            + inactive
                            + "\n"
                            + prototype
                            + "\n#endif\n");
        }
    }

    @Test
    void rejectsUnclosedConditionalAndControlSymbolsOnDirectiveLines() {
        Fixture fixture = fixture();
        assertRejected(fixture, fixture.source() + "\n#if 1\n");

        String prototype = aggregatePrototype(
                fixture.plan().aggregateSymbol());
        assertRejected(
                fixture,
                replaceOnce(fixture.source(), prototype, "")
                        + "\n#define "
                        + fixture.plan().aggregateSymbol()
                        + " registration_control_directive\n");
    }

    private Fixture fixture() {
        HostNativeRegistrationSource.Emission emission =
                NativeRegistrationControlTestFixture.emission(
                        11,
                        "registration-control-lexical-mutations");
        return new Fixture(emission.source(), emission.topologyPlan());
    }

    private List<String> prototypes(
            NativeRegistrationControlTopologyPlan plan) {
        return List.of(
                ownerPrototype(plan.owners().get(0).symbol()),
                chunkPrototype(plan.chunks().get(0).symbol()),
                aggregatePrototype(plan.aggregateSymbol()),
                failurePrototype(
                        plan.failureSymbols().ownerRollback()));
    }

    private List<FunctionSurface> surfaces(Fixture fixture) {
        NativeRegistrationControlTopologyPlan plan = fixture.plan();
        String owner = plan.owners().get(0).symbol();
        String chunk = plan.chunks().get(0).symbol();
        String aggregate = plan.aggregateSymbol();
        String failure = plan.failureSymbols().ownerRollback();
        return List.of(
                new FunctionSurface(
                        owner,
                        NativeRegistrationControlTestFixture.function(
                                fixture.source(),
                                owner)),
                new FunctionSurface(
                        chunk,
                        NativeRegistrationControlTestFixture.function(
                                fixture.source(),
                                chunk)),
                new FunctionSurface(
                        aggregate,
                        NativeRegistrationControlTestFixture.function(
                                fixture.source(),
                                aggregate)),
                new FunctionSurface(
                        failure,
                        NativeRegistrationControlTestFixture.functionAtHeader(
                                fixture.source(),
                                "static void " + failure + "(JNIEnv* env) {")));
    }

    private String ownerPrototype(String symbol) {
        return "static jint "
                + symbol
                + "(JNIEnv* env, const j2ll_registration_resolver* resolver, jclass* registered_owner) __attribute__((noinline));";
    }

    private String chunkPrototype(String symbol) {
        return "static jint "
                + symbol
                + "(JNIEnv* env, const j2ll_registration_resolver* resolver, jclass* registered_owners, size_t* registered_count) __attribute__((noinline));";
    }

    private String aggregatePrototype(String symbol) {
        return "static jint "
                + symbol
                + "(JavaVM* vm) __attribute__((noinline));";
    }

    private String failurePrototype(String symbol) {
        return "static void "
                + symbol
                + "(JNIEnv* env) __attribute__((noinline, cold));";
    }

    private String replaceOnce(
            String source,
            String before,
            String after) {
        assertEquals(
                1,
                NativeRegistrationControlTestFixture.occurrences(
                        source,
                        before));
        return source.replace(before, after);
    }

    private String cString(String value) {
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n");
    }

    private void assertRejected(Fixture fixture, String source) {
        assertThrows(
                IllegalStateException.class,
                () -> verify(fixture, source));
    }

    private void verify(Fixture fixture, String source) {
        new NativeRegistrationControlSourceVerifier().verify(
                source,
                fixture.plan());
    }

    private record Fixture(
            String source,
            NativeRegistrationControlTopologyPlan plan) {}

    private record FunctionSurface(
            String symbol,
            String definition) {}
}
