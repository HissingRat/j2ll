package xyz.melodysky.toolchain;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import xyz.melodysky.ir.model.IrBlock;
import xyz.melodysky.ir.model.IrInstruction;
import xyz.melodysky.ir.model.IrMethod;
import xyz.melodysky.ir.model.IrOpcode;
import xyz.melodysky.ir.model.IrTerminator;
import xyz.melodysky.ir.model.IrType;
import xyz.melodysky.ir.model.IrValue;
import xyz.melodysky.runtime.RuntimeTokenMapper;
import xyz.melodysky.toolchain.nativetext.GeneratedCFragmentTextObfuscator;
import xyz.melodysky.toolchain.nativetext.GeneratedNativeHardeningAudit;
import xyz.melodysky.toolchain.nativetext.NativeTextBuildKey;

final class HostJniLambdaRuntimeSourceTest {
    @Test
    void lambdaMetadataIsCallLocalAndHasNoTokenDirectory() {
        String spec = String.join(
                "\n",
                "plugin/Caller",
                "run",
                "()Ljava/lang/Runnable;",
                "()V",
                "6",
                "plugin/Caller",
                "body",
                "()V",
                "()V");
        String encoded = Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(spec.getBytes(StandardCharsets.UTF_8));
        String identity = "lambda:" + encoded;
        IrValue token = new IrValue("%token", IrType.I64);
        IrValue capture = new IrValue("%capture", IrType.REFERENCE);
        IrValue result = new IrValue("%result", IrType.REFERENCE);
        IrInstruction call = IrInstruction.call(
                Optional.of(result),
                IrOpcode.CALL_RUNTIME_HELPER,
                List.of(token, capture),
                "j2ll_rt_lambda_new|" + identity);
        IrMethod method = new IrMethod(
                "plugin/Caller",
                "factory",
                "()Ljava/lang/Runnable;",
                IrType.REFERENCE,
                List.of(),
                List.of(new IrBlock(
                        "entry",
                        List.of(call),
                        IrTerminator.returnValue(result))));
        HostJniCSourceGenerator.Binding binding = new HostJniCSourceGenerator.Binding(
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
        RuntimeTokenMapper mapper = RuntimeTokenMapper.fromBytes(
                "lambda-build".getBytes(StandardCharsets.UTF_8));
        StringBuilder source = new StringBuilder();

        HostJniLambdaRuntimeSource.append(
                source,
                List.of(binding),
                mapper);

        String generated = source.toString();
        String symbol = HostJniLambdaRuntimeSource.helperSymbol(
                mapper,
                identity);
        assertTrue(generated.contains(
                "jobject " + symbol + "(JNIEnv* env, jobject capture)"));
        assertTrue(generated.contains(
                "const char* caller_owner = \"plugin/Caller\";"));
        assertTrue(generated.contains(
                "entry.caller_owner = caller_owner;"));
        assertTrue(generated.contains("entry.impl_is_platform = 0;"));
        assertFalse(generated.contains("strncmp("));
        assertFalse(generated.contains("j2ll_lambda_table"));
        assertFalse(generated.contains("j2ll_find_lambda_entry"));
        assertFalse(generated.contains("int64_t token;"));
        assertFalse(symbol.contains("lambda"));
        assertTrue(symbol.matches("j2ll_h_[0-9a-f]{16}"));

        String hardened = new GeneratedCFragmentTextObfuscator().obfuscate(
                NativeTextBuildKey.fromUtf8("lambda-build"),
                "lambda",
                generated);
        assertTrue(new GeneratedNativeHardeningAudit()
                .audit(hardened)
                .passed());
        assertTrue(hardened.contains("__attribute__((cleanup("));
        assertFalse(hardened.contains("plugin/Caller"));
        assertFalse(hardened.contains("plugin/Caller#body"));
    }
}
