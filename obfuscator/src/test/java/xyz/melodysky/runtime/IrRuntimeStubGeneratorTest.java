package xyz.melodysky.runtime;

import org.junit.jupiter.api.Test;
import xyz.melodysky.backend.llvm.JniMangler;
import xyz.melodysky.packaging.NativeRegistrationPlan;

import static org.junit.jupiter.api.Assertions.assertTrue;

public class IrRuntimeStubGeneratorTest {

    @Test
    public void testGeneratesStubDefinitionsFromLlvmDeclarations() {
        String llvm = """
                declare i32 @"ir_rt_call__static__sample_s_MathOps__mix__int__int__int"(i32, i32)
                declare ptr @"ir_rt_new__sample_s_Worker"()
                declare ptr @"ir_rt_ldc_class__sample_s_Mode"()
                declare ptr @"ir_rt_concat__01202d3e2070617373__java_s_lang_s_String"(ptr)
                declare ptr @"ir_rt_concat__f09f8eb520455252203e2001__java_s_lang_s_String"(ptr)
                declare ptr @"ir_rt_ldc_string__f09f8eb520455252"()
                declare ptr @"ir_rt_sobf__1__0000002a__00112233445566778899aabb__000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f__1f1e1d1c1b1a191817161514131211100f0e0d0c0b0a09080706050403020100__cafe"()
                declare ptr @"ir_rt_sobf__0__0000002c__00112233445566778899aabb__000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f__1f1e1d1c1b1a191817161514131211100f0e0d0c0b0a09080706050403020100__"()
                declare ptr @"ir_rt_sobf_concat__0000002b__00112233445566778899aabb__000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f__1f1e1d1c1b1a191817161514131211100f0e0d0c0b0a09080706050403020100__deadbeef__java_s_lang_s_String"(ptr)
                declare ptr @"ir_rt_lambda__java_s_util_s_function_s_Function__apply__284c6a6176612f6c616e672f4f626a6563743b294c6a6176612f6c616e672f4f626a6563743b__sample_s_CallSite__sample_s_Helpers__up__284c6a6176612f6c616e672f537472696e673b294c6a6176612f6c616e672f537472696e673b__284c6a6176612f6c616e672f537472696e673b294c6a6176612f6c616e672f537472696e673b__static"()
                declare ptr @"ir_rt_lambda__java_s_lang_s_Runnable__run__282956__sample_s_Factory__sample_s_Worker__tick__282956__282956__special__sample_s_Worker"(ptr)
                declare ptr @"ir_rt_lambda__java_s_util_s_function_s_Supplier__get__28294c6a6176612f6c616e672f4f626a6563743b__sample_s_Factory__sample_s_Widget___init___282956__28294c73616d706c652f5769646765743b__constructor"()
                declare i1 @"ir_rt_instanceof__sample_s_Thing"(ptr)
                declare i32 @"ir_rt_type_switch__sample_s_Value__sample_s_Add"(ptr, i32)
                declare i32 @"ir_rt_type_switch__enum:sample_s_Mode:LEFT"(ptr, i32)
                declare ptr @"ir_rt_record__sample_s_Point__toString__6c6566743b6e616d65__left__int__name__java_s_lang_s_String"(ptr)
                declare ptr @"ir_rt_current_exception"()
                declare i1 @"ir_rt_exception_pending"()
                declare i32 @"ir_rt_fcmpl"(float, float)
                declare i32 @"ir_rt_dcmpg"(double, double)
                declare i1 @"ir_rt_ref_eq"(ptr, ptr)
                declare ptr @"ir_rt_new_init__java_s_net_s_URI__java_s_lang_s_String"(ptr)
                declare void @"ir_rt_call__special__java_s_lang_s_Enum___init___java_s_lang_s_String__int__void"(ptr, ptr, i32)
                declare void @"ir_rt_call__virtual__java_s_lang_s_invoke_s_MethodHandle__invokeExact__java_s_lang_s_Object__bench_s_FeatureScenarios_d_BenchEvent__void"(ptr, ptr, ptr)
                declare ptr @"ir_rt_new_array__float0lb00rb0"(i32)
                declare ptr @"ir_rt_multi_new_array__int0lb00rb00lb00rb0"(i32, i32)
                declare float @"ir_rt_array_load__float0lb00rb0"(ptr, i32)
                declare void @"ir_rt_array_store__char0lb00rb0"(ptr, i32, i16)
                declare ptr @"ir_rt_call__virtual__0lb0Lbench_s_FeatureScenarios_d_BenchLevel___clone__java_s_lang_s_Object"(ptr)
                declare void @"ir_rt_monitor_enter"(ptr)
                declare void @"ir_rt_put_static__sample_s_Holder__VALUE__int"(i32)
                declare i32 @"ir_rt_get_field__bench_s_FeatureScenarios_d_Pair__left__int"(ptr)
                declare ptr @"ir_rt_concat__c2a73754696d653a20c2a733016820016d696e200173__long__long__long"(i64, i64, i64)
                ; helper-meta ir_rth_deadbeefcafebabe00112233 = ir_rt_call__static__sample_s_Shadow__mix__int__int__int
                declare i32 @"ir_rth_deadbeefcafebabe00112233"(i32, i32)
                """;

        String stubs = new IrRuntimeStubGenerator().generate(
                llvm,
                new NativeRegistrationPlan(java.util.List.of(
                        new NativeRegistrationPlan.ClassRegistration(
                                0,
                                "sample/MathOps",
                                java.util.List.of(
                                        new NativeRegistrationPlan.MethodRegistration(
                                                "mix",
                                                "(II)I",
                                                JniMangler.nativeBridgeName("sample/MathOps", "mix", "(II)I")
                                        )
                                )
                        )
                )),
                "native0/Loader"
        );

        assertTrue(stubs.contains("#define IR_NOINLINE"));
        assertTrue(stubs.contains("#include <jni.h>"));
        assertTrue(stubs.contains("#include <stdlib.h>"));
        assertTrue(stubs.contains("extern _Thread_local void* " + JniMangler.symbolPrefix()));
        assertTrue(stubs.contains("static JNIEnv* " + JniMangler.symbolPrefix()));
        assertTrue(stubs.contains("jobject object = (*env)->AllocObject(env, clazz);"));
        assertTrue(stubs.contains("static const jchar empty_chars[1] = {0};"));
        assertTrue(stubs.contains("jstring result = (*env)->NewString(env, chars, (jsize)utf16_length);"));
        assertTrue(!stubs.contains("java/nio/charset/StandardCharsets"));
        assertTrue(stubs.contains("static jobject cached = NULL;"));
        assertTrue(stubs.contains("const size_t cipher_len = 0;"));
        assertTrue(stubs.contains("switch (placeholder_index)"));
        assertTrue(stubs.contains("static jobject cachedLambdaFactory = NULL;"));
        assertTrue(stubs.contains("GetIntField(env, receiver, field0)"));
        assertTrue(stubs.contains("GetObjectField(env, receiver, field1)"));
        assertTrue(stubs.contains("0x6c, 0x65, 0x66, 0x74, 0x3d"));
        assertTrue(stubs.contains("0x6e, 0x61, 0x6d, 0x65, 0x3d"));
        assertTrue(stubs.contains("jmethodID metafactory = " + JniMangler.opaqueSymbol("runtime-internal|get-method-id-obf", 24) + "(env, lambdaMetafactoryClass, 1"));
        assertTrue(stubs.contains("jmethodID findConstructor = " + JniMangler.opaqueSymbol("runtime-internal|get-method-id-obf", 24) + "(env, lookupClass, 0"));
        assertTrue(stubs.contains("jmethodID findStatic = " + JniMangler.opaqueSymbol("runtime-internal|get-method-id-obf", 24) + "(env, lookupClass, 0"));
        assertTrue(stubs.contains("jmethodID findSpecial = " + JniMangler.opaqueSymbol("runtime-internal|get-method-id-obf", 24) + "(env, lookupClass, 0"));
        assertTrue(stubs.contains("CallObjectMethod(env, callerLookup, findConstructor, targetClass, implMethodType)"));
        assertTrue(stubs.contains("cachedLambdaFactory = (*env)->NewGlobalRef(env, lambdaFactory);"));
        assertTrue(stubs.contains("return (uint8_t)((*env)->IsInstanceOf(env, value, targetClass) ? 1 : 0);"));
        assertTrue(stubs.contains("if ((*env)->IsInstanceOf(env, value, caseClass)) return 1;"));
        assertTrue(stubs.contains("(*env)->IsSameObject(env, value, caseValue)"));
        assertTrue(stubs.contains("jthrowable pending = (*env)->ExceptionOccurred(env);"));
        assertTrue(stubs.contains("return (uint8_t)((*env)->ExceptionCheck(env) ? 1 : 0);"));
        assertTrue(stubs.contains("if (arg0 != arg0 || arg1 != arg1) return -1;"));
        assertTrue(stubs.contains("if (arg0 != arg0 || arg1 != arg1) return 1;"));
        assertTrue(stubs.contains("(*env)->IsSameObject(env, (jobject)arg0, (jobject)arg1)"));
        assertTrue(stubs.contains("jobject object = (*env)->NewObjectA(env, clazz, method, args);"));
        assertTrue(!stubs.contains("(L_java/lang/String;I)V"));
        assertTrue(stubs.contains("jmethodID invokeWithArguments = " + JniMangler.opaqueSymbol("runtime-internal|get-method-id-obf", 24) + "(env, methodHandleClass, 0"));
        assertTrue(stubs.contains("jobjectArray argsArray = (*env)->NewObjectArray(env, 2, objectClass, NULL);"));
        assertTrue(stubs.contains("return (void*)(*env)->NewFloatArray(env, (jsize)arg0);"));
        assertTrue(stubs.contains("jclass reflectArrayClass = " + JniMangler.opaqueSymbol("runtime-internal|find-class-obf", 24) + "(env, reflectArrayClass_class_name"));
        assertTrue(stubs.contains("jmethodID newInstance = " + JniMangler.opaqueSymbol("runtime-internal|get-method-id-obf", 24) + "(env, reflectArrayClass, 1"));
        assertTrue(stubs.contains("jint dims[2];"));
        assertTrue(stubs.contains("jintArray dimsArray = (*env)->NewIntArray(env, 2);"));
        assertTrue(stubs.contains("(*env)->GetFloatArrayRegion(env, (jfloatArray)arg0, (jsize)arg1, 1, &value);"));
        assertTrue(stubs.contains("(*env)->SetCharArrayRegion(env, (jcharArray)arg0, (jsize)arg1, 1, &value);"));
        assertTrue(stubs.contains("(*env)->MonitorEnter(env, (jobject)arg0);"));
        assertTrue(stubs.contains("jmethodID appendLong = " + JniMangler.opaqueSymbol("runtime-internal|get-method-id-obf", 24) + "(env, builderClass, 0"));
        assertTrue(stubs.contains("(*env)->CallObjectMethod(env, builder, appendLong, (jlong)arg0);"));
        assertTrue(stubs.contains("(*env)->CallObjectMethod(env, builder, appendLong, (jlong)arg1);"));
        assertTrue(stubs.contains("(*env)->CallObjectMethod(env, builder, appendLong, (jlong)arg2);"));
        assertTrue(stubs.contains(JniMangler.symbolPrefix()));
        assertTrue(!stubs.contains("static char* ir_rt_decode_meta_cstr"));
        assertTrue(!stubs.contains("static jclass ir_rt_find_class_obf"));
        assertTrue(!stubs.contains("static jstring ir_rt_new_string_utf_obf"));
        assertTrue(!stubs.contains("static jmethodID ir_rt_get_method_id_obf"));
        assertTrue(!stubs.contains("static jfieldID ir_rt_get_field_id_obf"));
        assertTrue(!stubs.contains("static uint32_t ir_rt_load32_le"));
        assertTrue(!stubs.contains("static uint32_t ir_rt_rotl32"));
        assertTrue(!stubs.contains("static void ir_rt_store32_le"));
        assertTrue(!stubs.contains("static void ir_rt_chacha20_block"));
        assertTrue(!stubs.contains("static void ir_rt_chacha20_xor"));
        assertTrue(!stubs.contains("void ir_rt_throw(void* arg0)"));
        assertTrue(!stubs.contains("jclass callerClass = ir_rt_find_class_obf"));
        assertTrue(!stubs.contains("jmethodID getter0 = ir_rt_get_method_id_obf"));
        assertTrue(!stubs.contains("methods[0].name = ir_rt_decode_meta_cstr"));
        assertTrue(!stubs.contains("methods[0].signature = ir_rt_decode_meta_cstr"));
        assertTrue(!stubs.contains("jclass loader_class = ir_rt_find_class_obf"));
        assertTrue(stubs.contains("== 0u) { return NULL; }") || stubs.contains("== 0u) { return 0; }") || stubs.contains("== 0u) { return; }"));
        String bridgeName = JniMangler.nativeBridgeName("sample/MathOps", "mix", "(II)I");
        assertTrue(stubs.contains("extern void " + bridgeName + "(void);"));
        assertTrue(stubs.contains("JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved)"));
        assertTrue(!stubs.contains("FindClass(env, \"sample/CallSite\")"));
        assertTrue(!stubs.contains("FindClass(env, \"sample/Shadow\")"));
        assertTrue(!stubs.contains("FindClass(env, \"native0/Loader\")"));
        assertTrue(!stubs.contains("GetStaticFieldID(env, caseClass, \"LEFT\", \"Lsample/Mode;\")"));
        assertTrue(stubs.contains("case 0:\n            " + JniMangler.opaqueSymbol("runtime|register-class|0", 24) + "(env, clazz);"));
        assertTrue(stubs.contains("static IR_NOINLINE void " + JniMangler.opaqueSymbol("runtime|register-class|0", 24) + "(JNIEnv* env, jclass clazz) {"));
        assertTrue(stubs.contains("static IR_NOINLINE void JNICALL " + JniMangler.opaqueSymbol("runtime-internal|register-natives-for-class", 24) + "(JNIEnv* env, jclass loader_class, jint index, jclass clazz) {"));
        assertTrue(stubs.contains("static const " + JniMangler.opaqueSymbol("runtime|register-entry", 20) + " entries[] = {"));
        assertTrue(stubs.contains("JNINativeMethod* methods = (JNINativeMethod*)calloc((size_t)method_count, sizeof(JNINativeMethod));"));
    }
}
