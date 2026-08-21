package xyz.melodysky.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class RuntimeHelperCatalogTest {
    @Test
    void defaultCatalogContainsCoreJvmSemanticHelpers() {
        RuntimeHelperCatalog catalog = RuntimeHelperCatalog.defaultCatalog();

        assertTrue(catalog.helper(RuntimeHelperKind.NULL_CHECK).isPresent());
        assertTrue(catalog.helper(RuntimeHelperKind.ARRAY_BOUNDS_CHECK).isPresent());
        assertTrue(catalog.helper(RuntimeHelperKind.THROW).isPresent());
        assertTrue(catalog.helper(RuntimeHelperKind.RETHROW).isPresent());
        assertTrue(catalog.helper(RuntimeHelperKind.PENDING_EXCEPTION).isPresent());
        assertTrue(catalog.helper(RuntimeHelperKind.CLEAR_EXCEPTION).isPresent());
        assertTrue(catalog.helper(RuntimeHelperKind.CATCH_DISPATCH).isPresent());
        assertTrue(catalog.helper(RuntimeHelperKind.CREATE_NULL_POINTER_EXCEPTION).isPresent());
        assertTrue(catalog.helper(RuntimeHelperKind.CREATE_ARRAY_INDEX_OUT_OF_BOUNDS_EXCEPTION).isPresent());
        assertTrue(catalog.helper(RuntimeHelperKind.CREATE_ARRAY_STORE_EXCEPTION).isPresent());
        assertTrue(catalog.helper(RuntimeHelperKind.CREATE_CLASS_CAST_EXCEPTION).isPresent());
        assertTrue(catalog.helper(RuntimeHelperKind.CREATE_ARITHMETIC_EXCEPTION).isPresent());
        assertTrue(catalog.helper(RuntimeHelperKind.DIV_I32).isPresent());
        assertTrue(catalog.helper(RuntimeHelperKind.REM_I64).isPresent());
        assertTrue(catalog.helper(RuntimeHelperKind.CLASS_INIT).isPresent());
        assertTrue(catalog.helper(RuntimeHelperKind.CLASS_INIT_GUARD).isPresent());
        assertTrue(catalog.helper(RuntimeHelperKind.CLASS_INIT_BEGIN).isPresent());
        assertTrue(catalog.helper(RuntimeHelperKind.CLASS_INIT_END).isPresent());
        assertTrue(catalog.helper(RuntimeHelperKind.CLASS_INIT_FAILED).isPresent());
        assertTrue(catalog.helper(RuntimeHelperKind.CLASS_OBJECT).isPresent());
        assertTrue(catalog.helper(RuntimeHelperKind.EXCEPTION_BRIDGE).isPresent());
        assertTrue(catalog.helper(RuntimeHelperKind.MONITOR_ENTER).isPresent());
        assertTrue(catalog.helper(RuntimeHelperKind.MONITOR_EXIT).isPresent());
        assertTrue(catalog.helper(RuntimeHelperKind.MONITOR_EXIT_ON_EXCEPTION).isPresent());
        assertTrue(catalog.helper(RuntimeHelperKind.THREAD_START_HAPPENS_BEFORE).isPresent());
        assertTrue(catalog.helper(RuntimeHelperKind.THREAD_JOIN_HAPPENS_BEFORE).isPresent());
        assertTrue(catalog.helper(RuntimeHelperKind.THREAD_SLEEP).isPresent());
        assertTrue(catalog.helper(RuntimeHelperKind.FIELD_GET_STATIC_I32).isPresent());
        assertTrue(catalog.helper(RuntimeHelperKind.FIELD_PUT_FIELD_I64).isPresent());
        assertTrue(catalog.helper(RuntimeHelperKind.FIELD_GET_FIELD_REF).isPresent());
        assertTrue(catalog.helper(RuntimeHelperKind.CALL_STATIC_I32).isPresent());
        assertTrue(catalog.helper(RuntimeHelperKind.CALL_VIRTUAL_I32).isPresent());
        assertTrue(catalog.helper(RuntimeHelperKind.CALL_CONSTRUCTOR_VOID).isPresent());
        assertTrue(catalog.helper(RuntimeHelperKind.CALL_CONSTRUCTOR_VOID_I32_I32).isPresent());
        assertTrue(catalog.helper(RuntimeHelperKind.CALL_STATIC_REF).isPresent());
        assertTrue(catalog.helper(RuntimeHelperKind.I2B).isPresent());
        assertTrue(catalog.helper(RuntimeHelperKind.F2I).isPresent());
        assertTrue(catalog.helper(RuntimeHelperKind.LCMP).isPresent());
        assertTrue(catalog.helper(RuntimeHelperKind.DCMPG).isPresent());
        assertTrue(catalog.helper(RuntimeHelperKind.OBJECT_GET_CLASS).isPresent());
        assertTrue(catalog.helper(RuntimeHelperKind.STRING_LENGTH).isPresent());
        assertTrue(catalog.helper(RuntimeHelperKind.STRING_CONSTANT).isPresent());
        assertTrue(catalog.helper(RuntimeHelperKind.STRING_BUILDER_NEW).isPresent());
        assertTrue(catalog.helper(RuntimeHelperKind.STRING_BUILDER_APPEND_I32).isPresent());
        assertTrue(catalog.helper(RuntimeHelperKind.SYSTEM_ARRAYCOPY).isPresent());
        assertTrue(catalog.helper(RuntimeHelperKind.I32_BIG_ENDIAN_FRAME_NEW).isPresent());
        assertTrue(catalog.helper(RuntimeHelperKind.I32_BIG_ENDIAN_FRAME_WRITE).isPresent());
        assertTrue(catalog.helper(RuntimeHelperKind.I32_BIG_ENDIAN_FRAME_FINISH).isPresent());
        assertTrue(catalog.helper(RuntimeHelperKind.ALLOC_OBJECT).isPresent());
        assertTrue(catalog.helper(RuntimeHelperKind.NEW_BYTE_ARRAY).isPresent());
        assertTrue(catalog.helper(RuntimeHelperKind.NEW_SHORT_ARRAY).isPresent());
        assertTrue(catalog.helper(RuntimeHelperKind.NEW_CHAR_ARRAY).isPresent());
        assertTrue(catalog.helper(RuntimeHelperKind.NEW_INT_ARRAY).isPresent());
        assertTrue(catalog.helper(RuntimeHelperKind.NEW_LONG_ARRAY).isPresent());
        assertTrue(catalog.helper(RuntimeHelperKind.NEW_FLOAT_ARRAY).isPresent());
        assertTrue(catalog.helper(RuntimeHelperKind.NEW_DOUBLE_ARRAY).isPresent());
        assertTrue(catalog.helper(RuntimeHelperKind.NEW_OBJECT_ARRAY).isPresent());
        assertTrue(catalog.helper(RuntimeHelperKind.ARRAY_LENGTH_I32).isPresent());
        assertTrue(catalog.helper(RuntimeHelperKind.ARRAY_LOAD_I8).isPresent());
        assertTrue(catalog.helper(RuntimeHelperKind.ARRAY_STORE_I8).isPresent());
        assertTrue(catalog.helper(RuntimeHelperKind.ARRAY_LOAD_I16).isPresent());
        assertTrue(catalog.helper(RuntimeHelperKind.ARRAY_STORE_I16).isPresent());
        assertTrue(catalog.helper(RuntimeHelperKind.ARRAY_LOAD_U16).isPresent());
        assertTrue(catalog.helper(RuntimeHelperKind.ARRAY_STORE_U16).isPresent());
        assertTrue(catalog.helper(RuntimeHelperKind.ARRAY_LOAD_I32).isPresent());
        assertTrue(catalog.helper(RuntimeHelperKind.ARRAY_STORE_I32).isPresent());
        assertTrue(catalog.helper(RuntimeHelperKind.ARRAY_LOAD_I64).isPresent());
        assertTrue(catalog.helper(RuntimeHelperKind.ARRAY_STORE_I64).isPresent());
        assertTrue(catalog.helper(RuntimeHelperKind.ARRAY_LOAD_F32).isPresent());
        assertTrue(catalog.helper(RuntimeHelperKind.ARRAY_STORE_F32).isPresent());
        assertTrue(catalog.helper(RuntimeHelperKind.ARRAY_LOAD_F64).isPresent());
        assertTrue(catalog.helper(RuntimeHelperKind.ARRAY_STORE_F64).isPresent());
        assertTrue(catalog.helper(RuntimeHelperKind.ARRAY_LOAD_REF).isPresent());
        assertTrue(catalog.helper(RuntimeHelperKind.ARRAY_STORE_REF).isPresent());
        assertTrue(catalog.helper(RuntimeHelperKind.CHECKCAST).isPresent());
        assertTrue(catalog.helper(RuntimeHelperKind.INSTANCEOF).isPresent());
        assertTrue(catalog.helper(RuntimeHelperKind.MATH_ABS_I32).isPresent());
        assertTrue(catalog.helper(RuntimeHelperKind.INTEGER_VALUE_OF).isPresent());
        assertTrue(catalog.helper(RuntimeHelperKind.LAMBDA_NEW).isPresent());
        assertTrue(catalog.helper(RuntimeHelperKind.CLASS_FOR_NAME_STATIC).isPresent());
        assertTrue(catalog.helper(RuntimeHelperKind.GET_DECLARED_METHOD).isPresent());
        assertTrue(catalog.helper(RuntimeHelperKind.GET_DECLARED_FIELD).isPresent());
        assertTrue(catalog.helper(RuntimeHelperKind.GET_DECLARED_CONSTRUCTOR).isPresent());
        assertTrue(catalog.helper(RuntimeHelperKind.REFLECT_INVOKE).isPresent());
        assertTrue(catalog.helper(RuntimeHelperKind.REFLECT_NEW_INSTANCE).isPresent());
        assertTrue(catalog.helper(RuntimeHelperKind.METHOD_HANDLE_INVOKE_EXACT).isPresent());
        assertTrue(catalog.helper(RuntimeHelperKind.CONSTANT_DYNAMIC).isPresent());
        assertTrue(catalog.helper(RuntimeHelperKind.UNSAFE_OBJECT_FIELD_OFFSET).isPresent());
        assertTrue(catalog.helper(RuntimeHelperKind.UNSAFE_STATIC_FIELD_OFFSET).isPresent());
        assertTrue(catalog.helper(RuntimeHelperKind.UNSAFE_ARRAY_BASE_OFFSET).isPresent());
        assertTrue(catalog.helper(RuntimeHelperKind.UNSAFE_ARRAY_INDEX_SCALE).isPresent());
        assertTrue(catalog.helper(RuntimeHelperKind.UNSAFE_GET_INT).isPresent());
        assertTrue(catalog.helper(RuntimeHelperKind.UNSAFE_PUT_INT).isPresent());
        assertTrue(catalog.helper(RuntimeHelperKind.UNSAFE_COMPARE_AND_SWAP_INT).isPresent());
        assertTrue(catalog.helper(RuntimeHelperKind.UNSAFE_GET).isPresent());
        assertTrue(catalog.helper(RuntimeHelperKind.UNSAFE_PUT).isPresent());
        assertTrue(catalog.helper(RuntimeHelperKind.UNSAFE_GET_VOLATILE).isPresent());
        assertTrue(catalog.helper(RuntimeHelperKind.UNSAFE_PUT_VOLATILE).isPresent());
        assertTrue(catalog.helper(RuntimeHelperKind.UNSAFE_COMPARE_AND_SWAP).isPresent());
        assertTrue(catalog.helper(RuntimeHelperKind.UNSAFE_ALLOCATE_INSTANCE).isPresent());
        assertTrue(catalog.helper(RuntimeHelperKind.VAR_HANDLE_GET_INT).isPresent());
        assertTrue(catalog.helper(RuntimeHelperKind.VAR_HANDLE_SET_INT).isPresent());
        assertTrue(catalog.helper(RuntimeHelperKind.VAR_HANDLE_GET_VOLATILE_INT).isPresent());
        assertTrue(catalog.helper(RuntimeHelperKind.VAR_HANDLE_SET_VOLATILE_INT).isPresent());
        assertTrue(catalog.helper(RuntimeHelperKind.VAR_HANDLE_COMPARE_AND_SET_INT).isPresent());
    }

    @Test
    void referenceIdentityHelperUsesEnvAndTwoOpaqueReferenceHandles() {
        RuntimeHelper helper = RuntimeHelperCatalog.defaultCatalog()
                .helper(RuntimeHelperKind.IS_SAME_OBJECT)
                .orElseThrow();

        assertEquals("j2ll_rt_is_same_object", helper.llvmSymbol());
        assertEquals("i32", helper.llvmReturnType());
        assertEquals(java.util.List.of("jobject", "jobject"), helper.signature().parameterTypes());
        assertEquals(java.util.List.of("ptr", "ptr", "ptr"), helper.llvmParameterTypes());
    }

    @Test
    void defaultCatalogUsesJniReferenceAbiTokens() {
        RuntimeHelperCatalog catalog = RuntimeHelperCatalog.defaultCatalog();

        for (RuntimeHelper helper : catalog.helpers()) {
            assertTrue(!helper.signature().returnType().equals("ptr"), helper.kind() + " returns raw ptr ABI token");
            assertTrue(!helper.signature().parameterTypes().contains("ptr"), helper.kind() + " takes raw ptr ABI token");
        }
        RuntimeHelper classObject = catalog.helper(RuntimeHelperKind.CLASS_OBJECT).orElseThrow();
        RuntimeHelper arraycopy = catalog.helper(RuntimeHelperKind.SYSTEM_ARRAYCOPY).orElseThrow();
        RuntimeHelper pendingException = catalog.helper(RuntimeHelperKind.PENDING_EXCEPTION).orElseThrow();
        assertEquals("jclass", classObject.signature().returnType());
        assertEquals(java.util.List.of("jarray", "i32", "jarray", "i32", "i32"), arraycopy.signature().parameterTypes());
        assertEquals("jthrowable", pendingException.signature().returnType());
    }

    @Test
    void helperOrderingIsStable() {
        RuntimeHelperCatalog catalog = RuntimeHelperCatalog.defaultCatalog();

        assertEquals(
                catalog.helpers(),
                catalog.helpers().stream()
                        .sorted(java.util.Comparator
                                .comparing(RuntimeHelper::category)
                                .thenComparing(helper -> helper.kind().name()))
                        .toList());
    }

    @Test
    void duplicateHelpersAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new RuntimeHelperCatalog(java.util.List.of(
                new RuntimeHelper(RuntimeHelperKind.NULL_CHECK, "nullCheck", "j2ll_rt_null_check", "jobject", java.util.List.of("jobject")),
                new RuntimeHelper(RuntimeHelperKind.NULL_CHECK, "anotherNullCheck", "j2ll_rt_another_null_check", "jobject", java.util.List.of("jobject")))));

        assertThrows(IllegalArgumentException.class, () -> new RuntimeHelperCatalog(java.util.List.of(
                new RuntimeHelper(RuntimeHelperKind.NULL_CHECK, "nullCheck", "j2ll_rt_same", "jobject", java.util.List.of("jobject")),
                new RuntimeHelper(RuntimeHelperKind.ARRAY_BOUNDS_CHECK, "arrayBoundsCheck", "j2ll_rt_same", "void", java.util.List.of("jobject", "i32")))));
    }
}
