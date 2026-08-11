package xyz.melodysky.toolchain;

/**
 * Exact Java and JNI metadata for an operation on a
 * {@link java.lang.reflect.Field} object.
 */
enum ReflectionFieldOperation {
    GET(
            "j2ll_rt_reflect_field_get",
            "get",
            "(Ljava/lang/Object;)Ljava/lang/Object;",
            "Object",
            "jobject",
            "jobject",
            "NULL",
            AccessKind.GETTER),
    SET(
            "j2ll_rt_reflect_field_set",
            "set",
            "(Ljava/lang/Object;Ljava/lang/Object;)V",
            "Void",
            "void",
            "jobject",
            "",
            AccessKind.SETTER),
    GET_INT(
            "j2ll_rt_reflect_field_get_int",
            "getInt",
            "(Ljava/lang/Object;)I",
            "Int",
            "int32_t",
            "jint",
            "0",
            AccessKind.GETTER),
    SET_INT(
            "j2ll_rt_reflect_field_set_int",
            "setInt",
            "(Ljava/lang/Object;I)V",
            "Void",
            "void",
            "jint",
            "",
            AccessKind.SETTER),
    GET_BOOLEAN(
            "j2ll_rt_reflect_field_get_boolean",
            "getBoolean",
            "(Ljava/lang/Object;)Z",
            "Boolean",
            "int32_t",
            "jboolean",
            "0",
            AccessKind.GETTER),
    SET_BOOLEAN(
            "j2ll_rt_reflect_field_set_boolean",
            "setBoolean",
            "(Ljava/lang/Object;Z)V",
            "Void",
            "void",
            "jboolean",
            "",
            AccessKind.SETTER),
    GET_LONG(
            "j2ll_rt_reflect_field_get_long",
            "getLong",
            "(Ljava/lang/Object;)J",
            "Long",
            "int64_t",
            "jlong",
            "0",
            AccessKind.GETTER),
    SET_LONG(
            "j2ll_rt_reflect_field_set_long",
            "setLong",
            "(Ljava/lang/Object;J)V",
            "Void",
            "void",
            "jlong",
            "",
            AccessKind.SETTER),
    GET_DOUBLE(
            "j2ll_rt_reflect_field_get_double",
            "getDouble",
            "(Ljava/lang/Object;)D",
            "Double",
            "double",
            "jdouble",
            "0.0",
            AccessKind.GETTER),
    SET_DOUBLE(
            "j2ll_rt_reflect_field_set_double",
            "setDouble",
            "(Ljava/lang/Object;D)V",
            "Void",
            "void",
            "jdouble",
            "",
            AccessKind.SETTER);

    private final String runtimeHelperSymbol;
    private final String javaMethodName;
    private final String javaMethodDescriptor;
    private final String jniCallKind;
    private final String cReturnType;
    private final String jniValueType;
    private final String defaultReturnValue;
    private final AccessKind accessKind;

    ReflectionFieldOperation(
            String runtimeHelperSymbol,
            String javaMethodName,
            String javaMethodDescriptor,
            String jniCallKind,
            String cReturnType,
            String jniValueType,
            String defaultReturnValue,
            AccessKind accessKind) {
        this.runtimeHelperSymbol = runtimeHelperSymbol;
        this.javaMethodName = javaMethodName;
        this.javaMethodDescriptor = javaMethodDescriptor;
        this.jniCallKind = jniCallKind;
        this.cReturnType = cReturnType;
        this.jniValueType = jniValueType;
        this.defaultReturnValue = defaultReturnValue;
        this.accessKind = accessKind;
        validate();
    }

    String runtimeHelperSymbol() {
        return runtimeHelperSymbol;
    }

    String javaMethodName() {
        return javaMethodName;
    }

    String javaMethodDescriptor() {
        return javaMethodDescriptor;
    }

    String jniCallKind() {
        return jniCallKind;
    }

    String cReturnType() {
        return cReturnType;
    }

    String jniValueType() {
        return jniValueType;
    }

    String defaultReturnValue() {
        return defaultReturnValue;
    }

    boolean setter() {
        return accessKind == AccessKind.SETTER;
    }

    private void validate() {
        if (setter()) {
            if (!jniCallKind.equals("Void")
                    || !cReturnType.equals("void")
                    || !defaultReturnValue.isEmpty()
                    || !javaMethodDescriptor.endsWith(")V")) {
                throw new IllegalArgumentException(
                        "invalid reflective Field setter specification");
            }
        } else if (jniCallKind.equals("Void")
                || cReturnType.equals("void")
                || defaultReturnValue.isEmpty()
                || javaMethodDescriptor.endsWith(")V")) {
            throw new IllegalArgumentException(
                    "invalid reflective Field getter specification");
        }
    }

    private enum AccessKind {
        GETTER,
        SETTER
    }
}
