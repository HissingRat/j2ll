package xyz.melodysky.toolchain;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class HostJniJvmSemanticsSourcesTest {
    @Test
    void pendingExceptionHelpersPreserveThrowableLocalReferenceForCatchDispatchOrRethrow() {
        String source = HostJniJvmSemanticsSources.exceptionHelperSource();

        assertTrue(source.contains("jthrowable j2ll_rt_pending_exception(JNIEnv* env)"));
        assertTrue(source.contains("return (*env)->ExceptionOccurred(env);"));
        assertTrue(source.contains("void j2ll_rt_clear_exception(JNIEnv* env)"));
        assertTrue(source.contains("(*env)->ExceptionClear(env);"));
        assertTrue(source.contains("void j2ll_rt_rethrow(JNIEnv* env, jthrowable throwable)"));
        assertTrue(source.contains("if ((*env)->ExceptionCheck(env))"));
        assertTrue(source.contains("(*env)->Throw(env, throwable);"));
        assertFalse(source.contains("DeleteLocalRef(env, throwable)"));
    }
}
