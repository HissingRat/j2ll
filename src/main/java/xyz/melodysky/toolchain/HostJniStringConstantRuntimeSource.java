package xyz.melodysky.toolchain;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import xyz.melodysky.ir.model.IrOpcode;

final class HostJniStringConstantRuntimeSource {
    private HostJniStringConstantRuntimeSource() {}

    static void append(StringBuilder builder, List<HostJniCSourceGenerator.Binding> bindings) {
        Map<Long, String> constants = new TreeMap<>();
        Map<Long, EncryptedStringConstant> encryptedConstants = new TreeMap<>();
        for (HostJniCSourceGenerator.Binding binding : bindings) {
            if (binding.templateIrMethod().isEmpty()) {
                continue;
            }
            binding.templateIrMethod().orElseThrow().blocks().stream()
                    .flatMap(block -> block.instructions().stream())
                    .filter(instruction -> instruction.opcode() == IrOpcode.CALL_RUNTIME_HELPER)
                    .flatMap(instruction -> instruction.symbol().stream())
                    .filter(symbol -> symbol.startsWith("j2ll_rt_string_constant|string:"))
                    .forEach(symbol -> {
                        String value = symbol.substring("j2ll_rt_string_constant|string:".length());
                        constants.putIfAbsent(javaStringHashUnsigned("string:" + value), value);
                    });
            binding.templateIrMethod().orElseThrow().blocks().stream()
                    .flatMap(block -> block.instructions().stream())
                    .filter(instruction -> instruction.opcode() == IrOpcode.CONST_STRING)
                    .flatMap(instruction -> instruction.symbol().stream())
                    .forEach(symbol -> {
                        String value = symbol.startsWith("string:") ? symbol.substring("string:".length()) : symbol;
                        constants.putIfAbsent(javaStringHashUnsigned("string:" + value), value);
                    });
            binding.templateIrMethod().orElseThrow().blocks().stream()
                    .flatMap(block -> block.instructions().stream())
                    .filter(instruction -> instruction.opcode() == IrOpcode.CALL_RUNTIME_HELPER)
                    .flatMap(instruction -> instruction.symbol().stream())
                    .filter(symbol -> symbol.startsWith("j2ll_rt_string_constant|enc:v1:"))
                    .forEach(symbol -> {
                        String[] parts = symbol.split(":", 5);
                        if (parts.length == 5) {
                            long token = Long.parseLong(parts[2]);
                            encryptedConstants.putIfAbsent(token, new EncryptedStringConstant(
                                    token,
                                    parts[3],
                                    parts[4],
                                    parts[4].length() / 2));
                        }
                    });
        }
        if (constants.isEmpty() && encryptedConstants.isEmpty()) {
            builder.append("""
                    jobject j2ll_rt_string_constant(JNIEnv* env, int64_t token) {
                        (void)token;
                        j2ll_throw_new(env, "java/lang/IllegalArgumentException", "unknown string constant token");
                        return NULL;
                    }

                    """);
            return;
        }
        if (!constants.isEmpty()) {
            builder.append("typedef struct { int64_t token; const char* value; } j2ll_string_constant_entry;\n")
                    .append("static const j2ll_string_constant_entry j2ll_string_constant_table[] = {\n");
            for (Map.Entry<Long, String> entry : constants.entrySet()) {
                builder.append("    { ")
                        .append(entry.getKey())
                        .append("LL, \"")
                        .append(escapeCString(entry.getValue()))
                        .append("\" },\n");
            }
            builder.append("};\n");
        }
        if (!encryptedConstants.isEmpty()) {
            int index = 0;
            for (EncryptedStringConstant entry : encryptedConstants.values()) {
                builder.append("static const unsigned char j2ll_str_key_")
                        .append(index)
                        .append("[] = { ")
                        .append(cByteArray(entry.keyHex()))
                        .append(" };\n")
                        .append("static const unsigned char j2ll_str_cipher_")
                        .append(index)
                        .append("[] = { ")
                        .append(cByteArray(entry.cipherHex()))
                        .append(" };\n");
                index++;
            }
            builder.append("""
                    typedef struct {
                        int64_t token;
                        const unsigned char* key;
                        size_t key_len;
                        const unsigned char* cipher;
                        size_t cipher_len;
                    } j2ll_encrypted_string_constant_entry;
                    """);
            builder.append("static const j2ll_encrypted_string_constant_entry j2ll_encrypted_string_constant_table[] = {\n");
            index = 0;
            for (EncryptedStringConstant entry : encryptedConstants.values()) {
                builder.append("    { ")
                        .append(entry.token())
                        .append("LL, j2ll_str_key_")
                        .append(index)
                        .append(", sizeof(j2ll_str_key_")
                        .append(index)
                        .append("), j2ll_str_cipher_")
                        .append(index)
                        .append(", ")
                        .append(entry.length())
                        .append(" },\n");
                index++;
            }
            builder.append("};\n");
        }
        builder.append("jobject j2ll_rt_string_constant(JNIEnv* env, int64_t token) {\n");
        if (!constants.isEmpty()) {
            builder.append("    for (size_t index = 0; index < sizeof(j2ll_string_constant_table) / sizeof(j2ll_string_constant_table[0]); index++) {\n")
                    .append("        if (j2ll_string_constant_table[index].token == token) {\n")
                    .append("            return (*env)->NewStringUTF(env, j2ll_string_constant_table[index].value);\n")
                    .append("        }\n")
                    .append("    }\n");
        }
        if (!encryptedConstants.isEmpty()) {
            builder.append("""
                    for (size_t index = 0; index < sizeof(j2ll_encrypted_string_constant_table) / sizeof(j2ll_encrypted_string_constant_table[0]); index++) {
                        const j2ll_encrypted_string_constant_entry* entry = &j2ll_encrypted_string_constant_table[index];
                        if (entry->token == token) {
                            char* plain = (char*)malloc(entry->cipher_len + 1);
                            if (plain == NULL) {
                                j2ll_throw_new(env, "java/lang/OutOfMemoryError", "string decrypt allocation failed");
                                return NULL;
                            }
                            for (size_t byte_index = 0; byte_index < entry->cipher_len; byte_index++) {
                                plain[byte_index] = (char)(entry->cipher[byte_index] ^ entry->key[byte_index % entry->key_len]);
                            }
                            plain[entry->cipher_len] = 0;
                            jstring result = (*env)->NewStringUTF(env, plain);
                            free(plain);
                            return result;
                        }
                    }
                    """);
        }
        builder.append("    j2ll_throw_new(env, \"java/lang/IllegalArgumentException\", \"unknown string constant token\");\n")
                .append("    return NULL;\n")
                .append("}\n\n");
    }

    private static String cByteArray(String hex) {
        ArrayList<String> bytes = new ArrayList<>();
        for (int index = 0; index < hex.length(); index += 2) {
            bytes.add("0x" + hex.substring(index, index + 2));
        }
        return String.join(", ", bytes);
    }

    private record EncryptedStringConstant(long token, String keyHex, String cipherHex, int length) {
    }

    static boolean isNeeded(List<HostJniCSourceGenerator.Binding> bindings) {
        return bindings.stream()
                .flatMap(binding -> binding.templateIrMethod().stream())
                .flatMap(method -> method.blocks().stream())
                .flatMap(block -> block.instructions().stream())
                .anyMatch(instruction -> instruction.opcode() == IrOpcode.CONST_STRING
                        || (instruction.opcode() == IrOpcode.CALL_RUNTIME_HELPER
                                && instruction.symbol()
                                        .map(symbol -> runtimeHelperBaseSymbol(symbol).equals("j2ll_rt_string_constant"))
                                        .orElse(false)));
    }


    private static String runtimeHelperBaseSymbol(String symbol) {
        int separator = symbol.indexOf('|');
        return separator < 0 ? symbol : symbol.substring(0, separator);
    }

    private static String escapeCString(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static long javaStringHashUnsigned(String value) {
        return Integer.toUnsignedLong(value.hashCode());
    }
}
