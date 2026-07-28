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
    void emitsStableLlvmDeclarationsForHelperAbi() {
        String declarations = new RuntimeHelperDeclarationEmitter().emit(RuntimeHelperCatalog.defaultCatalog());

        assertTrue(declarations.contains("declare ptr @j2ll_rt_null_check(ptr) ; nullCheck"));
        assertTrue(declarations.contains("declare void @j2ll_rt_array_bounds_check(ptr, i32) ; arrayBoundsCheck"));
        assertTrue(declarations.contains("declare void @j2ll_rt_throw(ptr, ptr) ; throwException"));
        assertTrue(declarations.contains("declare void @j2ll_rt_rethrow(ptr, ptr) ; rethrowException"));
        assertTrue(declarations.contains("declare ptr @j2ll_rt_pending_exception(ptr) ; pendingException"));
        assertTrue(declarations.contains("declare void @j2ll_rt_clear_exception(ptr) ; clearException"));
        assertTrue(declarations.contains("declare void @j2ll_rt_thread_sleep(ptr, i64) ; threadSleep"));
        assertTrue(declarations.contains("declare ptr @j2ll_rt_catch_dispatch(ptr, ptr) ; catchDispatch"));
        assertTrue(declarations.contains("declare ptr @j2ll_rt_create_null_pointer_exception(ptr) ; createNullPointerException"));
        assertTrue(declarations.contains("declare ptr @j2ll_rt_create_array_index_out_of_bounds_exception(ptr, i32) ; createArrayIndexOutOfBoundsException"));
        assertTrue(declarations.contains("declare ptr @j2ll_rt_create_array_store_exception(ptr) ; createArrayStoreException"));
        assertTrue(declarations.contains("declare ptr @j2ll_rt_create_class_cast_exception(ptr) ; createClassCastException"));
        assertTrue(declarations.contains("declare ptr @j2ll_rt_create_arithmetic_exception(ptr) ; createArithmeticException"));
        assertTrue(declarations.contains("declare i32 @j2ll_rt_div_i32(ptr, i32, i32) ; divI32"));
        assertTrue(declarations.contains("declare i64 @j2ll_rt_rem_i64(ptr, i64, i64) ; remI64"));
        assertTrue(declarations.contains("declare void @j2ll_rt_class_init_guard(ptr, ptr) ; classInitGuard"));
        assertTrue(declarations.contains("declare void @j2ll_rt_class_init_begin(ptr, ptr) ; classInitBegin"));
        assertTrue(declarations.contains("declare void @j2ll_rt_class_init_end(ptr, ptr) ; classInitEnd"));
        assertTrue(declarations.contains("declare void @j2ll_rt_class_init_failed(ptr, ptr, ptr) ; classInitFailed"));
        assertTrue(declarations.contains("declare ptr @j2ll_rt_class_object(ptr, i64) ; classObject"));
        assertTrue(declarations.contains("declare void @j2ll_rt_monitor_enter(ptr, ptr) ; monitorEnter"));
        assertTrue(declarations.contains("declare void @j2ll_rt_monitor_exit(ptr, ptr) ; monitorExit"));
        assertTrue(declarations.contains("declare void @j2ll_rt_monitor_exit_on_exception(ptr, ptr) ; monitorExitOnException"));
        assertTrue(declarations.contains("declare void @j2ll_rt_thread_start_happens_before(ptr) ; threadStartHappensBefore"));
        assertTrue(declarations.contains("declare void @j2ll_rt_thread_join_happens_before(ptr) ; threadJoinHappensBefore"));
        assertTrue(declarations.contains("declare i32 @j2ll_rt_field_get_static_i32(ptr, ptr, i64) ; fieldGetStaticI32"));
        assertTrue(declarations.contains("declare void @j2ll_rt_field_put_field_i64(ptr, ptr, i64, i64) ; fieldPutFieldI64"));
        assertTrue(declarations.contains("declare ptr @j2ll_rt_field_get_field_ref(ptr, ptr, i64) ; fieldGetFieldRef"));
        assertTrue(declarations.contains("declare i32 @j2ll_rt_call_static_i32(ptr, ptr, i64, ptr) ; callStaticI32"));
        assertTrue(declarations.contains("declare i32 @j2ll_rt_call_virtual_i32(ptr, ptr, i64, ptr) ; callVirtualI32"));
        assertTrue(declarations.contains("declare void @j2ll_rt_call_constructor_void(ptr, ptr, i64) ; callConstructorVoid"));
        assertTrue(declarations.contains("declare void @j2ll_rt_call_constructor_void_i32_i32(ptr, ptr, i64, i32, i32) ; callConstructorVoidI32I32"));
        assertTrue(declarations.contains("declare ptr @j2ll_rt_call_static_ref(ptr, ptr, i64, ptr) ; callStaticRef"));
        assertTrue(declarations.contains("declare i32 @j2ll_rt_i2b(i32) ; i2b"));
        assertTrue(declarations.contains("declare i32 @j2ll_rt_fcmpl(float, float) ; fcmpl"));
        assertTrue(declarations.contains("declare i32 @j2ll_rt_dcmpg(double, double) ; dcmpg"));
        assertTrue(declarations.contains("declare ptr @j2ll_rt_object_get_class(ptr, ptr) ; objectGetClass"));
        assertTrue(declarations.contains("declare i32 @j2ll_rt_string_length(ptr, ptr) ; stringLength"));
        assertTrue(declarations.contains("declare i32 @j2ll_rt_string_equals(ptr, ptr, ptr) ; stringEquals"));
        assertTrue(declarations.contains("declare i32 @j2ll_rt_string_starts_with(ptr, ptr, ptr) ; stringStartsWith"));
        assertTrue(declarations.contains("declare ptr @j2ll_rt_string_substring(ptr, ptr, i32) ; stringSubstring"));
        assertTrue(declarations.contains("declare ptr @j2ll_rt_string_constant(ptr, i64) ; stringConstant"));
        assertTrue(declarations.contains("declare ptr @j2ll_rt_string_builder_new(ptr) ; stringBuilderNew"));
        assertTrue(declarations.contains("declare ptr @j2ll_rt_string_builder_append_i32(ptr, ptr, i32) ; stringBuilderAppendI32"));
        assertTrue(declarations.contains("declare void @j2ll_rt_system_arraycopy(ptr, ptr, i32, ptr, i32, i32) ; systemArraycopy"));
        assertTrue(declarations.contains("declare ptr @j2ll_rt_alloc_object(ptr, i64) ; allocObject"));
        assertTrue(declarations.contains("declare ptr @j2ll_rt_new_byte_array(ptr, i32) ; newByteArray"));
        assertTrue(declarations.contains("declare ptr @j2ll_rt_new_short_array(ptr, i32) ; newShortArray"));
        assertTrue(declarations.contains("declare ptr @j2ll_rt_new_char_array(ptr, i32) ; newCharArray"));
        assertTrue(declarations.contains("declare ptr @j2ll_rt_new_int_array(ptr, i32) ; newIntArray"));
        assertTrue(declarations.contains("declare ptr @j2ll_rt_new_long_array(ptr, i32) ; newLongArray"));
        assertTrue(declarations.contains("declare ptr @j2ll_rt_new_float_array(ptr, i32) ; newFloatArray"));
        assertTrue(declarations.contains("declare ptr @j2ll_rt_new_double_array(ptr, i32) ; newDoubleArray"));
        assertTrue(declarations.contains("declare ptr @j2ll_rt_new_object_array(ptr, i64, i32) ; newObjectArray"));
        assertTrue(declarations.contains("declare i32 @j2ll_rt_array_length_i32(ptr, ptr) ; arrayLengthI32"));
        assertTrue(declarations.contains("declare i32 @j2ll_rt_array_load_i8(ptr, ptr, i32) ; arrayLoadI8"));
        assertTrue(declarations.contains("declare void @j2ll_rt_array_store_i8(ptr, ptr, i32, i32) ; arrayStoreI8"));
        assertTrue(declarations.contains("declare i32 @j2ll_rt_array_load_i16(ptr, ptr, i32) ; arrayLoadI16"));
        assertTrue(declarations.contains("declare void @j2ll_rt_array_store_i16(ptr, ptr, i32, i32) ; arrayStoreI16"));
        assertTrue(declarations.contains("declare i32 @j2ll_rt_array_load_u16(ptr, ptr, i32) ; arrayLoadU16"));
        assertTrue(declarations.contains("declare void @j2ll_rt_array_store_u16(ptr, ptr, i32, i32) ; arrayStoreU16"));
        assertTrue(declarations.contains("declare i32 @j2ll_rt_array_load_i32(ptr, ptr, i32) ; arrayLoadI32"));
        assertTrue(declarations.contains("declare void @j2ll_rt_array_store_i32(ptr, ptr, i32, i32) ; arrayStoreI32"));
        assertTrue(declarations.contains("declare i64 @j2ll_rt_array_load_i64(ptr, ptr, i32) ; arrayLoadI64"));
        assertTrue(declarations.contains("declare void @j2ll_rt_array_store_i64(ptr, ptr, i32, i64) ; arrayStoreI64"));
        assertTrue(declarations.contains("declare float @j2ll_rt_array_load_f32(ptr, ptr, i32) ; arrayLoadF32"));
        assertTrue(declarations.contains("declare void @j2ll_rt_array_store_f32(ptr, ptr, i32, float) ; arrayStoreF32"));
        assertTrue(declarations.contains("declare double @j2ll_rt_array_load_f64(ptr, ptr, i32) ; arrayLoadF64"));
        assertTrue(declarations.contains("declare void @j2ll_rt_array_store_f64(ptr, ptr, i32, double) ; arrayStoreF64"));
        assertTrue(declarations.contains("declare ptr @j2ll_rt_array_load_ref(ptr, ptr, i32) ; arrayLoadRef"));
        assertTrue(declarations.contains("declare void @j2ll_rt_array_store_ref(ptr, ptr, i32, ptr) ; arrayStoreRef"));
        assertTrue(declarations.contains("declare ptr @j2ll_rt_checkcast(ptr, ptr, i64) ; checkcast"));
        assertTrue(declarations.contains("declare i32 @j2ll_rt_instanceof(ptr, ptr, i64) ; instanceof"));
        assertTrue(declarations.contains("declare i32 @j2ll_rt_math_abs_i32(i32) ; mathAbsI32"));
        assertTrue(declarations.contains("declare float @j2ll_rt_math_abs_f32(float) ; mathAbsF32"));
        assertTrue(declarations.contains("declare ptr @j2ll_rt_integer_value_of(ptr, i32) ; integerValueOf"));
        assertTrue(declarations.contains("declare double @j2ll_rt_double_double_value(ptr, ptr) ; doubleDoubleValue"));
        assertTrue(declarations.contains("declare ptr @j2ll_rt_objects_require_non_null(ptr, ptr) ; objectsRequireNonNull"));
        assertTrue(declarations.contains("declare ptr @j2ll_rt_lambda_new(ptr, i64, ptr) ; lambdaNew"));
        assertTrue(declarations.contains("declare ptr @j2ll_rt_class_for_name_static(ptr, i64, i32) ; classForNameStatic"));
        assertTrue(declarations.contains("declare ptr @j2ll_rt_get_declared_method(ptr, i64) ; getDeclaredMethod"));
        assertTrue(declarations.contains("declare ptr @j2ll_rt_get_declared_field(ptr, i64) ; getDeclaredField"));
        assertTrue(declarations.contains("declare ptr @j2ll_rt_get_declared_constructor(ptr, i64) ; getDeclaredConstructor"));
        assertTrue(declarations.contains("declare ptr @j2ll_rt_reflect_invoke(ptr, ptr, ptr, ptr) ; reflectInvoke"));
        assertTrue(declarations.contains("declare ptr @j2ll_rt_reflect_new_instance(ptr, ptr, ptr) ; reflectNewInstance"));
        assertTrue(declarations.contains("declare ptr @j2ll_rt_reflect_field_get(ptr, ptr, ptr) ; reflectFieldGet"));
        assertTrue(declarations.contains("declare void @j2ll_rt_reflect_field_set(ptr, ptr, ptr, ptr) ; reflectFieldSet"));
        assertTrue(declarations.contains("declare i32 @j2ll_rt_reflect_field_get_int(ptr, ptr, ptr) ; reflectFieldGetInt"));
        assertTrue(declarations.contains("declare void @j2ll_rt_reflect_field_set_int(ptr, ptr, ptr, i32) ; reflectFieldSetInt"));
        assertTrue(declarations.contains("declare i32 @j2ll_rt_reflect_field_get_boolean(ptr, ptr, ptr) ; reflectFieldGetBoolean"));
        assertTrue(declarations.contains("declare void @j2ll_rt_reflect_field_set_boolean(ptr, ptr, ptr, i32) ; reflectFieldSetBoolean"));
        assertTrue(declarations.contains("declare i64 @j2ll_rt_reflect_field_get_long(ptr, ptr, ptr) ; reflectFieldGetLong"));
        assertTrue(declarations.contains("declare void @j2ll_rt_reflect_field_set_long(ptr, ptr, ptr, i64) ; reflectFieldSetLong"));
        assertTrue(declarations.contains("declare double @j2ll_rt_reflect_field_get_double(ptr, ptr, ptr) ; reflectFieldGetDouble"));
        assertTrue(declarations.contains("declare void @j2ll_rt_reflect_field_set_double(ptr, ptr, ptr, double) ; reflectFieldSetDouble"));
        assertTrue(declarations.contains("declare ptr @j2ll_rt_method_handle_invoke_exact(ptr, ptr, ptr) ; methodHandleInvokeExact"));
        assertTrue(declarations.contains("declare ptr @j2ll_rt_constant_dynamic(ptr, i64) ; constantDynamic"));
        assertTrue(declarations.contains("declare i64 @j2ll_rt_unsafe_object_field_offset(ptr, ptr) ; unsafeObjectFieldOffset"));
        assertTrue(declarations.contains("declare i32 @j2ll_rt_unsafe_array_base_offset(ptr, ptr) ; unsafeArrayBaseOffset"));
        assertTrue(declarations.contains("declare i32 @j2ll_rt_unsafe_get_int(ptr, ptr, i64) ; unsafeGetInt"));
        assertTrue(declarations.contains("declare void @j2ll_rt_unsafe_put_int(ptr, ptr, i64, i32) ; unsafePutInt"));
        assertTrue(declarations.contains("declare i32 @j2ll_rt_unsafe_compare_and_swap_int(ptr, ptr, i64, i32, i32) ; unsafeCompareAndSwapInt"));
        assertTrue(declarations.contains("declare ptr @j2ll_rt_unsafe_get(ptr, ptr, ptr) ; unsafeGet"));
        assertTrue(declarations.contains("declare void @j2ll_rt_unsafe_put(ptr, ptr, ptr, i64, ptr) ; unsafePut"));
        assertTrue(declarations.contains("declare ptr @j2ll_rt_unsafe_get_volatile(ptr, ptr, ptr) ; unsafeGetVolatile"));
        assertTrue(declarations.contains("declare i32 @j2ll_rt_unsafe_compare_and_swap(ptr, ptr, ptr, i64, ptr, ptr) ; unsafeCompareAndSwap"));
        assertTrue(declarations.contains("declare i32 @j2ll_rt_var_handle_get_int(ptr, ptr, ptr) ; varHandleGetInt"));
        assertTrue(declarations.contains("declare void @j2ll_rt_var_handle_set_int(ptr, ptr, ptr, i32) ; varHandleSetInt"));
        assertTrue(declarations.contains("declare i32 @j2ll_rt_var_handle_get_volatile_int(ptr, ptr, ptr) ; varHandleGetVolatileInt"));
        assertTrue(declarations.contains("declare void @j2ll_rt_var_handle_set_volatile_int(ptr, ptr, ptr, i32) ; varHandleSetVolatileInt"));
        assertTrue(declarations.contains("declare i32 @j2ll_rt_var_handle_compare_and_set_int(ptr, ptr, ptr, i32, i32) ; varHandleCompareAndSetInt"));
        assertEquals(declarations, new RuntimeHelperDeclarationEmitter().emit(RuntimeHelperCatalog.defaultCatalog()));
    }

    @Test
    void emitsRuntimeHelperHeaderGolden() {
        RuntimeHelperCatalog catalog = new RuntimeHelperCatalog(java.util.List.of(
                new RuntimeHelper(RuntimeHelperKind.MONITOR_ENTER, "monitorEnter", "j2ll_rt_monitor_enter", "void", java.util.List.of("jobject")),
                new RuntimeHelper(RuntimeHelperKind.NULL_CHECK, "nullCheck", "j2ll_rt_null_check", "jobject", java.util.List.of("jobject"))));

        String header = new RuntimeStubGenerator().emitHeader(catalog);

        assertEquals("""
                #ifndef J2LL_RUNTIME_HELPERS_H
                #define J2LL_RUNTIME_HELPERS_H

                #include <jni.h>
                #include <stdint.h>

                /* Java-visible references in this ABI are JNI handles owned by the JVM. */
                /* Helpers must allocate Java objects through JNIEnv/runtime helpers, never native heap. */

                /* monitor */
                void j2ll_rt_monitor_enter(JNIEnv* env, jobject arg0);
                /* array-type-null-check */
                jobject j2ll_rt_null_check(JNIEnv* env, jobject arg0);

                #endif
                """, header);
    }

    @Test
    void emitsRuntimeHelperCSourceGolden() {
        RuntimeHelperCatalog catalog = new RuntimeHelperCatalog(java.util.List.of(
                new RuntimeHelper(RuntimeHelperKind.CLASS_OBJECT, "classObject", "j2ll_rt_class_object", "jclass", java.util.List.of("i64")),
                new RuntimeHelper(RuntimeHelperKind.CLASS_INIT_FAILED, "classInitFailed", "j2ll_rt_class_init_failed", "void", java.util.List.of("jclass", "jthrowable"))));

        String source = new RuntimeStubGenerator().emitCSource(catalog);

        assertEquals("""
                #include "runtime-helpers.h"

                /* class-init */
                void j2ll_rt_class_init_failed(JNIEnv* env, jclass arg0, jthrowable arg1) {
                    (void)env;
                    /* TODO: PushLocalFrame/PopLocalFrame according to helper local-frame policy. */
                    /* TODO: Check (*env)->ExceptionCheck(env) before returning or chaining helpers. */
                    (void)arg0;
                    (void)arg1;
                }

                jclass j2ll_rt_class_object(JNIEnv* env, int64_t arg0) {
                    (void)env;
                    /* TODO: PushLocalFrame/PopLocalFrame according to helper local-frame policy. */
                    /* TODO: Check (*env)->ExceptionCheck(env) before returning or chaining helpers. */
                    (void)arg0;
                    return 0;
                }

                """, source);
    }

    @Test
    void arithmeticExceptionHelperSkeletonUsesJniEnvAndPendingExceptionPolicy() {
        RuntimeHelper helper = RuntimeHelperCatalog.defaultCatalog()
                .helper(RuntimeHelperKind.DIV_I32)
                .orElseThrow();
        RuntimeHelperCatalog catalog = new RuntimeHelperCatalog(java.util.List.of(helper));
        RuntimeStubGenerator generator = new RuntimeStubGenerator();

        String header = generator.emitHeader(catalog);
        String source = generator.emitCSource(catalog);

        assertTrue(header.contains("int32_t j2ll_rt_div_i32(JNIEnv* env, int32_t arg0, int32_t arg1);"));
        assertTrue(source.contains("int32_t j2ll_rt_div_i32(JNIEnv* env, int32_t arg0, int32_t arg1)"));
        assertTrue(source.contains("Check (*env)->ExceptionCheck(env)"));
        assertTrue(source.contains("return 0;"));
    }

    @Test
    void backendDeclarationsAndRuntimeStubsUseCatalogSignatures() {
        RuntimeHelperCatalog catalog = RuntimeHelperCatalog.defaultCatalog();
        String declarations = new RuntimeHelperDeclarationEmitter().emit(catalog);
        RuntimeStubGenerator generator = new RuntimeStubGenerator();

        for (RuntimeHelper helper : catalog.helpers()) {
            assertTrue(declarations.contains("@" + helper.llvmSymbol() + "("
                    + String.join(", ", helper.llvmParameterTypes()) + ")"));
            assertTrue(generator.emitHeader(new RuntimeHelperCatalog(java.util.List.of(helper)))
                    .contains(generator.prototype(helper) + ";"));
            assertTrue(generator.prototype(helper).contains("JNIEnv* env"));
        }
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
    void allocationHelperSkeletonUsesJniAllocationAbi() {
        RuntimeHelperCatalog catalog = new RuntimeHelperCatalog(java.util.List.of(
                RuntimeHelperCatalog.defaultCatalog().helper(RuntimeHelperKind.ALLOC_OBJECT).orElseThrow(),
                RuntimeHelperCatalog.defaultCatalog().helper(RuntimeHelperKind.NEW_LONG_ARRAY).orElseThrow(),
                RuntimeHelperCatalog.defaultCatalog().helper(RuntimeHelperKind.NEW_DOUBLE_ARRAY).orElseThrow(),
                RuntimeHelperCatalog.defaultCatalog().helper(RuntimeHelperKind.NEW_INT_ARRAY).orElseThrow(),
                RuntimeHelperCatalog.defaultCatalog().helper(RuntimeHelperKind.NEW_OBJECT_ARRAY).orElseThrow()));
        RuntimeStubGenerator generator = new RuntimeStubGenerator();

        String header = generator.emitHeader(catalog);
        String source = generator.emitCSource(catalog);

        assertTrue(header.contains("jobject j2ll_rt_alloc_object(JNIEnv* env, int64_t arg0);"));
        assertTrue(header.contains("jarray j2ll_rt_new_int_array(JNIEnv* env, int32_t arg0);"));
        assertTrue(header.contains("jarray j2ll_rt_new_long_array(JNIEnv* env, int32_t arg0);"));
        assertTrue(header.contains("jarray j2ll_rt_new_double_array(JNIEnv* env, int32_t arg0);"));
        assertTrue(header.contains("jarray j2ll_rt_new_object_array(JNIEnv* env, int64_t arg0, int32_t arg1);"));
        assertTrue(source.contains("PushLocalFrame/PopLocalFrame"));
        assertTrue(!source.contains("malloc("));
        assertTrue(!source.contains("alloca("));
    }

    @Test
    void arrayHelperSkeletonUsesJniArrayAbi() {
        RuntimeHelperCatalog catalog = new RuntimeHelperCatalog(java.util.List.of(
                RuntimeHelperCatalog.defaultCatalog().helper(RuntimeHelperKind.ARRAY_LENGTH_I32).orElseThrow(),
                RuntimeHelperCatalog.defaultCatalog().helper(RuntimeHelperKind.ARRAY_LOAD_I8).orElseThrow(),
                RuntimeHelperCatalog.defaultCatalog().helper(RuntimeHelperKind.ARRAY_STORE_I8).orElseThrow(),
                RuntimeHelperCatalog.defaultCatalog().helper(RuntimeHelperKind.ARRAY_LOAD_I64).orElseThrow(),
                RuntimeHelperCatalog.defaultCatalog().helper(RuntimeHelperKind.ARRAY_STORE_I64).orElseThrow(),
                RuntimeHelperCatalog.defaultCatalog().helper(RuntimeHelperKind.ARRAY_LOAD_F64).orElseThrow(),
                RuntimeHelperCatalog.defaultCatalog().helper(RuntimeHelperKind.ARRAY_STORE_F64).orElseThrow(),
                RuntimeHelperCatalog.defaultCatalog().helper(RuntimeHelperKind.ARRAY_LOAD_REF).orElseThrow(),
                RuntimeHelperCatalog.defaultCatalog().helper(RuntimeHelperKind.ARRAY_STORE_REF).orElseThrow()));
        RuntimeStubGenerator generator = new RuntimeStubGenerator();

        String header = generator.emitHeader(catalog);
        String source = generator.emitCSource(catalog);

        assertTrue(header.contains("int32_t j2ll_rt_array_load_i8(JNIEnv* env, jarray arg0, int32_t arg1);"));
        assertTrue(header.contains("int64_t j2ll_rt_array_load_i64(JNIEnv* env, jarray arg0, int32_t arg1);"));
        assertTrue(header.contains("void j2ll_rt_array_store_f64(JNIEnv* env, jarray arg0, int32_t arg1, double arg2);"));
        assertTrue(header.contains("void j2ll_rt_array_store_ref(JNIEnv* env, jarray arg0, int32_t arg1, jobject arg2);"));
        assertTrue(source.contains("Check (*env)->ExceptionCheck(env)"));
        assertTrue(!source.contains("malloc("));
        assertTrue(!source.contains("alloca("));
    }

    @Test
    void typeAndConstructorHelpersUseJniHandleAbi() {
        RuntimeHelperCatalog catalog = new RuntimeHelperCatalog(java.util.List.of(
                RuntimeHelperCatalog.defaultCatalog().helper(RuntimeHelperKind.CHECKCAST).orElseThrow(),
                RuntimeHelperCatalog.defaultCatalog().helper(RuntimeHelperKind.INSTANCEOF).orElseThrow(),
                RuntimeHelperCatalog.defaultCatalog().helper(RuntimeHelperKind.CALL_CONSTRUCTOR_VOID).orElseThrow(),
                RuntimeHelperCatalog.defaultCatalog().helper(RuntimeHelperKind.CALL_CONSTRUCTOR_VOID_I32_I32).orElseThrow()));
        RuntimeStubGenerator generator = new RuntimeStubGenerator();

        String header = generator.emitHeader(catalog);
        String source = generator.emitCSource(catalog);

        assertTrue(header.contains("jobject j2ll_rt_checkcast(JNIEnv* env, jobject arg0, int64_t arg1);"));
        assertTrue(header.contains("int32_t j2ll_rt_instanceof(JNIEnv* env, jobject arg0, int64_t arg1);"));
        assertTrue(header.contains("void j2ll_rt_call_constructor_void(JNIEnv* env, jobject arg0, int64_t arg1);"));
        assertTrue(header.contains("void j2ll_rt_call_constructor_void_i32_i32(JNIEnv* env, jobject arg0, int64_t arg1, int32_t arg2, int32_t arg3);"));
        assertTrue(source.contains("Check (*env)->ExceptionCheck(env)"));
        assertTrue(!source.contains("malloc(sizeof(jobject"));
        assertTrue(!source.contains("alloca("));
    }

    @Test
    void helperOrderingIsStable() {
        RuntimeHelperCatalog catalog = RuntimeHelperCatalog.defaultCatalog();
        RuntimeStubGenerator generator = new RuntimeStubGenerator();

        assertEquals(generator.emitHeader(catalog), generator.emitHeader(catalog));
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
