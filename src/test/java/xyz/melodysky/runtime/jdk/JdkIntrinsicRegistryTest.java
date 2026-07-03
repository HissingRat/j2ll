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

        var arrayListAdd = registry.lookup("java/util/ArrayList", "add", "(Ljava/lang/Object;)Z").orElseThrow();
        assertEquals(JdkMethodPolicy.JVM_HELPER_FALLBACK, arrayListAdd.policy());
        assertEquals("JDK_COLLECTION_HELPER_FALLBACK: ArrayList.add uses JVM collection semantics", arrayListAdd.reason());

        var hashMapGet = registry.lookup("java/util/HashMap", "get", "(Ljava/lang/Object;)Ljava/lang/Object;").orElseThrow();
        assertEquals(JdkMethodPolicy.JVM_HELPER_FALLBACK, hashMapGet.policy());
        assertEquals("JDK_COLLECTION_HELPER_FALLBACK: HashMap.get uses JVM collection semantics", hashMapGet.reason());

        var arraysAsList = registry.lookup("java/util/Arrays", "asList", "([Ljava/lang/Object;)Ljava/util/List;").orElseThrow();
        assertEquals(JdkMethodPolicy.JVM_HELPER_FALLBACK, arraysAsList.policy());
        assertEquals("JDK_COLLECTION_HELPER_FALLBACK: Arrays.asList uses JVM list semantics", arraysAsList.reason());

        var emptyList = registry.lookup("java/util/Collections", "emptyList", "()Ljava/util/List;").orElseThrow();
        assertEquals(JdkMethodPolicy.JVM_HELPER_FALLBACK, emptyList.policy());
        assertEquals("JDK_COLLECTION_HELPER_FALLBACK: Collections.emptyList uses JVM collection semantics", emptyList.reason());

        var optionalOrElse = registry.lookup("java/util/Optional", "orElse", "(Ljava/lang/Object;)Ljava/lang/Object;").orElseThrow();
        assertEquals(JdkMethodPolicy.JVM_HELPER_FALLBACK, optionalOrElse.policy());
        assertEquals("JDK_OPTIONAL_HELPER_FALLBACK: Optional.orElse uses JVM Optional semantics", optionalOrElse.reason());

        var stringFormat = registry.lookup("java/lang/String", "format", "(Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/String;").orElseThrow();
        assertEquals(JdkMethodPolicy.JVM_HELPER_FALLBACK, stringFormat.policy());
        assertEquals("JDK_FORMAT_HELPER_FALLBACK: String.format uses JVM formatter semantics", stringFormat.reason());

        var runtimeMessage = registry.lookup("java/lang/RuntimeException", "<init>", "(Ljava/lang/String;)V").orElseThrow();
        assertEquals(JdkMethodPolicy.JVM_HELPER_BRIDGE, runtimeMessage.policy());
        assertEquals("JDK_BRIDGE: RuntimeException(String) keeps JVM Throwable semantics", runtimeMessage.reason());

        var getCause = registry.lookup("java/lang/Throwable", "getCause", "()Ljava/lang/Throwable;").orElseThrow();
        assertEquals(JdkMethodPolicy.JVM_HELPER_FALLBACK, getCause.policy());
        assertEquals("THROWABLE_HELPER_FALLBACK: Throwable.getCause keeps JVM cause semantics", getCause.reason());

        var threadStart = registry.lookup("java/lang/Thread", "start", "()V").orElseThrow();
        assertEquals(JdkMethodPolicy.JVM_HELPER_FALLBACK, threadStart.policy());
        assertEquals("THREAD_HELPER_FALLBACK: Thread.start keeps JVM scheduler semantics", threadStart.reason());

        var objectWait = registry.lookup("java/lang/Object", "wait", "()V").orElseThrow();
        assertEquals(JdkMethodPolicy.JVM_HELPER_FALLBACK, objectWait.policy());
        assertEquals("WAIT_NOTIFY_FALLBACK: Object.wait keeps JVM monitor queue semantics", objectWait.reason());
    }
}
