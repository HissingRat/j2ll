package xyz.melodysky.toolchain;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class HostJniRegistrationRuntimeSourceTest {
    @Test
    void registrationOwnerUsesJniOnLoadDefiningLoaderFindClassSemantics() {
        String source = HostJniRegistrationRuntimeSource.helperSource();
        String resolver = function(
                source,
                "static jclass j2ll_class_for_registration(");

        assertTrue(resolver.contains(
                "return (*env)->FindClass(env, internal_name);"));
        assertFalse(resolver.contains("j2ll_context_class_loader"));
        assertFalse(resolver.contains("Class.forName"));
        assertFalse(resolver.contains("CallStaticObjectMethod"));
        assertFalse(resolver.contains("NewStringUTF"));
        assertFalse(resolver.contains("JNI_FALSE"));
    }

    @Test
    void registrationRuntimeDoesNotCarryReflectionClassLoaderHelpers() {
        String source = HostJniRegistrationRuntimeSource.helperSource();

        assertFalse(source.contains("j2ll_context_class_loader"));
        assertFalse(source.contains("j2ll_dotted_class_name"));
        assertFalse(source.contains("java/lang/Thread"));
        assertFalse(source.contains("java/lang/ClassLoader"));
        assertTrue(source.contains("j2ll_throw_new"));
        assertTrue(source.contains("j2ll_class_for_registration"));
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
}
