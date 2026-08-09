package xyz.melodysky.toolchain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class HostJniRegistrationRuntimeSourceTest {
    @Test
    void resolverFindsOnlyTheLoaderAnchorAndCapturesItsExactDefiningLoader() {
        String source = HostJniRegistrationRuntimeSource.helperSource();
        String open = function(
                source,
                "static jint j2ll_registration_resolver_open(");

        assertEquals(1, occurrences(open, "(*env)->FindClass("));
        assertTrue(open.contains(
                "(*env)->FindClass(env, loader_internal_name)"));
        assertTrue(open.contains(
                "(*env)->GetObjectClass(env, resolver->loader_anchor)"));
        assertTrue(open.contains("\"getClassLoader\""));
        assertTrue(open.contains("\"()Ljava/lang/ClassLoader;\""));
        assertTrue(open.contains("(*env)->CallObjectMethod("));
        assertTrue(open.contains("resolver->loader_anchor"));
        assertTrue(open.contains("\"forName\""));
        assertTrue(open.contains(
                "\"(Ljava/lang/String;ZLjava/lang/ClassLoader;)Ljava/lang/Class;\""));
        assertFalse(open.contains("currentThread"));
        assertFalse(open.contains("getContextClassLoader"));
        assertFalse(open.contains("getSystemClassLoader"));
    }

    @Test
    void businessOwnersUseNonInitializingClassForNameWithTheCapturedLoader() {
        String source = HostJniRegistrationRuntimeSource.helperSource();
        String resolver = function(
                source,
                "static jclass j2ll_class_for_registration(");

        assertFalse(resolver.contains("FindClass"));
        assertTrue(resolver.contains("*cursor = '.';"));
        assertTrue(resolver.contains("(*env)->NewStringUTF(env, binary_name)"));
        assertTrue(resolver.contains("jvalue arguments[3] = {{0}};"));
        assertTrue(resolver.contains("arguments[0].l = name;"));
        assertTrue(resolver.contains("arguments[1].z = JNI_FALSE;"));
        assertTrue(resolver.contains(
                "arguments[2].l = resolver->defining_loader;"));
        assertTrue(resolver.contains("(*env)->CallStaticObjectMethodA("));
        assertFalse(resolver.contains("CallStaticObjectMethod("));
        assertFalse(resolver.contains("j2ll_context_class_loader"));
        assertFalse(resolver.contains("currentThread"));
        assertFalse(resolver.contains("getContextClassLoader"));
        assertFalse(resolver.contains("getSystemClassLoader"));
        assertTrue(resolver.contains("(*env)->DeleteLocalRef(env, name);"));
    }

    @Test
    void resolverContextReleasesEveryActivationLocalReference() {
        String close = function(
                HostJniRegistrationRuntimeSource.helperSource(),
                "static void j2ll_registration_resolver_close(");

        assertTrue(close.contains(
                "(*env)->DeleteLocalRef(env, resolver->defining_loader);"));
        assertTrue(close.contains(
                "(*env)->DeleteLocalRef(env, resolver->class_class);"));
        assertTrue(close.contains(
                "(*env)->DeleteLocalRef(env, resolver->loader_anchor);"));
        assertTrue(close.contains("resolver->defining_loader = NULL;"));
        assertTrue(close.contains("resolver->class_class = NULL;"));
        assertTrue(close.contains("resolver->loader_anchor = NULL;"));
        assertTrue(close.contains("resolver->class_for_name = NULL;"));
    }

    private String function(String source, String marker) {
        int start = source.indexOf(marker);
        int openingBrace = source.indexOf('{', start);
        int depth = 0;
        for (int index = openingBrace; index < source.length(); index++) {
            char current = source.charAt(index);
            if (current == '{') {
                depth++;
            } else if (current == '}' && --depth == 0) {
                return source.substring(start, index + 1);
            }
        }
        throw new AssertionError("generated C function is incomplete: " + marker);
    }

    private int occurrences(String source, String needle) {
        int count = 0;
        int offset = 0;
        while ((offset = source.indexOf(needle, offset)) >= 0) {
            count++;
            offset += needle.length();
        }
        return count;
    }
}
