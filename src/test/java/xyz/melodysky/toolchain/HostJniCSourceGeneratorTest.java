package xyz.melodysky.toolchain;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import xyz.melodysky.frontend.cfg.MethodCfgBuilder;
import xyz.melodysky.frontend.classfile.AsmClassParser;
import xyz.melodysky.frontend.classfile.ClassFileEntry;
import xyz.melodysky.frontend.classfile.ParsedClass;
import xyz.melodysky.frontend.classfile.ParsedMethod;
import xyz.melodysky.ir.model.IrMethod;
import xyz.melodysky.ir.ssa.BytecodeToSsaLowerer;
import xyz.melodysky.packaging.MethodRewriteDecision;
import xyz.melodysky.packaging.MethodRewritePlanner;
import xyz.melodysky.packaging.NativeRegistrationPlan;
import xyz.melodysky.packaging.NativeRegistrationPlanner;
import xyz.melodysky.packaging.RuntimeLoaderPlan;
import xyz.melodysky.testsupport.AsmFixtureBuilder;

class HostJniCSourceGeneratorTest {
    @Test
    void runtimeHelperFamiliesKeepDependencyOrder() {
        ParsedClass parsedClass = parse(
                "pkg/StaticFields.class",
                AsmFixtureBuilder.classWithStaticFieldRead("pkg/StaticFields"));
        MethodRewriteDecision decision = decision(parsedClass, "getValue");
        NativeRegistrationPlan registrationPlan = new NativeRegistrationPlanner().plan(List.of(decision));
        NativeImplementationPlan implementationPlan = new NativeImplementationPlanner().plan(
                registrationPlan,
                List.of(decision),
                Map.of(decision.method().methodKey(), irMethod(parsedClass, "getValue")));

        String source = new HostJniCSourceGenerator().generate(implementationPlan);
        List<String> orderedMarkers = List.of(
                "static void j2ll_throw_new",
                "static const j2ll_class_entry j2ll_class_table[]",
                "jclass j2ll_rt_class_object",
                "int32_t j2ll_rt_div_i32",
                "static jobject j2ll_call_static_box",
                "void j2ll_rt_monitor_enter",
                "int32_t j2ll_rt_array_length_i32",
                "jobject j2ll_rt_checkcast",
                "int32_t j2ll_rt_string_length",
                "jobject j2ll_rt_lambda_new",
                "static jobject j2ll_var_handle_method_handle",
                "static const j2ll_reflection_method_entry j2ll_reflection_method_table[]",
                "static const j2ll_method_entry j2ll_method_table[]",
                "static const j2ll_field_entry j2ll_field_table[]");

        for (int index = 1; index < orderedMarkers.size(); index++) {
            assertAppearsBefore(source, orderedMarkers.get(index - 1), orderedMarkers.get(index));
        }
        assertFalse(source.contains("j2ll_try_define_hidden_fallback"));
        assertFalse(source.contains("j2ll_verify_sha256_hex"));
        assertFalse(source.contains("defineHiddenFallback"));
    }

    @Test
    void emitsFallbackOnlySupportAgainstThePlannedLoaderName() {
        ParsedClass parsedClass = parse(
                "pkg/JdkFallback.class",
                AsmFixtureBuilder.classWithUnsupportedJdkStringCall("pkg/JdkFallback"));
        MethodRewriteDecision decision = decision(parsedClass, "substring");
        NativeRegistrationPlan registrationPlan =
                new NativeRegistrationPlanner().plan(List.of(decision));
        NativeImplementationPlan implementationPlan = new NativeImplementationPlanner().plan(
                registrationPlan,
                List.of(decision),
                Map.of(),
                Set.of(decision.method().methodKey()));
        RuntimeLoaderPlan loaderPlan =
                RuntimeLoaderPlan.create("xyz/Melody/natives", true);

        String source = new HostJniCSourceGenerator().generate(
                implementationPlan,
                loaderPlan);
        String fallbackSupport =
                HostJniFallbackRuntimeSource.fallbackHelperSource(loaderPlan.internalName());

        assertTrue(implementationPlan.hasNativeEmbeddedFallback());
        assertTrue(source.contains("j2ll_try_define_hidden_fallback"));
        assertTrue(source.contains("j2ll_verify_sha256_hex"));
        assertFalse(source.contains("J2llFallbackSupport"));
        assertTrue(fallbackSupport.contains(
                "FindClass(env, \"xyz/Melody/natives/Loader\")"));
        assertTrue(fallbackSupport.contains("\"defineHiddenFallback\""));
    }

    @Test
    void fieldHelperSourceUsesJniHandlesAndTokensOnly() {
        ParsedClass staticClass = parse("pkg/StaticFields.class", AsmFixtureBuilder.classWithStaticFieldRead("pkg/StaticFields"));
        ParsedClass instanceClass = parse("pkg/Fields.class", AsmFixtureBuilder.classWithInstanceFieldRead("pkg/Fields"));
        MethodRewriteDecision staticDecision = decision(staticClass, "getValue");
        MethodRewriteDecision instanceDecision = decision(instanceClass, "read");
        NativeRegistrationPlan registrationPlan = new NativeRegistrationPlanner().plan(List.of(staticDecision, instanceDecision));
        NativeImplementationPlan implementationPlan = new NativeImplementationPlanner().plan(
                registrationPlan,
                List.of(staticDecision, instanceDecision),
                Map.of(
                        staticDecision.method().methodKey(), irMethod(staticClass, "getValue"),
                        instanceDecision.method().methodKey(), irMethod(instanceClass, "read")));

        String source = new HostJniCSourceGenerator().generate(implementationPlan);
        NativeMethodImplementation staticImplementation = implementationPlan
                .implementationFor(staticDecision.method().methodKey())
                .orElseThrow();
        NativeMethodImplementation instanceImplementation = implementationPlan
                .implementationFor(instanceDecision.method().methodKey())
                .orElseThrow();

        assertTrue(source.contains("typedef struct"));
        assertTrue(source.contains("j2ll_field_table"));
        assertTrue(source.contains("GetStaticFieldID"));
        assertTrue(source.contains("GetStaticIntField"));
        assertTrue(source.contains("GetObjectClass"));
        assertTrue(source.contains("GetFieldID"));
        assertTrue(source.contains("GetIntField"));
        assertFalse(source.contains("NoSuchFieldError"));
        assertFalse(source.contains("NullPointerException"));
        assertTrue(source.contains("j2ll_rt_div_i32"));
        assertFalse(source.contains("java/lang/ArithmeticException"));
        assertFalse(source.contains("\"/ by zero\""));
        assertTrue(source.contains("j2ll_rt_array_load_i32"));
        assertTrue(source.contains("GetIntArrayRegion"));
        assertTrue(source.contains("SetIntArrayRegion"));
        assertFalse(source.contains("ArrayIndexOutOfBoundsException"));
        assertTrue(source.contains("j2ll_decode_metadata_strings();"));
        assertTrue(source.contains("void j2ll_rt_monitor_enter(JNIEnv* env, jobject monitor)"));
        assertTrue(source.contains("(*env)->MonitorEnter(env, monitor)"));
        assertTrue(source.contains("void j2ll_rt_monitor_exit(JNIEnv* env, jobject monitor)"));
        assertTrue(source.contains("(*env)->MonitorExit(env, monitor)"));
        assertTrue(source.contains("jclass j2ll_rt_class_object(JNIEnv* env, int64_t class_token)"));
        assertTrue(source.contains("j2ll_find_class_object_name(class_token)"));
        assertTrue(source.contains("void j2ll_rt_throw(JNIEnv* env, jobject throwable)"));
        assertTrue(source.contains("(*env)->Throw(env, (jthrowable)throwable)"));
        assertTrue(source.contains("extern jint "
                + staticImplementation.llvmFunctionSymbol().orElseThrow()
                + "(JNIEnv* env, jclass owner);"));
        assertTrue(source.contains("jint result = (jint)"
                + staticImplementation.llvmFunctionSymbol().orElseThrow()
                + "(env, owner);"));
        assertTrue(source.contains("extern jint "
                + instanceImplementation.llvmFunctionSymbol().orElseThrow()
                + "(JNIEnv* env, jobject self);"));
        assertTrue(source.contains("jint result = (jint)"
                + instanceImplementation.llvmFunctionSymbol().orElseThrow()
                + "(env, self);"));
        assertTrue(source.contains("return result;"));
        assertFalse(source.contains("j2ll_get_field_pkg_Fields_value_I"));
        assertFalse(source.contains("j2ll_get_static_pkg_StaticFields_VALUE_I"));
        assertFalse(source.contains("self->"));
        assertFalse(source.contains("offsetof("));
    }

    private ParsedClass parse(String entry, byte[] bytes) {
        return new AsmClassParser()
                .parse(new ClassFileEntry(entry, bytes, "fixture"))
                .artifact()
                .orElseThrow();
    }

    private MethodRewriteDecision decision(ParsedClass parsedClass, String name) {
        return new MethodRewritePlanner().planClass(parsedClass).stream()
                .filter(item -> item.method().name().equals(name))
                .findFirst()
                .orElseThrow();
    }

    private IrMethod irMethod(ParsedClass parsedClass, String name) {
        ParsedMethod method = parsedClass.methods().stream()
                .filter(candidate -> candidate.name().equals(name))
                .findFirst()
                .orElseThrow();
        return new BytecodeToSsaLowerer()
                .lower(new MethodCfgBuilder().build(method).artifact().orElseThrow())
                .artifact()
                .orElseThrow()
                .irMethod()
                .orElseThrow();
    }

    private void assertAppearsBefore(String source, String first, String second) {
        int firstIndex = source.indexOf(first);
        int secondIndex = source.indexOf(second);
        assertTrue(firstIndex >= 0, () -> "missing generated C marker: " + first);
        assertTrue(secondIndex >= 0, () -> "missing generated C marker: " + second);
        assertTrue(firstIndex < secondIndex, () -> first + " must appear before " + second);
    }
}
