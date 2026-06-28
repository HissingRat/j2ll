package xyz.melodysky.runtime;

import java.util.List;
import java.util.Objects;

public record RuntimeHelper(
        RuntimeHelperKind kind,
        RuntimeHelperCategory category,
        String name,
        String llvmSymbol,
        RuntimeHelperSignature signature) {
    private static final RuntimeAbi ABI = new RuntimeAbi();

    public RuntimeHelper {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(llvmSymbol, "llvmSymbol");
        Objects.requireNonNull(signature, "signature");
    }

    public RuntimeHelper(
            RuntimeHelperKind kind,
            String name,
            String llvmSymbol,
            String llvmReturnType,
            List<String> llvmParameterTypes) {
        this(
                kind,
                categoryFor(kind),
                name,
                llvmSymbol,
                new RuntimeHelperSignature(llvmReturnType, llvmParameterTypes));
    }

    public String llvmReturnType() {
        return ABI.llvmType(signature.returnType());
    }

    public List<String> llvmParameterTypes() {
        if (category == RuntimeHelperCategory.FIELD_ACCESS
                || category == RuntimeHelperCategory.CALL_SITE
                || category == RuntimeHelperCategory.ARITHMETIC_EXCEPTION
                || category == RuntimeHelperCategory.ALLOCATION
                || category == RuntimeHelperCategory.STRING_HELPER
                || category == RuntimeHelperCategory.ARRAY_ACCESS
                || category == RuntimeHelperCategory.TYPE_CHECK
                || category == RuntimeHelperCategory.MONITOR
                || category == RuntimeHelperCategory.CLASS_INIT
                || category == RuntimeHelperCategory.EXCEPTION
                || isEnvBackedJdkIntrinsic(kind)) {
            java.util.ArrayList<String> parameters = new java.util.ArrayList<>();
            parameters.add("ptr");
            signature.parameterTypes().stream().map(ABI::llvmType).forEach(parameters::add);
            return List.copyOf(parameters);
        }
        return signature.parameterTypes().stream().map(ABI::llvmType).toList();
    }

    private static boolean isEnvBackedJdkIntrinsic(RuntimeHelperKind kind) {
        return kind == RuntimeHelperKind.CLASS_FOR_NAME_STATIC
                || kind == RuntimeHelperKind.GET_DECLARED_METHOD
                || kind == RuntimeHelperKind.GET_DECLARED_FIELD
                || kind == RuntimeHelperKind.GET_DECLARED_CONSTRUCTOR
                || kind == RuntimeHelperKind.REFLECT_INVOKE
                || kind == RuntimeHelperKind.REFLECT_NEW_INSTANCE
                || kind == RuntimeHelperKind.REFLECT_SET_ACCESSIBLE
                || kind == RuntimeHelperKind.REFLECT_FIELD_GET
                || kind == RuntimeHelperKind.REFLECT_FIELD_SET
                || kind == RuntimeHelperKind.REFLECT_FIELD_GET_INT
                || kind == RuntimeHelperKind.REFLECT_FIELD_SET_INT
                || kind == RuntimeHelperKind.REFLECT_FIELD_GET_BOOLEAN
                || kind == RuntimeHelperKind.REFLECT_FIELD_SET_BOOLEAN
                || kind == RuntimeHelperKind.REFLECT_FIELD_GET_LONG
                || kind == RuntimeHelperKind.REFLECT_FIELD_SET_LONG
                || kind == RuntimeHelperKind.REFLECT_FIELD_GET_DOUBLE
                || kind == RuntimeHelperKind.REFLECT_FIELD_SET_DOUBLE
                || kind == RuntimeHelperKind.STRING_BUILDER_NEW
                || kind == RuntimeHelperKind.STRING_BUILDER_INIT
                || kind == RuntimeHelperKind.STRING_BUILDER_APPEND_REF
                || kind == RuntimeHelperKind.STRING_BUILDER_APPEND_I32
                || kind == RuntimeHelperKind.STRING_BUILDER_APPEND_I64
                || kind == RuntimeHelperKind.STRING_BUILDER_APPEND_F32
                || kind == RuntimeHelperKind.STRING_BUILDER_APPEND_F64
                || kind == RuntimeHelperKind.STRING_BUILDER_TO_STRING
                || kind == RuntimeHelperKind.SYSTEM_ARRAYCOPY
                || kind == RuntimeHelperKind.INTEGER_VALUE_OF
                || kind == RuntimeHelperKind.INTEGER_INT_VALUE
                || kind == RuntimeHelperKind.LONG_VALUE_OF
                || kind == RuntimeHelperKind.LONG_LONG_VALUE
                || kind == RuntimeHelperKind.BOOLEAN_VALUE_OF
                || kind == RuntimeHelperKind.BOOLEAN_BOOLEAN_VALUE
                || kind == RuntimeHelperKind.DOUBLE_VALUE_OF
                || kind == RuntimeHelperKind.DOUBLE_DOUBLE_VALUE
                || kind == RuntimeHelperKind.OBJECTS_REQUIRE_NON_NULL
                || kind == RuntimeHelperKind.OBJECTS_EQUALS
                || kind == RuntimeHelperKind.LAMBDA_NEW
                || kind == RuntimeHelperKind.METHOD_HANDLE_INVOKE_EXACT
                || kind == RuntimeHelperKind.CONSTANT_DYNAMIC
                || kind == RuntimeHelperKind.UNSAFE_OBJECT_FIELD_OFFSET
                || kind == RuntimeHelperKind.UNSAFE_STATIC_FIELD_OFFSET
                || kind == RuntimeHelperKind.UNSAFE_ARRAY_BASE_OFFSET
                || kind == RuntimeHelperKind.UNSAFE_ARRAY_INDEX_SCALE
                || kind == RuntimeHelperKind.UNSAFE_GET_INT
                || kind == RuntimeHelperKind.UNSAFE_PUT_INT
                || kind == RuntimeHelperKind.UNSAFE_COMPARE_AND_SWAP_INT
                || kind == RuntimeHelperKind.UNSAFE_GET
                || kind == RuntimeHelperKind.UNSAFE_PUT
                || kind == RuntimeHelperKind.UNSAFE_GET_VOLATILE
                || kind == RuntimeHelperKind.UNSAFE_PUT_VOLATILE
                || kind == RuntimeHelperKind.UNSAFE_COMPARE_AND_SWAP
                || kind == RuntimeHelperKind.UNSAFE_ALLOCATE_INSTANCE
                || kind == RuntimeHelperKind.VAR_HANDLE_GET_INT
                || kind == RuntimeHelperKind.VAR_HANDLE_SET_INT
                || kind == RuntimeHelperKind.VAR_HANDLE_GET_VOLATILE_INT
                || kind == RuntimeHelperKind.VAR_HANDLE_SET_VOLATILE_INT
                || kind == RuntimeHelperKind.VAR_HANDLE_COMPARE_AND_SET_INT;
    }

    private static RuntimeHelperCategory categoryFor(RuntimeHelperKind kind) {
        return switch (kind) {
            case NULL_CHECK, ARRAY_BOUNDS_CHECK -> RuntimeHelperCategory.ARRAY_TYPE_NULL_CHECK;
            case THROW, RETHROW, PENDING_EXCEPTION, CLEAR_EXCEPTION, CATCH_DISPATCH,
                    CREATE_NULL_POINTER_EXCEPTION, CREATE_ARRAY_INDEX_OUT_OF_BOUNDS_EXCEPTION,
                    CREATE_ARRAY_STORE_EXCEPTION, CREATE_CLASS_CAST_EXCEPTION,
                    CREATE_ARITHMETIC_EXCEPTION -> RuntimeHelperCategory.EXCEPTION;
            case DIV_I32, REM_I32, DIV_I64, REM_I64 -> RuntimeHelperCategory.ARITHMETIC_EXCEPTION;
            case CLASS_INIT, CLASS_INIT_GUARD, CLASS_INIT_BEGIN, CLASS_INIT_END,
                    CLASS_INIT_FAILED, CLASS_OBJECT -> RuntimeHelperCategory.CLASS_INIT;
            case EXCEPTION_BRIDGE -> RuntimeHelperCategory.JNI_BRIDGE;
            case MONITOR_ENTER, MONITOR_EXIT, MONITOR_EXIT_ON_EXCEPTION -> RuntimeHelperCategory.MONITOR;
            case THREAD_START_HAPPENS_BEFORE, THREAD_JOIN_HAPPENS_BEFORE -> RuntimeHelperCategory.JMM_FENCE;
            case FIELD_GET_STATIC_I32, FIELD_PUT_STATIC_I32, FIELD_GET_FIELD_I32, FIELD_PUT_FIELD_I32,
                    FIELD_GET_STATIC_I64, FIELD_PUT_STATIC_I64, FIELD_GET_FIELD_I64, FIELD_PUT_FIELD_I64,
                    FIELD_GET_STATIC_REF, FIELD_PUT_STATIC_REF, FIELD_GET_FIELD_REF, FIELD_PUT_FIELD_REF ->
                    RuntimeHelperCategory.FIELD_ACCESS;
            case CALL_STATIC_I32, CALL_SPECIAL_I32, CALL_CONSTRUCTOR_VOID,
                    CALL_CONSTRUCTOR_VOID_I32_I32,
                    CALL_VIRTUAL_I32, CALL_INTERFACE_I32,
                    CALL_VIRTUAL_I32_ARG_I32, CALL_INTERFACE_I32_ARG_I32,
                    CALL_STATIC_REF, CALL_VIRTUAL_REF, CALL_INTERFACE_REF,
                    CALL_VIRTUAL_REF_ARG_REF, CALL_INTERFACE_REF_ARG_REF -> RuntimeHelperCategory.CALL_SITE;
            case STRING_LENGTH, STRING_IS_EMPTY, STRING_CHAR_AT, STRING_EQUALS,
                    STRING_STARTS_WITH, STRING_ENDS_WITH, STRING_SUBSTRING,
                    STRING_SUBSTRING_RANGE, STRING_CONSTANT ->
                    RuntimeHelperCategory.STRING_HELPER;
            case I2B, I2C, I2S, F2I, F2L, D2I, D2L,
                    LCMP, FCMPL, FCMPG, DCMPL, DCMPG,
                    OBJECT_GET_CLASS, OBJECT_HASH_CODE, OBJECT_EQUALS,
                    STRING_BUILDER_NEW, STRING_BUILDER_INIT, STRING_BUILDER_APPEND_REF,
                    STRING_BUILDER_APPEND_I32, STRING_BUILDER_APPEND_I64,
                    STRING_BUILDER_APPEND_F32, STRING_BUILDER_APPEND_F64,
                    STRING_BUILDER_TO_STRING, SYSTEM_ARRAYCOPY,
                    MATH_ABS_I32, MATH_ABS_I64, MATH_ABS_F32, MATH_ABS_F64,
                    MATH_MIN_I32, MATH_MIN_I64, MATH_MIN_F32, MATH_MIN_F64,
                    MATH_MAX_I32, MATH_MAX_I64, MATH_MAX_F32, MATH_MAX_F64,
                    INTEGER_VALUE_OF, INTEGER_INT_VALUE,
                    LONG_VALUE_OF, LONG_LONG_VALUE, BOOLEAN_VALUE_OF,
                    BOOLEAN_BOOLEAN_VALUE, DOUBLE_VALUE_OF, DOUBLE_DOUBLE_VALUE,
                    OBJECTS_REQUIRE_NON_NULL, OBJECTS_EQUALS, LAMBDA_NEW,
                    CLASS_FOR_NAME_STATIC, GET_DECLARED_METHOD, GET_DECLARED_FIELD,
                    GET_DECLARED_CONSTRUCTOR, REFLECT_INVOKE,
                    REFLECT_NEW_INSTANCE, REFLECT_SET_ACCESSIBLE, REFLECT_FIELD_GET, REFLECT_FIELD_SET,
                    REFLECT_FIELD_GET_INT, REFLECT_FIELD_SET_INT,
                    REFLECT_FIELD_GET_BOOLEAN, REFLECT_FIELD_SET_BOOLEAN,
                    REFLECT_FIELD_GET_LONG, REFLECT_FIELD_SET_LONG,
                    REFLECT_FIELD_GET_DOUBLE, REFLECT_FIELD_SET_DOUBLE,
                    METHOD_HANDLE_INVOKE_EXACT,
                    CONSTANT_DYNAMIC, UNSAFE_OBJECT_FIELD_OFFSET, UNSAFE_STATIC_FIELD_OFFSET,
                    UNSAFE_ARRAY_BASE_OFFSET, UNSAFE_ARRAY_INDEX_SCALE,
                    UNSAFE_GET_INT, UNSAFE_PUT_INT, UNSAFE_COMPARE_AND_SWAP_INT,
                    UNSAFE_GET, UNSAFE_PUT, UNSAFE_GET_VOLATILE, UNSAFE_PUT_VOLATILE,
                    UNSAFE_COMPARE_AND_SWAP, UNSAFE_ALLOCATE_INSTANCE,
                    VAR_HANDLE_GET_INT, VAR_HANDLE_SET_INT,
                    VAR_HANDLE_GET_VOLATILE_INT, VAR_HANDLE_SET_VOLATILE_INT,
                    VAR_HANDLE_COMPARE_AND_SET_INT -> RuntimeHelperCategory.JDK_INTRINSIC;
            case ALLOC_OBJECT, NEW_BYTE_ARRAY, NEW_SHORT_ARRAY, NEW_CHAR_ARRAY,
                    NEW_INT_ARRAY, NEW_LONG_ARRAY, NEW_FLOAT_ARRAY, NEW_DOUBLE_ARRAY,
                    NEW_OBJECT_ARRAY -> RuntimeHelperCategory.ALLOCATION;
            case ARRAY_LENGTH_I32, ARRAY_LOAD_I8, ARRAY_STORE_I8,
                    ARRAY_LOAD_I16, ARRAY_STORE_I16, ARRAY_LOAD_U16, ARRAY_STORE_U16,
                    ARRAY_LOAD_I32, ARRAY_STORE_I32,
                    ARRAY_LOAD_I64, ARRAY_STORE_I64,
                    ARRAY_LOAD_F32, ARRAY_STORE_F32,
                    ARRAY_LOAD_F64, ARRAY_STORE_F64,
                    ARRAY_LOAD_REF, ARRAY_STORE_REF -> RuntimeHelperCategory.ARRAY_ACCESS;
            case CHECKCAST, INSTANCEOF -> RuntimeHelperCategory.TYPE_CHECK;
        };
    }
}
