package xyz.melodysky.runtime.jdk;

import java.util.List;

final class JdkGenericBridgePolicies {
    private JdkGenericBridgePolicies() {
    }

    static List<JdkIntrinsic> policies() {
        return List.of(
                bridge("java/lang/Enum", "<init>", "(Ljava/lang/String;I)V", "enum construction"),
                bridge("java/lang/Exception", "getMessage", "()Ljava/lang/String;", "Throwable message access"),
                bridge("java/lang/Exception", "printStackTrace", "()V", "Throwable stack-trace output"),
                bridge("java/lang/Byte", "byteValue", "()B", "boxed byte unboxing"),
                bridge("java/lang/Character", "charValue", "()C", "boxed char unboxing"),
                bridge("java/lang/Float", "floatValue", "()F", "boxed float unboxing"),
                bridge("java/lang/Short", "shortValue", "()S", "boxed short unboxing"),
                bridge("java/lang/Class", "getName", "()Ljava/lang/String;", "class metadata lookup"),
                bridge("java/lang/IllegalStateException", "<init>", "(Ljava/lang/Throwable;)V", "Throwable cause construction"),
                bridge("java/lang/RuntimeException", "<init>", "(Ljava/lang/Throwable;)V", "Throwable cause construction"),
                bridge("java/lang/String", "<init>", "([BLjava/nio/charset/Charset;)V", "charset decoding"),
                bridge("java/lang/String", "compareTo", "(Ljava/lang/String;)I", "String ordering"),
                bridge("java/lang/String", "getBytes", "(Ljava/nio/charset/Charset;)[B", "charset encoding"),
                bridge("java/lang/String", "hashCode", "()I", "String hash semantics"),
                bridge("java/lang/String", "toLowerCase", "()Ljava/lang/String;", "locale-sensitive lowercase conversion"),
                bridge("java/lang/System", "exit", "(I)V", "JVM process termination"),
                bridge("java/lang/Thread", "ofVirtual", "()Ljava/lang/Thread$Builder$OfVirtual;", "virtual-thread builder creation"),
                bridge("java/lang/Thread$Builder$OfVirtual", "start", "(Ljava/lang/Runnable;)Ljava/lang/Thread;", "virtual-thread scheduling"),
                bridge("java/net/URI", "<init>", "(Ljava/lang/String;)V", "URI parsing"),
                bridge("java/nio/ByteBuffer", "allocate", "(I)Ljava/nio/ByteBuffer;", "JVM buffer allocation"),
                bridge("java/nio/ByteBuffer", "array", "()[B", "JVM buffer backing-array access"),
                bridge("java/nio/ByteBuffer", "putInt", "(I)Ljava/nio/ByteBuffer;", "JVM buffer mutation"),
                bridge("java/util/Timer", "<init>", "()V", "timer construction"),
                bridge("java/util/Timer", "<init>", "(Ljava/lang/String;Z)V", "named timer construction"),
                bridge("java/util/Timer", "schedule", "(Ljava/util/TimerTask;J)V", "timer scheduling"),
                bridge("java/util/Timer", "schedule", "(Ljava/util/TimerTask;JJ)V", "repeating timer scheduling"),
                bridge("java/util/TimerTask", "<init>", "()V", "timer-task construction"),
                bridge("java/util/TimerTask", "cancel", "()Z", "timer-task cancellation"),
                bridge("java/util/Iterator", "hasNext", "()Z", "iterator dispatch"),
                bridge("java/util/Iterator", "next", "()Ljava/lang/Object;", "iterator dispatch"),
                bridge("java/util/List", "add", "(Ljava/lang/Object;)Z", "list dispatch"),
                bridge("java/util/List", "get", "(I)Ljava/lang/Object;", "list dispatch"),
                bridge("java/util/List", "iterator", "()Ljava/util/Iterator;", "list iterator creation"),
                bridge("java/util/List", "set", "(ILjava/lang/Object;)Ljava/lang/Object;", "list mutation"),
                bridge("java/util/List", "size", "()I", "list size lookup"),
                bridge("java/util/Map", "get", "(Ljava/lang/Object;)Ljava/lang/Object;", "map dispatch"),
                bridge("java/util/Map", "put", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", "map mutation"),
                bridge("java/util/Map", "size", "()I", "map size lookup"),
                bridge("java/security/KeyFactory", "generatePublic", "(Ljava/security/spec/KeySpec;)Ljava/security/PublicKey;", "provider-backed key generation"),
                bridge("java/security/KeyFactory", "getInstance", "(Ljava/lang/String;)Ljava/security/KeyFactory;", "security-provider lookup"),
                bridge("java/security/MessageDigest", "digest", "()[B", "provider-backed digest finalization"),
                bridge("java/security/MessageDigest", "getInstance", "(Ljava/lang/String;)Ljava/security/MessageDigest;", "security-provider lookup"),
                bridge("java/security/MessageDigest", "update", "([B)V", "provider-backed digest update"),
                bridge("java/security/MessageDigest", "update", "(B)V", "provider-backed digest update"),
                bridge("java/security/SecureRandom", "<init>", "()V", "JVM secure-random construction"),
                bridge("java/security/SecureRandom", "nextBytes", "([B)V", "JVM secure-random generation"),
                bridge("java/security/spec/X509EncodedKeySpec", "<init>", "([B)V", "key-spec construction"),
                bridge("java/util/Base64", "getDecoder", "()Ljava/util/Base64$Decoder;", "Base64 decoder lookup"),
                bridge("java/util/Base64", "getEncoder", "()Ljava/util/Base64$Encoder;", "Base64 encoder lookup"),
                bridge("java/util/Base64$Decoder", "decode", "(Ljava/lang/String;)[B", "Base64 decoding"),
                bridge("java/util/Base64$Encoder", "encodeToString", "([B)Ljava/lang/String;", "Base64 encoding"),
                bridge("javax/crypto/Cipher", "doFinal", "([B)[B", "provider-backed cipher finalization"),
                bridge("javax/crypto/Cipher", "getInstance", "(Ljava/lang/String;)Ljavax/crypto/Cipher;", "crypto-provider lookup"),
                bridge("javax/crypto/Cipher", "init", "(ILjava/security/Key;)V", "provider-backed cipher initialization"),
                bridge("javax/crypto/Cipher", "init", "(ILjava/security/Key;Ljava/security/spec/AlgorithmParameterSpec;)V", "provider-backed cipher initialization"),
                bridge("javax/crypto/SecretKey", "getEncoded", "()[B", "JVM key encoding"),
                bridge("javax/crypto/spec/IvParameterSpec", "<init>", "([B)V", "IV parameter construction"));
    }

    private static JdkIntrinsic bridge(String owner, String name, String descriptor, String operation) {
        return JdkIntrinsic.bridge(
                owner,
                name,
                descriptor,
                "JDK_BRIDGE: " + operation + " stays on the JVM");
    }
}
