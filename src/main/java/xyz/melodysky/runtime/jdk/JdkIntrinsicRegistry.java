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
                JdkIntrinsic.fallback("java/lang/String", "substring", "(I)Ljava/lang/String;", "single-index substring remains nativeEmbeddedClassBlob fallback fixture")));
    }

    public Optional<JdkIntrinsic> lookup(String owner, String name, String descriptor) {
        return Optional.ofNullable(methods.get(new JdkMethodId(owner, name, descriptor)));
    }
}
