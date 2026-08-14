package xyz.melodysky.toolchain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

final class NativeRegistrationControlAggregateMutationTest {
    @Test
    void rejectsRollbackEscapingItsLoopAndMissingStatusCheck() {
        Fixture fixture = fixture();
        assertAggregateRejected(
                fixture,
                aggregate -> replaceOnce(
                        aggregate,
                        "        unregister_status = (*env)->UnregisterNatives(env, rollback_owner);\n",
                        "    }\n"
                                + "    unregister_status = (*env)->UnregisterNatives(env, rollback_owner);\n"));
        assertAggregateRejected(
                fixture,
                aggregate -> replaceOnce(
                        aggregate,
                        "        if (unregister_status != JNI_OK) {\n"
                                + "            rollback_failed = JNI_TRUE;\n"
                                + "        }\n",
                        ""));
    }

    @Test
    void rejectsMissingExceptionCaptureAndClearSteps() {
        Fixture fixture = fixture();
        String captureBlock = "rollback:\n"
                + "    if ((*env)->ExceptionCheck(env)) {\n"
                + "        failure_exception = (*env)->ExceptionOccurred(env);\n"
                + "        (*env)->ExceptionClear(env);\n"
                + "    }\n"
                + "    while (registered_count != 0u) {\n";
        for (String statement : List.of(
                "    if ((*env)->ExceptionCheck(env)) {\n",
                "        failure_exception = (*env)->ExceptionOccurred(env);\n",
                "        (*env)->ExceptionClear(env);\n")) {
            assertAggregateRejected(
                    fixture,
                    aggregate -> replaceOnce(
                            aggregate,
                            captureBlock,
                            captureBlock.replace(statement, "")));
        }
    }

    @Test
    void rejectsDestroyingOriginalThrowableBeforeRestoreOrThrowingRollbackThrowable() {
        Fixture fixture = fixture();
        assertAggregateRejected(
                fixture,
                aggregate -> replaceOnce(
                        aggregate,
                        "        throw_status = (*env)->Throw(env, failure_exception);\n"
                                + "        (*env)->DeleteLocalRef(env, failure_exception);\n"
                                + "        failure_exception = NULL;\n",
                        "        (*env)->DeleteLocalRef(env, failure_exception);\n"
                                + "        failure_exception = NULL;\n"
                                + "        throw_status = (*env)->Throw(env, failure_exception);\n"));
        assertAggregateRejected(
                fixture,
                aggregate -> replaceOnce(
                        aggregate,
                        "        throw_status = (*env)->Throw(env, failure_exception);\n",
                        "        (void)(*env)->Throw(env, rollback_exception);\n"
                                + "        throw_status = (*env)->Throw(env, failure_exception);\n"));
    }

    @Test
    void rejectsDuplicateRollbackGetEnvRegisterNativesAndDirectTopologyBypass() {
        Fixture fixture = fixture();
        NativeRegistrationControlTopologyPlan plan = fixture.plan();
        assertAggregateRejected(
                fixture,
                aggregate -> replaceOnce(
                        aggregate,
                        "rollback:\n",
                        "rollback:\nrollback:\n"));
        assertAggregateRejected(
                fixture,
                aggregate -> replaceOnce(
                        aggregate,
                        "    JNIEnv* env = NULL;\n",
                        "    JNIEnv* env = NULL;\n"
                                + "    (void)(*vm)->GetEnv(vm, (void**)&env, JNI_VERSION_1_8);\n"));
        assertAggregateRejected(
                fixture,
                aggregate -> replaceOnce(
                        aggregate,
                        "    size_t registered_count = 0u;\n",
                        "    size_t registered_count = 0u;\n"
                                + "    (void)(*env)->RegisterNatives(env, NULL, NULL, 0);\n"));
        assertAggregateRejected(
                fixture,
                aggregate -> replaceOnce(
                        aggregate,
                        "    size_t registered_count = 0u;\n",
                        "    size_t registered_count = 0u;\n"
                                + "    (void)"
                                + plan.owners().get(0).symbol()
                                + "(env, &resolver, &registered_owners[0]);\n"));
        assertAggregateRejected(
                fixture,
                aggregate -> replaceOnce(
                        aggregate,
                        "    size_t registered_count = 0u;\n",
                        "    size_t registered_count = 0u;\n"
                                + "    (void)"
                                + plan.chunks().get(1).symbol()
                                + "(env, &resolver, registered_owners, &registered_count);\n"));
    }

    @Test
    void rejectsEveryEarlyReturnOrRollbackJumpBeforeTheExactTail() {
        Fixture fixture = fixture();
        String tail = "    jthrowable failure_exception = NULL;\n";
        for (String injection : List.of(
                "    return JNI_VERSION_1_8;\n",
                "    goto rollback;\n",
                "    return JNI_ERR;\n",
                "    return 7;\n")) {
            assertAggregateRejected(
                    fixture,
                    aggregate -> replaceOnce(
                            aggregate,
                            tail,
                            injection + tail));
        }
    }

    private Fixture fixture() {
        HostNativeRegistrationSource.Emission emission =
                NativeRegistrationControlTestFixture.emission(
                        11,
                        "registration-aggregate-mutations");
        return new Fixture(emission.source(), emission.topologyPlan());
    }

    private void assertAggregateRejected(
            Fixture fixture,
            UnaryOperator<String> mutation) {
        String definition = NativeRegistrationControlTestFixture.function(
                fixture.source(),
                fixture.plan().aggregateSymbol());
        String mutated = mutation.apply(definition);
        assertThrows(
                IllegalStateException.class,
                () -> new NativeRegistrationControlSourceVerifier().verify(
                        replaceOnce(
                                fixture.source(),
                                definition,
                                mutated),
                        fixture.plan()));
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
