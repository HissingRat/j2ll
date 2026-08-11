package xyz.melodysky.toolchain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import xyz.melodysky.ir.model.IrBlock;
import xyz.melodysky.ir.model.IrInstruction;
import xyz.melodysky.ir.model.IrMethod;
import xyz.melodysky.ir.model.IrOpcode;
import xyz.melodysky.ir.model.IrTerminator;
import xyz.melodysky.ir.model.IrType;
import xyz.melodysky.runtime.RuntimeTokenMapper;

final class HostJniReflectionRuntimeSourceTest {
    private static final List<String> FIELD_HELPERS = List.of(
            "j2ll_rt_reflect_field_get",
            "j2ll_rt_reflect_field_set",
            "j2ll_rt_reflect_field_get_int",
            "j2ll_rt_reflect_field_set_int",
            "j2ll_rt_reflect_field_get_boolean",
            "j2ll_rt_reflect_field_set_boolean",
            "j2ll_rt_reflect_field_get_long",
            "j2ll_rt_reflect_field_set_long",
            "j2ll_rt_reflect_field_get_double",
            "j2ll_rt_reflect_field_set_double");

    @Test
    void fieldOperationsUseExactJavaMethodNamesDescriptorsAndJniCallKinds() {
        assertEquals(
                FIELD_HELPERS,
                Arrays.stream(ReflectionFieldOperation.values())
                        .map(ReflectionFieldOperation::runtimeHelperSymbol)
                        .toList(),
                "every reflective Field operation must have an exact source assertion");
        String generated = fieldHelperSource();
        String objectGetter = helperDefinition(
                generated,
                "j2ll_rt_reflect_field_get");
        String objectSetter = helperDefinition(
                generated,
                "j2ll_rt_reflect_field_set");

        assertTrue(objectGetter.contains(
                "GetMethodID(env, cls, \"get\", "
                        + "\"(Ljava/lang/Object;)Ljava/lang/Object;\")"));
        assertTrue(objectGetter.contains(
                "CallObjectMethod(env, field, method, target)"));
        assertTrue(objectSetter.contains(
                "GetMethodID(env, cls, \"set\", "
                        + "\"(Ljava/lang/Object;Ljava/lang/Object;)V\")"));
        assertTrue(objectSetter.contains(
                "CallVoidMethod(env, field, method, target, value)"));
        assertFalse(generated.contains("GetMethodID(env, cls, \"getObject\""));
        assertFalse(generated.contains("GetMethodID(env, cls, \"setObject\""));

        assertFieldGetter(
                generated,
                "j2ll_rt_reflect_field_get_int",
                "getInt",
                "(Ljava/lang/Object;)I",
                "CallIntMethod");
        assertFieldSetter(
                generated,
                "j2ll_rt_reflect_field_set_int",
                "setInt",
                "(Ljava/lang/Object;I)V");
        assertFieldGetter(
                generated,
                "j2ll_rt_reflect_field_get_boolean",
                "getBoolean",
                "(Ljava/lang/Object;)Z",
                "CallBooleanMethod");
        assertFieldSetter(
                generated,
                "j2ll_rt_reflect_field_set_boolean",
                "setBoolean",
                "(Ljava/lang/Object;Z)V");
        assertFieldGetter(
                generated,
                "j2ll_rt_reflect_field_get_long",
                "getLong",
                "(Ljava/lang/Object;)J",
                "CallLongMethod");
        assertFieldSetter(
                generated,
                "j2ll_rt_reflect_field_set_long",
                "setLong",
                "(Ljava/lang/Object;J)V");
        assertFieldGetter(
                generated,
                "j2ll_rt_reflect_field_get_double",
                "getDouble",
                "(Ljava/lang/Object;)D",
                "CallDoubleMethod");
        assertFieldSetter(
                generated,
                "j2ll_rt_reflect_field_set_double",
                "setDouble",
                "(Ljava/lang/Object;D)V");
    }

    @Test
    void reflectionMetadataIsBindingScopedHashOnlyAndUsesDefiningLoader() {
        RuntimeTokenMapper mapper = RuntimeTokenMapper.fromBytes(
                "reflection-build".getBytes(StandardCharsets.UTF_8));
        String methodKey =
                "method:plugin/Hidden#secret!(Lplugin/Argument;)Ljava/lang/String;";
        String fieldKey = "field:plugin/Hidden#token";
        HostJniCSourceGenerator.Binding binding =
                new HostJniCSourceGenerator.Binding(
                        null,
                        null,
                        NativeImplementationPath.LLVM_NATIVE_PATH,
                        Optional.empty(),
                        true,
                        true,
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(methodKey, fieldKey),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        Optional.empty(),
                        "test",
                        null);
        StringBuilder source = new StringBuilder();

        HostJniReflectionRuntimeSource.append(
                source,
                List.of(binding),
                mapper);

        String generated = source.toString();
        assertTrue(generated.contains(
                HostJniReflectionRuntimeSource.helperSymbol(
                        mapper,
                        methodKey)));
        assertTrue(generated.contains(
                HostJniReflectionRuntimeSource.helperSymbol(
                        mapper,
                        fieldKey)));
        assertTrue(generated.contains(
                "FindClass(env, \"plugin/Hidden\")"));
        assertTrue(generated.contains(
                "CallObjectMethod(env, owner, get_loader)"));
        assertTrue(generated.contains(
                "j2ll_parameter_array_for_descriptor(env, "
                        + "\"(Lplugin/Argument;)Ljava/lang/String;\", loader)"));
        assertFalse(generated.contains("j2ll_k"));
        assertFalse(generated.contains(
                "native local ABI integrity check failed"));
        assertFalse(generated.contains("getContextClassLoader"));
        assertFalse(generated.contains("currentThread"));
        assertFalse(generated.contains("strlen("));
        assertFalse(generated.contains("malloc("));
        assertFalse(generated.contains("free("));
        assertFalse(generated.contains("j2ll_dotted_class_name"));
        assertFalse(generated.contains("j2ll_reflection_method_table"));
        assertFalse(generated.contains("j2ll_reflection_field_table"));
        assertFalse(generated.contains("j2ll_find_reflection"));
    }

    @Test
    void helperSymbolsDifferAcrossBuildsAndLeakNoMetadata() {
        String key = "method:plugin/Hidden#secret!()V";
        String first = HostJniReflectionRuntimeSource.helperSymbol(
                RuntimeTokenMapper.fromBytes(
                        "first".getBytes(StandardCharsets.UTF_8)),
                key);
        String second = HostJniReflectionRuntimeSource.helperSymbol(
                RuntimeTokenMapper.fromBytes(
                        "second".getBytes(StandardCharsets.UTF_8)),
                key);

        assertFalse(first.equals(second));
        assertTrue(first.matches("j2ll_h_[0-9a-f]{16}"));
        assertFalse(first.contains("method"));
        assertFalse(first.contains("Hidden"));
        assertFalse(first.contains("secret"));
    }

    private String fieldHelperSource() {
        List<IrInstruction> calls = FIELD_HELPERS.stream()
                .map(symbol -> IrInstruction.operation(
                        Optional.empty(),
                        IrOpcode.CALL_RUNTIME_HELPER,
                        List.of(),
                        symbol))
                .toList();
        IrMethod method = new IrMethod(
                "fixture/Reflection",
                "exerciseFields",
                "()V",
                IrType.VOID,
                List.of(),
                List.of(new IrBlock(
                        "entry",
                        calls,
                        IrTerminator.returnVoid())));
        HostJniCSourceGenerator.Binding binding =
                new HostJniCSourceGenerator.Binding(
                        null,
                        null,
                        NativeImplementationPath.LLVM_NATIVE_PATH,
                        Optional.empty(),
                        true,
                        true,
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        Optional.of(method),
                        "test",
                        null);
        StringBuilder source = new StringBuilder();
        HostJniReflectionRuntimeSource.append(
                source,
                List.of(binding),
                RuntimeTokenMapper.fromBytes(
                        "field-helper-build".getBytes(StandardCharsets.UTF_8)));
        return source.toString();
    }

    private void assertFieldGetter(
            String generated,
            String helperSymbol,
            String javaMethodName,
            String descriptor,
            String jniCall) {
        String helper = helperDefinition(generated, helperSymbol);
        assertTrue(helper.contains(
                "GetMethodID(env, cls, \""
                        + javaMethodName
                        + "\", \""
                        + descriptor
                        + "\")"));
        assertTrue(helper.contains(
                jniCall + "(env, field, method, target)"));
    }

    private void assertFieldSetter(
            String generated,
            String helperSymbol,
            String javaMethodName,
            String descriptor) {
        String helper = helperDefinition(generated, helperSymbol);
        assertTrue(helper.contains(
                "GetMethodID(env, cls, \""
                        + javaMethodName
                        + "\", \""
                        + descriptor
                        + "\")"));
        assertTrue(helper.contains(
                "CallVoidMethod(env, field, method, target, "));
    }

    private String helperDefinition(String generated, String helperSymbol) {
        int symbol = generated.indexOf(" " + helperSymbol + "(");
        assertTrue(symbol >= 0, "missing helper " + helperSymbol);
        int start = generated.lastIndexOf('\n', symbol) + 1;
        int end = generated.indexOf("\n}\n\n", symbol);
        assertTrue(end >= 0, "unterminated helper " + helperSymbol);
        return generated.substring(start, end + 3);
    }
}
