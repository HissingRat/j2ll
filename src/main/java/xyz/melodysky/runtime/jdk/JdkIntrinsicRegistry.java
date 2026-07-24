package xyz.melodysky.runtime.jdk;

import java.util.ArrayList;
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
        ArrayList<JdkIntrinsic> methods = new ArrayList<>(List.of(
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
                JdkIntrinsic.bridge("java/lang/StringBuilder", "append", "(Z)Ljava/lang/StringBuilder;", "JDK_BRIDGE: StringBuilder.append(boolean) keeps JVM text semantics"),
                JdkIntrinsic.bridge("java/lang/StringBuilder", "append", "(C)Ljava/lang/StringBuilder;", "JDK_BRIDGE: StringBuilder.append(char) keeps JVM text semantics"),
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
                JdkIntrinsic.bridge("java/lang/String", "valueOf", "(Ljava/lang/Object;)Ljava/lang/String;", "JDK_BRIDGE: String.valueOf(Object) keeps JVM toString/null semantics"),
                JdkIntrinsic.bridge("java/lang/Float", "floatToRawIntBits", "(F)I", "JDK_BRIDGE: Float raw-bit conversion uses JVM helper bridge"),
                JdkIntrinsic.bridge("java/lang/Double", "doubleToRawLongBits", "(D)J", "JDK_BRIDGE: Double raw-bit conversion uses JVM helper bridge"),
                JdkIntrinsic.bridge("java/lang/Enum", "valueOf", "(Ljava/lang/Class;Ljava/lang/String;)Ljava/lang/Enum;", "JDK_BRIDGE: Enum.valueOf uses JVM enum semantics"),
                JdkIntrinsic.bridge("java/lang/Class", "getAnnotation", "(Ljava/lang/Class;)Ljava/lang/annotation/Annotation;", "JDK_BRIDGE: Class annotation lookup uses JVM metadata"),
                JdkIntrinsic.bridge("java/lang/Class", "getSimpleName", "()Ljava/lang/String;", "JDK_BRIDGE: Class simple-name lookup uses JVM metadata"),
                JdkIntrinsic.bridge("java/lang/Class", "getModule", "()Ljava/lang/Module;", "JDK_BRIDGE: Class module lookup uses JVM module metadata"),
                JdkIntrinsic.bridge("java/lang/Module", "getName", "()Ljava/lang/String;", "JDK_BRIDGE: Module name lookup uses JVM module metadata"),
                JdkIntrinsic.bridge("java/lang/Module", "isNamed", "()Z", "JDK_BRIDGE: Module named state uses JVM module metadata"),
                JdkIntrinsic.bridge("java/util/ResourceBundle", "getBundle", "(Ljava/lang/String;Ljava/util/Locale;)Ljava/util/ResourceBundle;", "JDK_BRIDGE: ResourceBundle lookup uses JVM resource loading"),
                JdkIntrinsic.bridge("java/util/ResourceBundle", "getString", "(Ljava/lang/String;)Ljava/lang/String;", "JDK_BRIDGE: ResourceBundle string lookup uses JVM resource loading"),
                JdkIntrinsic.bridge("java/text/NumberFormat", "getNumberInstance", "(Ljava/util/Locale;)Ljava/text/NumberFormat;", "JDK_BRIDGE: NumberFormat factory uses JVM locale data"),
                JdkIntrinsic.bridge("java/text/NumberFormat", "setGroupingUsed", "(Z)V", "JDK_BRIDGE: NumberFormat mutation stays on JVM object"),
                JdkIntrinsic.bridge("java/text/NumberFormat", "setMaximumFractionDigits", "(I)V", "JDK_BRIDGE: NumberFormat mutation stays on JVM object"),
                JdkIntrinsic.bridge("java/text/NumberFormat", "setMinimumFractionDigits", "(I)V", "JDK_BRIDGE: NumberFormat mutation stays on JVM object"),
                JdkIntrinsic.bridge("java/text/NumberFormat", "format", "(D)Ljava/lang/String;", "JDK_BRIDGE: NumberFormat formatting uses JVM locale data"),
                JdkIntrinsic.bridge("java/lang/invoke/MethodHandles", "lookup", "()Ljava/lang/invoke/MethodHandles$Lookup;", "JDK_BRIDGE: MethodHandles.lookup uses JVM lookup semantics"),
                JdkIntrinsic.bridge("java/lang/invoke/MethodHandles", "dropArguments", "(Ljava/lang/invoke/MethodHandle;ILjava/util/List;)Ljava/lang/invoke/MethodHandle;", "JDK_BRIDGE: MethodHandle adapter creation uses JVM semantics"),
                JdkIntrinsic.bridge("java/lang/invoke/MethodHandles", "dropArguments", "(Ljava/lang/invoke/MethodHandle;I[Ljava/lang/Class;)Ljava/lang/invoke/MethodHandle;", "JDK_BRIDGE: MethodHandle adapter creation uses JVM semantics"),
                JdkIntrinsic.bridge("java/lang/invoke/MethodHandles", "permuteArguments", "(Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;[I)Ljava/lang/invoke/MethodHandle;", "JDK_BRIDGE: MethodHandle adapter creation uses JVM semantics"),
                JdkIntrinsic.bridge("java/lang/invoke/MethodHandles", "filterArguments", "(Ljava/lang/invoke/MethodHandle;I[Ljava/lang/invoke/MethodHandle;)Ljava/lang/invoke/MethodHandle;", "JDK_BRIDGE: MethodHandle adapter creation uses JVM semantics"),
                JdkIntrinsic.bridge("java/lang/invoke/MethodHandles", "foldArguments", "(Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodHandle;)Ljava/lang/invoke/MethodHandle;", "JDK_BRIDGE: MethodHandle adapter creation uses JVM semantics"),
                JdkIntrinsic.bridge("java/lang/invoke/MethodHandles$Lookup", "findStatic", "(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/MethodHandle;", "JDK_BRIDGE: Lookup.findStatic uses JVM MethodHandle semantics"),
                JdkIntrinsic.bridge("java/lang/invoke/MethodHandles$Lookup", "findVarHandle", "(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/Class;)Ljava/lang/invoke/VarHandle;", "JDK_BRIDGE: Lookup.findVarHandle uses JVM VarHandle semantics"),
                JdkIntrinsic.bridge("java/lang/invoke/MethodType", "methodType", "(Ljava/lang/Class;)Ljava/lang/invoke/MethodType;", "JDK_BRIDGE: MethodType factory uses JVM descriptor semantics"),
                JdkIntrinsic.bridge("java/lang/invoke/MethodType", "methodType", "(Ljava/lang/Class;Ljava/lang/Class;)Ljava/lang/invoke/MethodType;", "JDK_BRIDGE: MethodType factory uses JVM descriptor semantics"),
                JdkIntrinsic.bridge("java/lang/invoke/MethodType", "methodType", "(Ljava/lang/Class;Ljava/lang/Class;Ljava/lang/Class;)Ljava/lang/invoke/MethodType;", "JDK_BRIDGE: MethodType factory uses JVM descriptor semantics"),
                JdkIntrinsic.bridge("java/lang/invoke/MethodType", "methodType", "(Ljava/lang/Class;Ljava/lang/Class;[Ljava/lang/Class;)Ljava/lang/invoke/MethodType;", "JDK_BRIDGE: MethodType factory uses JVM descriptor semantics"),
                JdkIntrinsic.bridge("java/lang/invoke/MethodType", "methodType", "(Ljava/lang/Class;Ljava/util/List;)Ljava/lang/invoke/MethodType;", "JDK_BRIDGE: MethodType factory uses JVM descriptor semantics"),
                JdkIntrinsic.bridge("java/lang/invoke/MethodHandle", "bindTo", "(Ljava/lang/Object;)Ljava/lang/invoke/MethodHandle;", "JDK_BRIDGE: MethodHandle adapter uses JVM semantics"),
                JdkIntrinsic.bridge("java/lang/invoke/MethodHandle", "asType", "(Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/MethodHandle;", "JDK_BRIDGE: MethodHandle adapter uses JVM semantics"),
                JdkIntrinsic.bridge("java/lang/invoke/MethodHandle", "asCollector", "(Ljava/lang/Class;I)Ljava/lang/invoke/MethodHandle;", "JDK_BRIDGE: MethodHandle adapter uses JVM semantics"),
                JdkIntrinsic.bridge("java/util/Arrays", "stream", "([Ljava/lang/Object;)Ljava/util/stream/Stream;", "JDK_BRIDGE: Arrays.stream uses JVM collection semantics"),
                JdkIntrinsic.bridge("java/util/stream/Collectors", "joining", "(Ljava/lang/CharSequence;)Ljava/util/stream/Collector;", "JDK_BRIDGE: Collectors.joining uses JVM stream semantics"),
                JdkIntrinsic.bridge("java/util/stream/Stream", "map", "(Ljava/util/function/Function;)Ljava/util/stream/Stream;", "JDK_BRIDGE: Stream.map uses JVM stream semantics"),
                JdkIntrinsic.bridge("java/util/stream/Stream", "sorted", "()Ljava/util/stream/Stream;", "JDK_BRIDGE: Stream.sorted uses JVM stream semantics"),
                JdkIntrinsic.bridge("java/util/stream/Stream", "collect", "(Ljava/util/stream/Collector;)Ljava/lang/Object;", "JDK_BRIDGE: Stream.collect uses JVM stream semantics"),
                JdkIntrinsic.bridge("java/util/function/Function", "apply", "(Ljava/lang/Object;)Ljava/lang/Object;", "JDK_BRIDGE: Function.apply uses JVM dispatch semantics"),
                JdkIntrinsic.bridge("java/util/function/IntSupplier", "getAsInt", "()I", "JDK_BRIDGE: IntSupplier.getAsInt uses JVM dispatch semantics"),
                JdkIntrinsic.bridge("java/util/ArrayList", "<init>", "()V", "JDK_BRIDGE: ArrayList construction stays on the JVM"),
                JdkIntrinsic.fallback("java/util/ArrayList", "add", "(Ljava/lang/Object;)Z", "JDK_COLLECTION_HELPER_FALLBACK: ArrayList.add uses JVM collection semantics"),
                JdkIntrinsic.fallback("java/util/ArrayList", "get", "(I)Ljava/lang/Object;", "JDK_COLLECTION_HELPER_FALLBACK: ArrayList.get uses JVM collection semantics"),
                JdkIntrinsic.fallback("java/util/ArrayList", "size", "()I", "JDK_COLLECTION_HELPER_FALLBACK: ArrayList.size uses JVM collection semantics"),
                JdkIntrinsic.bridge("java/util/HashMap", "<init>", "()V", "JDK_BRIDGE: HashMap construction stays on the JVM"),
                JdkIntrinsic.fallback("java/util/HashMap", "put", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", "JDK_COLLECTION_HELPER_FALLBACK: HashMap.put uses JVM collection semantics"),
                JdkIntrinsic.fallback("java/util/HashMap", "get", "(Ljava/lang/Object;)Ljava/lang/Object;", "JDK_COLLECTION_HELPER_FALLBACK: HashMap.get uses JVM collection semantics"),
                JdkIntrinsic.fallback("java/util/HashMap", "containsKey", "(Ljava/lang/Object;)Z", "JDK_COLLECTION_HELPER_FALLBACK: HashMap.containsKey uses JVM collection semantics"),
                JdkIntrinsic.fallback("java/util/Arrays", "copyOf", "([II)[I", "JDK_ARRAYS_HELPER_FALLBACK: Arrays.copyOf uses JVM array semantics"),
                JdkIntrinsic.bridge("java/util/Arrays", "equals", "([I[I)Z", "JDK_BRIDGE: Arrays.equals uses JVM array semantics"),
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
                JdkIntrinsic.bridge("java/lang/RuntimeException", "<init>", "(Ljava/lang/String;)V", "JDK_BRIDGE: RuntimeException(String) keeps JVM Throwable semantics"),
                JdkIntrinsic.bridge("java/lang/IllegalArgumentException", "<init>", "(Ljava/lang/String;)V", "JDK_BRIDGE: IllegalArgumentException(String) keeps JVM Throwable semantics"),
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
        methods.addAll(JdkGenericBridgePolicies.policies());
        return new JdkIntrinsicRegistry(methods);
    }

    public Optional<JdkIntrinsic> lookup(String owner, String name, String descriptor) {
        return Optional.ofNullable(methods.get(new JdkMethodId(owner, name, descriptor)));
    }
}
