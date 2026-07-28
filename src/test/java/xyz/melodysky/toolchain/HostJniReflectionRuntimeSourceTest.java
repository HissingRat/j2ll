package xyz.melodysky.toolchain;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import xyz.melodysky.runtime.RuntimeTokenMapper;

final class HostJniReflectionRuntimeSourceTest {
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
}
