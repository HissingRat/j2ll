package xyz.melodysky.runtime.jdk;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import xyz.melodysky.runtime.RuntimeHelperKind;

public final class JdkIntrinsicRegistry {
    private final Map<JdkMethodId, JdkIntrinsic> methods;

    public JdkIntrinsicRegistry(List<JdkIntrinsic> methods) {
        HashMap<JdkMethodId, JdkIntrinsic> map = new HashMap<>();
        for (JdkIntrinsic method : methods) {
            if (map.putIfAbsent(method.method(), method) != null) {
                throw new IllegalArgumentException("duplicate JDK method policy " + method.method().methodKey());
            }
        }
        this.methods = Map.copyOf(map);
    }

    public static JdkIntrinsicRegistry defaultRegistry() {
        return new JdkIntrinsicRegistry(List.of(
                JdkIntrinsic.direct("java/lang/Object", "<init>", "()V"),
                JdkIntrinsic.runtimeHelper("java/lang/Object", "getClass", "()Ljava/lang/Class;", RuntimeHelperKind.OBJECT_GET_CLASS),
                JdkIntrinsic.runtimeHelper("java/lang/Object", "hashCode", "()I", RuntimeHelperKind.OBJECT_HASH_CODE),
                JdkIntrinsic.runtimeHelper("java/lang/Object", "equals", "(Ljava/lang/Object;)Z", RuntimeHelperKind.OBJECT_EQUALS),
                JdkIntrinsic.runtimeHelper("java/lang/String", "length", "()I", RuntimeHelperKind.STRING_LENGTH),
                JdkIntrinsic.runtimeHelper("java/lang/String", "isEmpty", "()Z", RuntimeHelperKind.STRING_IS_EMPTY),
                JdkIntrinsic.runtimeHelper("java/lang/String", "charAt", "(I)C", RuntimeHelperKind.STRING_CHAR_AT),
                JdkIntrinsic.runtimeHelper("java/lang/String", "equals", "(Ljava/lang/Object;)Z", RuntimeHelperKind.STRING_EQUALS),
                JdkIntrinsic.runtimeHelper("java/lang/String", "startsWith", "(Ljava/lang/String;)Z", RuntimeHelperKind.STRING_STARTS_WITH),
                JdkIntrinsic.runtimeHelper("java/lang/String", "endsWith", "(Ljava/lang/String;)Z", RuntimeHelperKind.STRING_ENDS_WITH),
                JdkIntrinsic.runtimeHelper("java/lang/String", "substring", "(II)Ljava/lang/String;", RuntimeHelperKind.STRING_SUBSTRING_RANGE),
                JdkIntrinsic.runtimeHelper("java/lang/StringBuilder", "<init>", "()V", RuntimeHelperKind.STRING_BUILDER_INIT),
                JdkIntrinsic.runtimeHelper("java/lang/StringBuilder", "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;", RuntimeHelperKind.STRING_BUILDER_APPEND_REF),
                JdkIntrinsic.runtimeHelper("java/lang/StringBuilder", "append", "(Ljava/lang/Object;)Ljava/lang/StringBuilder;", RuntimeHelperKind.STRING_BUILDER_APPEND_REF),
                JdkIntrinsic.runtimeHelper("java/lang/StringBuilder", "append", "(I)Ljava/lang/StringBuilder;", RuntimeHelperKind.STRING_BUILDER_APPEND_I32),
                JdkIntrinsic.runtimeHelper("java/lang/StringBuilder", "append", "(Z)Ljava/lang/StringBuilder;", RuntimeHelperKind.STRING_BUILDER_APPEND_I32),
                JdkIntrinsic.runtimeHelper("java/lang/StringBuilder", "append", "(C)Ljava/lang/StringBuilder;", RuntimeHelperKind.STRING_BUILDER_APPEND_I32),
                JdkIntrinsic.runtimeHelper("java/lang/StringBuilder", "append", "(J)Ljava/lang/StringBuilder;", RuntimeHelperKind.STRING_BUILDER_APPEND_I64),
                JdkIntrinsic.runtimeHelper("java/lang/StringBuilder", "append", "(F)Ljava/lang/StringBuilder;", RuntimeHelperKind.STRING_BUILDER_APPEND_F32),
                JdkIntrinsic.runtimeHelper("java/lang/StringBuilder", "append", "(D)Ljava/lang/StringBuilder;", RuntimeHelperKind.STRING_BUILDER_APPEND_F64),
                JdkIntrinsic.runtimeHelper("java/lang/StringBuilder", "toString", "()Ljava/lang/String;", RuntimeHelperKind.STRING_BUILDER_TO_STRING),
                JdkIntrinsic.runtimeHelper("java/lang/System", "arraycopy", "(Ljava/lang/Object;ILjava/lang/Object;II)V", RuntimeHelperKind.SYSTEM_ARRAYCOPY),
                JdkIntrinsic.runtimeHelper("java/lang/Math", "abs", "(I)I", RuntimeHelperKind.MATH_ABS_I32),
                JdkIntrinsic.runtimeHelper("java/lang/Math", "abs", "(J)J", RuntimeHelperKind.MATH_ABS_I64),
                JdkIntrinsic.runtimeHelper("java/lang/Math", "abs", "(F)F", RuntimeHelperKind.MATH_ABS_F32),
                JdkIntrinsic.runtimeHelper("java/lang/Math", "abs", "(D)D", RuntimeHelperKind.MATH_ABS_F64),
                JdkIntrinsic.runtimeHelper("java/lang/Math", "min", "(II)I", RuntimeHelperKind.MATH_MIN_I32),
                JdkIntrinsic.runtimeHelper("java/lang/Math", "min", "(JJ)J", RuntimeHelperKind.MATH_MIN_I64),
                JdkIntrinsic.runtimeHelper("java/lang/Math", "min", "(FF)F", RuntimeHelperKind.MATH_MIN_F32),
                JdkIntrinsic.runtimeHelper("java/lang/Math", "min", "(DD)D", RuntimeHelperKind.MATH_MIN_F64),
                JdkIntrinsic.runtimeHelper("java/lang/Math", "max", "(II)I", RuntimeHelperKind.MATH_MAX_I32),
                JdkIntrinsic.runtimeHelper("java/lang/Math", "max", "(JJ)J", RuntimeHelperKind.MATH_MAX_I64),
                JdkIntrinsic.runtimeHelper("java/lang/Math", "max", "(FF)F", RuntimeHelperKind.MATH_MAX_F32),
                JdkIntrinsic.runtimeHelper("java/lang/Math", "max", "(DD)D", RuntimeHelperKind.MATH_MAX_F64),
                JdkIntrinsic.runtimeHelper("java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;", RuntimeHelperKind.INTEGER_VALUE_OF),
                JdkIntrinsic.runtimeHelper("java/lang/Integer", "intValue", "()I", RuntimeHelperKind.INTEGER_INT_VALUE),
                JdkIntrinsic.runtimeHelper("java/lang/Long", "valueOf", "(J)Ljava/lang/Long;", RuntimeHelperKind.LONG_VALUE_OF),
                JdkIntrinsic.runtimeHelper("java/lang/Long", "longValue", "()J", RuntimeHelperKind.LONG_LONG_VALUE),
                JdkIntrinsic.runtimeHelper("java/lang/Boolean", "valueOf", "(Z)Ljava/lang/Boolean;", RuntimeHelperKind.BOOLEAN_VALUE_OF),
                JdkIntrinsic.runtimeHelper("java/lang/Boolean", "booleanValue", "()Z", RuntimeHelperKind.BOOLEAN_BOOLEAN_VALUE),
                JdkIntrinsic.runtimeHelper("java/lang/Double", "valueOf", "(D)Ljava/lang/Double;", RuntimeHelperKind.DOUBLE_VALUE_OF),
                JdkIntrinsic.runtimeHelper("java/lang/Double", "doubleValue", "()D", RuntimeHelperKind.DOUBLE_DOUBLE_VALUE),
                JdkIntrinsic.runtimeHelper("java/util/Objects", "requireNonNull", "(Ljava/lang/Object;)Ljava/lang/Object;", RuntimeHelperKind.OBJECTS_REQUIRE_NON_NULL),
                JdkIntrinsic.runtimeHelper("java/util/Objects", "equals", "(Ljava/lang/Object;Ljava/lang/Object;)Z", RuntimeHelperKind.OBJECTS_EQUALS),
                JdkIntrinsic.fallback("java/util/ArrayList", "<init>", "()V", "JDK_COLLECTION_HELPER_FALLBACK: ArrayList constructor uses JVM collection semantics"),
                JdkIntrinsic.fallback("java/util/ArrayList", "add", "(Ljava/lang/Object;)Z", "JDK_COLLECTION_HELPER_FALLBACK: ArrayList.add uses JVM collection semantics"),
                JdkIntrinsic.fallback("java/util/ArrayList", "get", "(I)Ljava/lang/Object;", "JDK_COLLECTION_HELPER_FALLBACK: ArrayList.get uses JVM collection semantics"),
                JdkIntrinsic.fallback("java/util/ArrayList", "size", "()I", "JDK_COLLECTION_HELPER_FALLBACK: ArrayList.size uses JVM collection semantics"),
                JdkIntrinsic.fallback("java/util/HashMap", "<init>", "()V", "JDK_COLLECTION_HELPER_FALLBACK: HashMap constructor uses JVM collection semantics"),
                JdkIntrinsic.fallback("java/util/HashMap", "put", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", "JDK_COLLECTION_HELPER_FALLBACK: HashMap.put uses JVM collection semantics"),
                JdkIntrinsic.fallback("java/util/HashMap", "get", "(Ljava/lang/Object;)Ljava/lang/Object;", "JDK_COLLECTION_HELPER_FALLBACK: HashMap.get uses JVM collection semantics"),
                JdkIntrinsic.fallback("java/util/HashMap", "containsKey", "(Ljava/lang/Object;)Z", "JDK_COLLECTION_HELPER_FALLBACK: HashMap.containsKey uses JVM collection semantics"),
                JdkIntrinsic.fallback("java/util/Arrays", "copyOf", "([II)[I", "JDK_ARRAYS_HELPER_FALLBACK: Arrays.copyOf uses JVM array semantics"),
                JdkIntrinsic.fallback("java/util/Arrays", "equals", "([I[I)Z", "JDK_ARRAYS_HELPER_FALLBACK: Arrays.equals uses JVM array semantics"),
                JdkIntrinsic.fallback("java/util/Arrays", "fill", "([IIII)V", "JDK_ARRAYS_HELPER_FALLBACK: Arrays.fill uses JVM array semantics"),
                JdkIntrinsic.fallback("java/util/Arrays", "asList", "([Ljava/lang/Object;)Ljava/util/List;", "JDK_COLLECTION_HELPER_FALLBACK: Arrays.asList uses JVM list semantics"),
                JdkIntrinsic.fallback("java/util/Collections", "emptyList", "()Ljava/util/List;", "JDK_COLLECTION_HELPER_FALLBACK: Collections.emptyList uses JVM collection semantics"),
                JdkIntrinsic.fallback("java/util/Collections", "singletonList", "(Ljava/lang/Object;)Ljava/util/List;", "JDK_COLLECTION_HELPER_FALLBACK: Collections.singletonList uses JVM collection semantics"),
                JdkIntrinsic.fallback("java/util/Optional", "of", "(Ljava/lang/Object;)Ljava/util/Optional;", "JDK_OPTIONAL_HELPER_FALLBACK: Optional.of uses JVM Optional semantics"),
                JdkIntrinsic.fallback("java/util/Optional", "ofNullable", "(Ljava/lang/Object;)Ljava/util/Optional;", "JDK_OPTIONAL_HELPER_FALLBACK: Optional.ofNullable uses JVM Optional semantics"),
                JdkIntrinsic.fallback("java/util/Optional", "isPresent", "()Z", "JDK_OPTIONAL_HELPER_FALLBACK: Optional.isPresent uses JVM Optional semantics"),
                JdkIntrinsic.fallback("java/util/Optional", "get", "()Ljava/lang/Object;", "JDK_OPTIONAL_HELPER_FALLBACK: Optional.get uses JVM Optional semantics"),
                JdkIntrinsic.fallback("java/util/Optional", "orElse", "(Ljava/lang/Object;)Ljava/lang/Object;", "JDK_OPTIONAL_HELPER_FALLBACK: Optional.orElse uses JVM Optional semantics"),
                JdkIntrinsic.fallback("java/lang/String", "format", "(Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/String;", "JDK_FORMAT_HELPER_FALLBACK: String.format uses JVM formatter semantics"),
                JdkIntrinsic.fallback("java/lang/RuntimeException", "<init>", "(Ljava/lang/String;)V", "THROWABLE_HELPER_FALLBACK: RuntimeException(String) keeps JVM Throwable semantics"),
                JdkIntrinsic.fallback("java/lang/IllegalArgumentException", "<init>", "(Ljava/lang/String;)V", "THROWABLE_HELPER_FALLBACK: IllegalArgumentException(String) keeps JVM Throwable semantics"),
                JdkIntrinsic.fallback("java/lang/Throwable", "getMessage", "()Ljava/lang/String;", "THROWABLE_HELPER_FALLBACK: Throwable.getMessage keeps JVM Throwable semantics"),
                JdkIntrinsic.fallback("java/lang/Throwable", "initCause", "(Ljava/lang/Throwable;)Ljava/lang/Throwable;", "THROWABLE_HELPER_FALLBACK: Throwable.initCause keeps JVM cause semantics"),
                JdkIntrinsic.fallback("java/lang/Throwable", "getCause", "()Ljava/lang/Throwable;", "THROWABLE_HELPER_FALLBACK: Throwable.getCause keeps JVM cause semantics"),
                JdkIntrinsic.fallback("java/lang/Thread", "<init>", "(Ljava/lang/Runnable;)V", "THREAD_HELPER_FALLBACK: Thread(Runnable) keeps JVM thread semantics"),
                JdkIntrinsic.fallback("java/lang/Thread", "start", "()V", "THREAD_HELPER_FALLBACK: Thread.start keeps JVM scheduler semantics"),
                JdkIntrinsic.fallback("java/lang/Thread", "join", "()V", "THREAD_HELPER_FALLBACK: Thread.join keeps JVM scheduler semantics"),
                JdkIntrinsic.fallback("java/lang/Object", "wait", "()V", "WAIT_NOTIFY_FALLBACK: Object.wait keeps JVM monitor queue semantics"),
                JdkIntrinsic.fallback("java/lang/Object", "wait", "(J)V", "WAIT_NOTIFY_FALLBACK: Object.wait(long) keeps JVM monitor queue semantics"),
                JdkIntrinsic.fallback("java/lang/Object", "notify", "()V", "WAIT_NOTIFY_FALLBACK: Object.notify keeps JVM monitor queue semantics"),
                JdkIntrinsic.fallback("java/lang/String", "substring", "(I)Ljava/lang/String;", "single-index substring remains nativeEmbeddedClassBlob fallback fixture")));
    }

    public Optional<JdkIntrinsic> lookup(String owner, String name, String descriptor) {
        return Optional.ofNullable(methods.get(new JdkMethodId(owner, name, descriptor)));
    }
}
