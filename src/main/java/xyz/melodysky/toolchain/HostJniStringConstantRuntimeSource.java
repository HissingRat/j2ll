package xyz.melodysky.toolchain;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import xyz.melodysky.ir.model.BusinessStringConstantRef;
import xyz.melodysky.ir.model.BusinessStringSymbolMapper;
import xyz.melodysky.ir.model.IrInstruction;
import xyz.melodysky.ir.model.IrMethod;
import xyz.melodysky.toolchain.nativetext.NativeScratchZeroizerSource;
import xyz.melodysky.toolchain.nativetext.NativeTextBuildKey;
import xyz.melodysky.toolchain.nativetext.NativeTextCEmitter;
import xyz.melodysky.toolchain.nativetext.NativeTextEncoder;
import xyz.melodysky.toolchain.nativetext.NativeTextEncoding;
import xyz.melodysky.toolchain.nativetext.NativeTextPurpose;

/**
 * Emits one small native helper per distinct business-string value.
 *
 * <p>There is deliberately no token dispatcher, pointer directory, shared
 * business decoder or shared plaintext storage. Each helper owns its
 * ciphertext, decode material and plaintext lifetime. Cleanup uses only the
 * metadata-free native zeroizer shared by the translation unit.</p>
 */
final class HostJniStringConstantRuntimeSource {
    private HostJniStringConstantRuntimeSource() {}

    static void append(
            StringBuilder builder,
            List<HostJniCSourceGenerator.Binding> bindings,
            NativeTextBuildKey buildKey) {
        BusinessStringSymbolMapper symbolMapper =
                BusinessStringSymbolMapper.fromBytes(buildKey.bytes());
        Map<String, LocalizedBusinessString> constants =
                constants(bindings, symbolMapper);
        NativeTextEncoder encoder = new NativeTextEncoder();
        NativeTextCEmitter emitter = new NativeTextCEmitter();
        for (LocalizedBusinessString localized : constants.values()) {
            BusinessStringConstantRef constant = localized.constant();
            NativeTextEncoding encoding = encoder.encodeBytes(
                    buildKey,
                    NativeTextPurpose.BUSINESS_STRING,
                    "business-string-helper:" + localized.helperSymbol(),
                    constant.modifiedUtf8Bytes());
            builder.append(emitter.ciphertextDeclaration(encoding));
            appendLocalHelper(builder, localized, encoding);
        }
    }

    private static void appendLocalHelper(
            StringBuilder builder,
            LocalizedBusinessString localized,
            NativeTextEncoding encoding) {
        String helperSymbol = localized.helperSymbol();
        String scratch = "j2ll_business_text_"
                + helperSymbol.substring(
                        "j2ll_rt_string_constant_".length());
        builder.append("jobject ")
                .append(helperSymbol)
                .append("(JNIEnv* env) {\n")
                .append("    char ")
                .append(scratch)
                .append("[sizeof(")
                .append(encoding.symbol())
                .append("_cipher)];\n")
                .append(new NativeTextCEmitter().decodeInto(
                        encoding,
                        scratch,
                        "    "))
                .append("    jstring result = (*env)->NewStringUTF(env, ")
                .append(scratch)
                .append(");\n")
                .append("    ")
                .append(NativeScratchZeroizerSource.FUNCTION_NAME)
                .append("(")
                .append(scratch)
                .append(", sizeof(")
                .append(scratch)
                .append("));\n")
                .append("    return result;\n")
                .append("}\n\n");
    }

    private static Map<String, LocalizedBusinessString> constants(
            List<HostJniCSourceGenerator.Binding> bindings,
            BusinessStringSymbolMapper symbolMapper) {
        TreeMap<String, LocalizedBusinessString> constants = new TreeMap<>();
        for (HostJniCSourceGenerator.Binding binding : bindings) {
            if (binding.implementationIrMethod().isEmpty()) {
                continue;
            }
            IrMethod method = binding.implementationIrMethod().orElseThrow();
            for (IrInstruction instruction : method.blocks().stream()
                    .flatMap(block -> block.instructions().stream())
                    .toList()) {
                BusinessStringConstantRef.fromInstruction(instruction).ifPresent(candidate -> {
                    String helperSymbol = candidate.helperSymbol(symbolMapper);
                    LocalizedBusinessString existing = constants.putIfAbsent(
                            helperSymbol,
                            new LocalizedBusinessString(candidate, helperSymbol));
                    if (existing != null
                            && !existing.constant().value().equals(candidate.value())) {
                        throw new IllegalArgumentException(
                                "business string helper collision between "
                                        + existing.helperSymbol()
                                        + " and "
                                        + helperSymbol);
                    }
                });
            }
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(constants));
    }

    static boolean isNeeded(List<HostJniCSourceGenerator.Binding> bindings) {
        return bindings.stream()
                .flatMap(binding -> binding.implementationIrMethod().stream())
                .flatMap(method -> method.blocks().stream())
                .flatMap(block -> block.instructions().stream())
                .anyMatch(instruction ->
                        BusinessStringConstantRef.fromInstruction(instruction).isPresent());
    }

    private record LocalizedBusinessString(
            BusinessStringConstantRef constant,
            String helperSymbol) {}
}
