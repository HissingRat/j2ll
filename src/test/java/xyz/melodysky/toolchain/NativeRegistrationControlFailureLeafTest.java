package xyz.melodysky.toolchain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

final class NativeRegistrationControlFailureLeafTest {
    @Test
    void eachFailureLeafIsOnePureHashOnlyColdFatalPath() {
        Fixture fixture = fixture();
        for (String symbol : fixture.plan().failureSymbols().symbols()) {
            String definition = definition(fixture.source(), symbol);
            assertTrue(symbol.matches("[a-p]{32}"));
            assertTrue(fixture.source().contains(prototype(symbol)));
            assertEquals(
                    1,
                    NativeRegistrationControlTestFixture.occurrences(
                            definition,
                            "(*env)->FatalError(env, message);"));
            assertEquals(
                    1,
                    NativeRegistrationControlTestFixture.occurrences(
                            definition,
                            "j2ll_native_text_zero(message, sizeof(message));"));
            for (String forbidden : List.of(
                    "RegisterNatives",
                    "UnregisterNatives",
                    "ExceptionCheck",
                    "ExceptionOccurred",
                    "ExceptionClear",
                    "j2ll_registration_resolver",
                    "registered_owners",
                    "registered_count")) {
                assertFalse(definition.contains(forbidden), forbidden);
            }
        }
    }

    @Test
    void rejectsMissingDuplicateOrReorderedFatalAndCleanupSteps() {
        Fixture fixture = fixture();
        String symbol = fixture.plan().failureSymbols().ownerRollback();
        assertDefinitionRejected(
                fixture,
                symbol,
                body -> replaceOnce(
                        body,
                        "    (*env)->FatalError(env, message);\n",
                        ""));
        assertDefinitionRejected(
                fixture,
                symbol,
                body -> replaceOnce(
                        body,
                        "    (*env)->FatalError(env, message);\n",
                        "    (*env)->FatalError(env, message);\n"
                                + "    (*env)->FatalError(env, message);\n"));
        assertDefinitionRejected(
                fixture,
                symbol,
                body -> replaceOnce(
                        body,
                        "    j2ll_native_text_zero(message, sizeof(message));\n",
                        ""));
        assertDefinitionRejected(
                fixture,
                symbol,
                body -> replaceOnce(
                        body,
                        "    (*env)->FatalError(env, message);\n"
                                + "    j2ll_native_text_zero(message, sizeof(message));\n",
                        "    j2ll_native_text_zero(message, sizeof(message));\n"
                                + "    (*env)->FatalError(env, message);\n"));
    }

    @Test
    void rejectsFailureLeafCallsToRegistrationControlOrJniStateMachines() {
        Fixture fixture = fixture();
        String symbol = fixture.plan().failureSymbols().ownerRollback();
        for (String injection : List.of(
                "    (void)"
                        + fixture.plan().aggregateSymbol()
                        + "(NULL);\n",
                "    (void)(*env)->ExceptionCheck(env);\n",
                "    (void)(*env)->UnregisterNatives(env, NULL);\n")) {
            assertDefinitionRejected(
                    fixture,
                    symbol,
                    body -> replaceOnce(
                            body,
                            "    (*env)->FatalError(env, message);\n",
                            injection
                                    + "    (*env)->FatalError(env, message);\n"));
        }
    }

    private Fixture fixture() {
        HostNativeRegistrationSource.Emission emission =
                NativeRegistrationControlTestFixture.emission(
                        11,
                        "registration-failure-leaf-closure");
        return new Fixture(emission.source(), emission.topologyPlan());
    }

    private void assertDefinitionRejected(
            Fixture fixture,
            String symbol,
            UnaryOperator<String> mutation) {
        String definition = definition(fixture.source(), symbol);
        assertThrows(
                IllegalStateException.class,
                () -> new NativeRegistrationControlSourceVerifier().verify(
                        replaceOnce(
                                fixture.source(),
                                definition,
                                mutation.apply(definition)),
                        fixture.plan()));
    }

    private String definition(String source, String symbol) {
        return NativeRegistrationControlTestFixture.functionAtHeader(
                source,
                "static void " + symbol + "(JNIEnv* env) {");
    }

    private String prototype(String symbol) {
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

    private record Fixture(
            String source,
            NativeRegistrationControlTopologyPlan plan) {}
}
