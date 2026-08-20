package xyz.melodysky.toolchain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

final class NativeRegistrationControlTranslationPhaseMutationTest {
    @Test
    void continuedInactiveDirectivesCannotSatisfyPrototypeOrBodyClosure() {
        Fixture fixture = fixture();
        String prototype = aggregatePrototype(fixture);
        String definition = aggregateDefinition(fixture);
        String splitIf = "#i\\" + "\n" + "f 0";
        String splitEndif = "#e\\" + "\n" + "ndif";

        assertRejected(
                fixture,
                replaceOnce(fixture.source(), prototype, "")
                        + "\n"
                        + splitIf
                        + "\n"
                        + prototype
                        + "\n"
                        + splitEndif
                        + "\n");
        assertRejected(
                fixture,
                replaceOnce(fixture.source(), definition, "")
                        + "\n"
                        + splitIf
                        + "\n"
                        + definition
                        + "\n"
                        + splitEndif
                        + "\n");
    }

    @Test
    void phaseTwoSplicingCannotTurnAcceptedControlEvidenceIntoComments() {
        Fixture fixture = fixture();
        String prototype = aggregatePrototype(fixture);
        String definition = aggregateDefinition(fixture);
        String spliceToLineComment = "/\\" + "\n" + "/ ";

        assertRejected(
                fixture,
                replaceOnce(
                        fixture.source(),
                        prototype,
                        spliceToLineComment + prototype));
        assertRejected(
                fixture,
                replaceOnce(
                        fixture.source(),
                        definition,
                        spliceToLineComment + definition));

        String trigraphSplice = "/??/" + "\n" + "/ ";
        assertRejected(
                fixture,
                replaceOnce(
                        fixture.source(),
                        prototype,
                        trigraphSplice + prototype));

        String crlfSplice = "/\\" + "\r\n" + "/ ";
        assertRejected(
                fixture,
                replaceOnce(
                        fixture.source(),
                        prototype,
                        crlfSplice + prototype));
    }

    @Test
    void crlfContinuedInactiveDirectiveCannotSatisfyClosure() {
        Fixture fixture = fixture();
        String prototype = aggregatePrototype(fixture);
        String splitIf = "#i\\" + "\r\n" + "f 0";
        String splitEndif = "#e\\" + "\r\n" + "ndif";

        assertRejected(
                fixture,
                replaceOnce(fixture.source(), prototype, "")
                        + "\r\n"
                        + splitIf
                        + "\r\n"
                        + prototype
                        + "\r\n"
                        + splitEndif
                        + "\r\n");
    }

    @Test
    void everyRawTrigraphSurfaceFailsClosed() {
        Fixture fixture = fixture();
        for (String trigraph : java.util.List.of(
                "??=",
                "??/",
                "??'",
                "??(",
                "??)",
                "??!",
                "??<",
                "??>",
                "??-")) {
            assertRejected(
                    fixture,
                    fixture.source() + "\n" + trigraph + "\n");
        }
    }

    private Fixture fixture() {
        HostNativeRegistrationSource.Emission emission =
                NativeRegistrationControlTestFixture.emission(
                        11,
                        "registration-control-translation-phases");
        return new Fixture(emission.source(), emission.topologyPlan());
    }

    private String aggregatePrototype(Fixture fixture) {
        return NativeRegistrationControlCFunctionPolicy.prototype(
                "static jint "
                        + fixture.plan().aggregateSymbol()
                        + "(JavaVM* vm)");
    }

    private String aggregateDefinition(Fixture fixture) {
        return NativeRegistrationControlTestFixture.function(
                fixture.source(),
                fixture.plan().aggregateSymbol());
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

    private void assertRejected(Fixture fixture, String source) {
        assertThrows(
                IllegalStateException.class,
                () -> new NativeRegistrationControlSourceVerifier().verify(
                        source,
                        fixture.plan()));
    }

    private record Fixture(
            String source,
            NativeRegistrationControlTopologyPlan plan) {}
}
