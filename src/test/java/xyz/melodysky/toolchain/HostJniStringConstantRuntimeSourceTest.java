package xyz.melodysky.toolchain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import xyz.melodysky.backend.llvm.LlvmModuleLowerer;
import xyz.melodysky.backend.llvm.LlvmNameMangler;
import xyz.melodysky.backend.llvm.model.LlvmTextEmitter;
import xyz.melodysky.ir.model.BusinessStringConstantRef;
import xyz.melodysky.ir.model.BusinessStringSymbolMapper;
import xyz.melodysky.ir.model.IrBlock;
import xyz.melodysky.ir.model.IrClass;
import xyz.melodysky.ir.model.IrInstruction;
import xyz.melodysky.ir.model.IrMethod;
import xyz.melodysky.ir.model.IrOpcode;
import xyz.melodysky.ir.model.IrTerminator;
import xyz.melodysky.ir.model.IrType;
import xyz.melodysky.ir.model.IrValue;
import xyz.melodysky.ir.pass.protection.ProtectionConfig;
import xyz.melodysky.ir.pass.protection.StringEncryptionPass;
import xyz.melodysky.toolchain.nativetext.NativeScratchZeroizerSource;
import xyz.melodysky.toolchain.nativetext.NativeTextBuildKey;

final class HostJniStringConstantRuntimeSourceTest {
    @Test
    void ordinaryAndEncryptedCarriersBecomeIndependentLocalHelpers() {
        IrMethod ordinary = stringMethod("ordinary", "ordinary-sensitive-value");
        IrMethod encrypted = new StringEncryptionPass().run(
                stringMethod("encrypted", "encrypted-sensitive-value"),
                ProtectionConfig.enabled(41L));
        StringBuilder source = new StringBuilder();

        HostJniStringConstantRuntimeSource.append(
                source,
                List.of(binding(ordinary), binding(encrypted)),
                NativeTextBuildKey.fromUtf8("fixed-business-build"));

        String generated = source.toString();
        assertFalse(generated.contains("ordinary-sensitive-value"));
        assertFalse(generated.contains("encrypted-sensitive-value"));
        assertFalse(generated.contains("j2ll_string_constant_table"));
        assertFalse(generated.contains("j2ll_encrypted_string_constant_table"));
        assertFalse(generated.contains("j2ll_str_key_"));
        assertFalse(generated.contains("j2ll_str_cipher_"));
        assertFalse(generated.contains("typedef struct"));
        assertFalse(generated.contains("jobject j2ll_rt_string_constant(JNIEnv* env, int64_t token)"));
        assertFalse(generated.contains("if (token == "));
        assertFalse(generated.contains("j2ll_native_text_decode"));
        assertFalse(generated.contains(
                "volatile unsigned char* clear_cursor"));
        assertEquals(2, occurrences(generated, "_cipher[] = {"));
        assertEquals(2, occurrences(generated, "jobject j2ll_rt_string_constant_"));
        assertEquals(2, occurrences(generated, "jstring result = (*env)->NewStringUTF"));
        assertEquals(2, occurrences(
                generated,
                NativeScratchZeroizerSource.FUNCTION_NAME + "("));
        assertEquals(2, occurrences(generated, "return result;"));

        int offset = 0;
        for (int index = 0; index < 2; index++) {
            int newString = generated.indexOf(
                    "jstring result = (*env)->NewStringUTF",
                    offset);
            int zero = generated.indexOf(
                    NativeScratchZeroizerSource.FUNCTION_NAME + "(",
                    newString);
            int returned = generated.indexOf("return result;", newString);
            assertTrue(newString >= 0);
            assertTrue(zero > newString);
            assertTrue(returned > zero);
            offset = returned + 1;
        }
    }

    @Test
    void buildKeyDiversifiesBusinessCiphertextAndCompatibilityOverloadIsStable() {
        List<HostJniCSourceGenerator.Binding> bindings =
                List.of(binding(stringMethod("value", "same-sensitive-value")));
        StringBuilder first = new StringBuilder();
        StringBuilder second = new StringBuilder();
        StringBuilder compatibilityOne = new StringBuilder();
        StringBuilder compatibilityTwo = new StringBuilder();

        HostJniStringConstantRuntimeSource.append(
                first,
                bindings,
                NativeTextBuildKey.fromUtf8("build-one"));
        HostJniStringConstantRuntimeSource.append(
                second,
                bindings,
                NativeTextBuildKey.fromUtf8("build-two"));
        HostJniStringConstantRuntimeSource.append(compatibilityOne, bindings);
        HostJniStringConstantRuntimeSource.append(compatibilityTwo, bindings);

        assertNotEquals(first.toString(), second.toString());
        assertEquals(compatibilityOne.toString(), compatibilityTwo.toString());
        assertNotEquals(
                helperDefinition(first.toString()),
                helperDefinition(second.toString()));
    }

    @Test
    void equalValuesShareOnlyTheirSmallLocalHelperGroup() {
        StringBuilder source = new StringBuilder();

        HostJniStringConstantRuntimeSource.append(
                source,
                List.of(
                        binding(stringMethod("first", "same-value")),
                        binding(stringMethod("second", "same-value"))),
                NativeTextBuildKey.fromUtf8("fixed-build"));

        assertEquals(
                1,
                occurrences(
                        source.toString(),
                        "jobject j2ll_rt_string_constant_"));
    }

    @Test
    void llvmCallAndGeneratedCHelperShareTheSameBuildScopedSymbol() {
        IrMethod method = stringMethod("value", "a\0\uD83D\uDE00");
        NativeTextBuildKey buildKey =
                NativeTextBuildKey.fromUtf8("build-scoped-helper");
        BusinessStringSymbolMapper mapper =
                BusinessStringSymbolMapper.fromBytes(buildKey.bytes());
        String helper = BusinessStringConstantRef.of("a\0\uD83D\uDE00")
                .helperSymbol(mapper);
        String llvm = new LlvmTextEmitter().emit(
                new LlvmModuleLowerer(new LlvmNameMangler(), mapper)
                        .lowerClass(new IrClass("sample/Strings", List.of(method))));
        StringBuilder source = new StringBuilder();

        HostJniStringConstantRuntimeSource.append(
                source,
                List.of(binding(method)),
                buildKey);

        assertTrue(llvm.contains("declare ptr @" + helper + "(ptr)"));
        assertTrue(llvm.contains(
                "call ptr @" + helper + "(ptr %j2ll_env)"));
        assertTrue(source.toString().contains(
                "jobject " + helper + "(JNIEnv* env)"));
        assertFalse(llvm.contains(
                "@j2ll_rt_string_constant(ptr %j2ll_env, i64"));
        assertFalse(source.toString().contains(
                "jobject j2ll_rt_string_constant(JNIEnv* env, int64_t token)"));
        assertNotEquals(
                helper,
                BusinessStringConstantRef.of("a\0\uD83D\uDE00")
                        .helperSymbol(BusinessStringSymbolMapper.fromBytes(
                                NativeTextBuildKey.fromUtf8("other-build")
                                        .bytes())));
    }

    @Test
    void rejectsMalformedEncryptedCarrierInsteadOfEmittingPartialRuntime() {
        IrValue value = new IrValue("%value", IrType.REFERENCE);
        IrMethod malformed = new IrMethod(
                "sample/Strings",
                "malformed",
                "()Ljava/lang/String;",
                IrType.REFERENCE,
                List.of(),
                List.of(new IrBlock(
                        "entry",
                        List.of(IrInstruction.operation(
                                Optional.of(value),
                                IrOpcode.CALL_RUNTIME_HELPER,
                                List.of(),
                                "j2ll_rt_string_constant|enc:v1:1:zz:00")),
                        IrTerminator.returnValue(value))));

        assertThrows(
                IllegalArgumentException.class,
                () -> HostJniStringConstantRuntimeSource.append(
                        new StringBuilder(),
                        List.of(binding(malformed)),
                        NativeTextBuildKey.fromUtf8("fixed-build")));
    }

    private IrMethod stringMethod(String name, String value) {
        IrValue result = new IrValue("%value", IrType.REFERENCE);
        return new IrMethod(
                "sample/Strings",
                name,
                "()Ljava/lang/String;",
                IrType.REFERENCE,
                List.of(),
                List.of(new IrBlock(
                        "entry",
                        List.of(IrInstruction.symbolicConstant(
                                result,
                                IrOpcode.CONST_STRING,
                                "string:" + value)),
                        IrTerminator.returnValue(result))));
    }

    private HostJniCSourceGenerator.Binding binding(IrMethod method) {
        return new HostJniCSourceGenerator.Binding(
                null,
                null,
                NativeImplementationPath.TEMPLATE_JNI_PATH,
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
    }

    private int occurrences(String text, String needle) {
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(needle, index)) >= 0) {
            count++;
            index += needle.length();
        }
        return count;
    }

    private String helperDefinition(String source) {
        int start = source.indexOf("jobject j2ll_rt_string_constant_");
        int end = source.indexOf("(JNIEnv* env)", start);
        return source.substring(start, end);
    }
}
