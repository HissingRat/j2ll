package xyz.melodysky.runtime.jdk;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import xyz.melodysky.runtime.RuntimeHelperKind;

class JdkIntrinsicRegistryTest {
    @Test
    void looksUpFirstBatchJdkPolicies() {
        JdkIntrinsicRegistry registry = JdkIntrinsicRegistry.defaultRegistry();

        var objectInit = registry.lookup("java/lang/Object", "<init>", "()V").orElseThrow();
        assertEquals(JdkMethodPolicy.DIRECT_NATIVE_LOWERING, objectInit.policy());

        var stringLength = registry.lookup("java/lang/String", "length", "()I").orElseThrow();
        assertEquals(JdkMethodPolicy.RUNTIME_HELPER, stringLength.policy());
        assertEquals(RuntimeHelperKind.STRING_LENGTH, stringLength.helperKind().orElseThrow());

        var arraycopy = registry.lookup(
                        "java/lang/System",
                        "arraycopy",
                        "(Ljava/lang/Object;ILjava/lang/Object;II)V")
                .orElseThrow();
        assertEquals(RuntimeHelperKind.SYSTEM_ARRAYCOPY, arraycopy.helperKind().orElseThrow());

        var substring = registry.lookup("java/lang/String", "substring", "(II)Ljava/lang/String;").orElseThrow();
        assertEquals(JdkMethodPolicy.RUNTIME_HELPER, substring.policy());
        assertEquals(RuntimeHelperKind.STRING_SUBSTRING_RANGE, substring.helperKind().orElseThrow());

        var fallback = registry.lookup("java/lang/String", "substring", "(I)Ljava/lang/String;").orElseThrow();
        assertEquals(JdkMethodPolicy.JVM_HELPER_FALLBACK, fallback.policy());

        var startsWith = registry.lookup("java/lang/String", "startsWith", "(Ljava/lang/String;)Z").orElseThrow();
        assertEquals(RuntimeHelperKind.STRING_STARTS_WITH, startsWith.helperKind().orElseThrow());
    }
}
