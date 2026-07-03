package xyz.melodysky.toolchain;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
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
import xyz.melodysky.testsupport.AsmFixtureBuilder;

class HostJniCSourceGeneratorTest {
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
        assertTrue(source.contains("NoSuchFieldError"));
        assertTrue(source.contains("NullPointerException"));
        assertTrue(source.contains("j2ll_rt_div_i32"));
        assertTrue(source.contains("java/lang/ArithmeticException"));
        assertTrue(source.contains("\"/ by zero\""));
        assertTrue(source.contains("j2ll_rt_array_load_i32"));
        assertTrue(source.contains("GetIntArrayRegion"));
        assertTrue(source.contains("SetIntArrayRegion"));
        assertTrue(source.contains("ArrayIndexOutOfBoundsException"));
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
}
