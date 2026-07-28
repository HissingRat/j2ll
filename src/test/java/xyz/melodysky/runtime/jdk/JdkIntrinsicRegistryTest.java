package xyz.melodysky.runtime.jdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
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

        var unsupported = registry.lookup("java/lang/String", "substring", "(I)Ljava/lang/String;").orElseThrow();
        assertEquals(JdkMethodPolicy.JVM_HELPER_UNSUPPORTED, unsupported.policy());

        var startsWith = registry.lookup("java/lang/String", "startsWith", "(Ljava/lang/String;)Z").orElseThrow();
        assertEquals(RuntimeHelperKind.STRING_STARTS_WITH, startsWith.helperKind().orElseThrow());

        var arrayListAdd = registry.lookup("java/util/ArrayList", "add", "(Ljava/lang/Object;)Z").orElseThrow();
        assertEquals(JdkMethodPolicy.JVM_HELPER_UNSUPPORTED, arrayListAdd.policy());
        assertEquals("JDK_COLLECTION_HELPER_UNSUPPORTED: ArrayList.add uses JVM collection semantics", arrayListAdd.reason());

        var hashMapGet = registry.lookup("java/util/HashMap", "get", "(Ljava/lang/Object;)Ljava/lang/Object;").orElseThrow();
        assertEquals(JdkMethodPolicy.JVM_HELPER_UNSUPPORTED, hashMapGet.policy());
        assertEquals("JDK_COLLECTION_HELPER_UNSUPPORTED: HashMap.get uses JVM collection semantics", hashMapGet.reason());

        var arraysAsList = registry.lookup("java/util/Arrays", "asList", "([Ljava/lang/Object;)Ljava/util/List;").orElseThrow();
        assertEquals(JdkMethodPolicy.JVM_HELPER_UNSUPPORTED, arraysAsList.policy());
        assertEquals("JDK_COLLECTION_HELPER_UNSUPPORTED: Arrays.asList uses JVM list semantics", arraysAsList.reason());

        var emptyList = registry.lookup("java/util/Collections", "emptyList", "()Ljava/util/List;").orElseThrow();
        assertEquals(JdkMethodPolicy.JVM_HELPER_UNSUPPORTED, emptyList.policy());
        assertEquals("JDK_COLLECTION_HELPER_UNSUPPORTED: Collections.emptyList uses JVM collection semantics", emptyList.reason());

        var optionalOrElse = registry.lookup("java/util/Optional", "orElse", "(Ljava/lang/Object;)Ljava/lang/Object;").orElseThrow();
        assertEquals(JdkMethodPolicy.JVM_HELPER_UNSUPPORTED, optionalOrElse.policy());
        assertEquals("JDK_OPTIONAL_HELPER_UNSUPPORTED: Optional.orElse uses JVM Optional semantics", optionalOrElse.reason());

        var stringFormat = registry.lookup("java/lang/String", "format", "(Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/String;").orElseThrow();
        assertEquals(JdkMethodPolicy.JVM_HELPER_UNSUPPORTED, stringFormat.policy());
        assertEquals("JDK_FORMAT_HELPER_UNSUPPORTED: String.format uses JVM formatter semantics", stringFormat.reason());

        var runtimeMessage = registry.lookup("java/lang/RuntimeException", "<init>", "(Ljava/lang/String;)V").orElseThrow();
        assertEquals(JdkMethodPolicy.JVM_HELPER_BRIDGE, runtimeMessage.policy());
        assertEquals("JDK_BRIDGE: RuntimeException(String) keeps JVM Throwable semantics", runtimeMessage.reason());

        var getCause = registry.lookup("java/lang/Throwable", "getCause", "()Ljava/lang/Throwable;").orElseThrow();
        assertEquals(JdkMethodPolicy.JVM_HELPER_UNSUPPORTED, getCause.policy());
        assertEquals("THROWABLE_HELPER_UNSUPPORTED: Throwable.getCause keeps JVM cause semantics", getCause.reason());

        var threadStart = registry.lookup("java/lang/Thread", "start", "()V").orElseThrow();
        assertEquals(JdkMethodPolicy.JVM_HELPER_UNSUPPORTED, threadStart.policy());
        assertEquals("THREAD_HELPER_UNSUPPORTED: Thread.start keeps JVM scheduler semantics", threadStart.reason());

        var threadSleep = registry.lookup("java/lang/Thread", "sleep", "(J)V").orElseThrow();
        assertEquals(JdkMethodPolicy.RUNTIME_HELPER, threadSleep.policy());
        assertEquals(RuntimeHelperKind.THREAD_SLEEP, threadSleep.helperKind().orElseThrow());

        var objectWait = registry.lookup("java/lang/Object", "wait", "()V").orElseThrow();
        assertEquals(JdkMethodPolicy.JVM_HELPER_UNSUPPORTED, objectWait.policy());
        assertEquals("WAIT_NOTIFY_UNSUPPORTED: Object.wait keeps JVM monitor queue semantics", objectWait.reason());
    }

    @Test
    void explicitlyBridgesRealWorldJdkInteropWithoutUnsupportedPolicy() {
        JdkIntrinsicRegistry registry = JdkIntrinsicRegistry.defaultRegistry();
        List<JdkMethodId> bridgedMethods = List.of(
                method("java/lang/Enum", "<init>", "(Ljava/lang/String;I)V"),
                method("java/lang/Exception", "getMessage", "()Ljava/lang/String;"),
                method("java/lang/String", "<init>", "([BLjava/nio/charset/Charset;)V"),
                method("java/lang/String", "getBytes", "(Ljava/nio/charset/Charset;)[B"),
                method("java/lang/String", "hashCode", "()I"),
                method("java/lang/System", "exit", "(I)V"),
                method("java/lang/Thread", "ofVirtual", "()Ljava/lang/Thread$Builder$OfVirtual;"),
                method("java/lang/Thread$Builder$OfVirtual", "start", "(Ljava/lang/Runnable;)Ljava/lang/Thread;"),
                method("java/util/Timer", "<init>", "()V"),
                method("java/util/Timer", "<init>", "(Ljava/lang/String;Z)V"),
                method("java/util/Timer", "schedule", "(Ljava/util/TimerTask;J)V"),
                method("java/util/Timer", "schedule", "(Ljava/util/TimerTask;JJ)V"),
                method("java/util/TimerTask", "<init>", "()V"),
                method("java/util/TimerTask", "cancel", "()Z"),
                method("java/security/KeyFactory", "generatePublic", "(Ljava/security/spec/KeySpec;)Ljava/security/PublicKey;"),
                method("java/security/KeyFactory", "getInstance", "(Ljava/lang/String;)Ljava/security/KeyFactory;"),
                method("java/security/MessageDigest", "digest", "()[B"),
                method("java/security/MessageDigest", "update", "([B)V"),
                method("java/security/MessageDigest", "update", "(B)V"),
                method("java/security/SecureRandom", "<init>", "()V"),
                method("java/security/SecureRandom", "nextBytes", "([B)V"),
                method("java/security/spec/X509EncodedKeySpec", "<init>", "([B)V"),
                method("java/util/Base64", "getDecoder", "()Ljava/util/Base64$Decoder;"),
                method("java/util/Base64", "getEncoder", "()Ljava/util/Base64$Encoder;"),
                method("java/util/Base64$Decoder", "decode", "(Ljava/lang/String;)[B"),
                method("java/util/Base64$Encoder", "encodeToString", "([B)Ljava/lang/String;"),
                method("javax/crypto/Cipher", "doFinal", "([B)[B"),
                method("javax/crypto/Cipher", "getInstance", "(Ljava/lang/String;)Ljavax/crypto/Cipher;"),
                method("javax/crypto/Cipher", "init", "(ILjava/security/Key;)V"),
                method("javax/crypto/Cipher", "init", "(ILjava/security/Key;Ljava/security/spec/AlgorithmParameterSpec;)V"),
                method("javax/crypto/SecretKey", "getEncoded", "()[B"),
                method("javax/crypto/spec/IvParameterSpec", "<init>", "([B)V"));

        assertEquals(32, bridgedMethods.size());
        for (JdkMethodId method : bridgedMethods) {
            JdkIntrinsic intrinsic = registry.lookup(method.owner(), method.name(), method.descriptor()).orElseThrow();
            assertEquals(JdkMethodPolicy.JVM_HELPER_BRIDGE, intrinsic.policy(), method.methodKey());
            assertTrue(intrinsic.reason().startsWith("JDK_BRIDGE:"), method.methodKey());
        }
    }

    @Test
    void explicitlyBridgesSecondRealWorldJdkBatchWithoutUnsupportedPolicy() {
        JdkIntrinsicRegistry registry = JdkIntrinsicRegistry.defaultRegistry();
        List<JdkMethodId> bridgedMethods = List.of(
                method("java/lang/Byte", "byteValue", "()B"),
                method("java/lang/Character", "charValue", "()C"),
                method("java/lang/Class", "getName", "()Ljava/lang/String;"),
                method("java/lang/Exception", "printStackTrace", "()V"),
                method("java/lang/Float", "floatValue", "()F"),
                method("java/lang/IllegalStateException", "<init>", "(Ljava/lang/Throwable;)V"),
                method("java/lang/RuntimeException", "<init>", "(Ljava/lang/Throwable;)V"),
                method("java/lang/Short", "shortValue", "()S"),
                method("java/lang/String", "compareTo", "(Ljava/lang/String;)I"),
                method("java/lang/String", "toLowerCase", "()Ljava/lang/String;"),
                method("java/net/URI", "<init>", "(Ljava/lang/String;)V"),
                method("java/nio/ByteBuffer", "allocate", "(I)Ljava/nio/ByteBuffer;"),
                method("java/nio/ByteBuffer", "array", "()[B"),
                method("java/nio/ByteBuffer", "putInt", "(I)Ljava/nio/ByteBuffer;"),
                method("java/security/MessageDigest", "getInstance", "(Ljava/lang/String;)Ljava/security/MessageDigest;"),
                method("java/util/ArrayList", "<init>", "()V"),
                method("java/util/HashMap", "<init>", "()V"),
                method("java/util/Iterator", "hasNext", "()Z"),
                method("java/util/Iterator", "next", "()Ljava/lang/Object;"),
                method("java/util/List", "add", "(Ljava/lang/Object;)Z"),
                method("java/util/List", "get", "(I)Ljava/lang/Object;"),
                method("java/util/List", "iterator", "()Ljava/util/Iterator;"),
                method("java/util/List", "set", "(ILjava/lang/Object;)Ljava/lang/Object;"),
                method("java/util/List", "size", "()I"),
                method("java/util/Map", "get", "(Ljava/lang/Object;)Ljava/lang/Object;"),
                method("java/util/Map", "put", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"),
                method("java/util/Map", "size", "()I"));

        assertEquals(27, bridgedMethods.size());
        for (JdkMethodId method : bridgedMethods) {
            JdkIntrinsic intrinsic = registry.lookup(method.owner(), method.name(), method.descriptor()).orElseThrow();
            assertEquals(JdkMethodPolicy.JVM_HELPER_BRIDGE, intrinsic.policy(), method.methodKey());
            assertTrue(intrinsic.reason().startsWith("JDK_BRIDGE:"), method.methodKey());
        }
    }

    @Test
    void explicitlyBridgesV2ResourceAndHiddenClassBatchByExactSignature() {
        JdkIntrinsicRegistry registry = JdkIntrinsicRegistry.defaultRegistry();
        List<JdkMethodId> bridgedMethods = List.of(
                method("java/io/InputStream", "close", "()V"),
                method("java/io/InputStream", "readAllBytes", "()[B"),
                method("java/lang/Throwable", "addSuppressed", "(Ljava/lang/Throwable;)V"),
                method(
                        "java/lang/invoke/MethodHandles",
                        "privateLookupIn",
                        "(Ljava/lang/Class;Ljava/lang/invoke/MethodHandles$Lookup;)"
                                + "Ljava/lang/invoke/MethodHandles$Lookup;"),
                method(
                        "java/lang/invoke/MethodHandles$Lookup",
                        "defineHiddenClass",
                        "([BZ[Ljava/lang/invoke/MethodHandles$Lookup$ClassOption;)"
                                + "Ljava/lang/invoke/MethodHandles$Lookup;"),
                method(
                        "java/lang/invoke/MethodHandles$Lookup",
                        "lookupClass",
                        "()Ljava/lang/Class;"),
                method("java/nio/ByteBuffer", "wrap", "([B)Ljava/nio/ByteBuffer;"),
                method("java/nio/ByteBuffer", "get", "()B"),
                method("java/nio/ByteBuffer", "get", "([B)Ljava/nio/ByteBuffer;"),
                method("java/nio/ByteBuffer", "remaining", "()I"),
                method("java/util/Arrays", "fill", "([BB)V"));

        assertEquals(11, bridgedMethods.size());
        for (JdkMethodId method : bridgedMethods) {
            JdkIntrinsic intrinsic =
                    registry.lookup(method.owner(), method.name(), method.descriptor()).orElseThrow();
            assertEquals(JdkMethodPolicy.JVM_HELPER_BRIDGE, intrinsic.policy(), method.methodKey());
            assertTrue(intrinsic.reason().startsWith("JDK_BRIDGE:"), method.methodKey());
        }
    }

    private JdkMethodId method(String owner, String name, String descriptor) {
        return new JdkMethodId(owner, name, descriptor);
    }
}
