package xyz.melodysky.runtime;

import xyz.melodysky.backend.llvm.JniMangler;
import xyz.melodysky.packaging.NativeRegistrationPlan;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class IrRuntimeStubGenerator {

    private static final Pattern DECL = Pattern.compile("^declare\\s+(\\S+)\\s+@\\\"([^\\\"]+)\\\"\\(([^)]*)\\)$");
    private static final Pattern HELPER_META = Pattern.compile("^;\\s*helper-meta\\s+(\\S+)\\s+=\\s+(.+)$");
    private RuntimeNames runtimeNames;

    public String generate(String llvmText) {
        return generate(llvmText, NativeRegistrationPlan.empty(), null);
    }

    public String generate(String llvmText, NativeRegistrationPlan registrationPlan, String loaderInternalName) {
        return generateSourceSet(llvmText, registrationPlan, loaderInternalName, 1).monolithicText();
    }

    public RuntimeSourceSet generateSourceSet(String llvmText, NativeRegistrationPlan registrationPlan,
                                              String loaderInternalName, int requestedHelperShardCount) {
        runtimeNames = RuntimeNames.create();
        List<HelperSpec> helpers = collectHelpers(llvmText);

        String monolithicText = renderMonolithicSource(helpers, registrationPlan, loaderInternalName);
        List<RuntimeFragment> fragments = renderShardedSources(helpers, registrationPlan, loaderInternalName, requestedHelperShardCount);
        return new RuntimeSourceSet(monolithicText, fragments);
    }

    private List<HelperSpec> collectHelpers(String llvmText) {
        LinkedHashMap<String, String> helperAliases = new LinkedHashMap<>();
        LinkedHashMap<String, HelperSpec> helpers = new LinkedHashMap<>();
        for (String line : llvmText.lines().toList()) {
            String trimmed = line.trim();
            Matcher helperMetaMatcher = HELPER_META.matcher(trimmed);
            if (helperMetaMatcher.matches()) {
                helperAliases.put(helperMetaMatcher.group(1), helperMetaMatcher.group(2));
                continue;
            }
            Matcher matcher = DECL.matcher(trimmed);
            if (matcher.matches()) {
                String emittedName = matcher.group(2);
                helpers.put(emittedName, new HelperSpec(
                        emittedName,
                        helperAliases.getOrDefault(emittedName, emittedName),
                        new Sig(matcher.group(1), parseParams(matcher.group(3).trim()))
                ));
            }
        }
        String emittedThrow = emittedBuiltinHelperName("ir_rt_throw");
        helpers.putIfAbsent(emittedThrow, new HelperSpec(emittedThrow, "ir_rt_throw", new Sig("void", List.of("ptr"))));
        String emittedExceptionPending = emittedBuiltinHelperName("ir_rt_exception_pending");
        helpers.putIfAbsent(emittedExceptionPending, new HelperSpec(emittedExceptionPending, "ir_rt_exception_pending", new Sig("i1", List.of())));
        return List.copyOf(helpers.values());
    }

    private String renderMonolithicSource(List<HelperSpec> helpers, NativeRegistrationPlan registrationPlan,
                                          String loaderInternalName) {
        StringBuilder out = new StringBuilder();
        appendRuntimeIncludes(out);
        appendRuntimeEnvExtern(out);
        appendEnvAccessorDefinition(out, true);
        appendStringObfuscationSupport(out, true);
        appendNativeBridgeDeclarations(out, registrationPlan);
        appendRegistrationSupport(out, registrationPlan, loaderInternalName);
        appendHelperDefinitions(out, helpers);
        return out.toString();
    }

    private List<RuntimeFragment> renderShardedSources(List<HelperSpec> helpers, NativeRegistrationPlan registrationPlan,
                                                       String loaderInternalName, int requestedHelperShardCount) {
        ArrayList<RuntimeFragment> fragments = new ArrayList<>();

        StringBuilder common = new StringBuilder();
        appendRuntimeIncludes(common);
        appendRuntimeEnvExtern(common);
        appendEnvAccessorDefinition(common, false);
        appendStringObfuscationSupport(common, false);
        appendNativeBridgeDeclarations(common, registrationPlan);
        appendRegistrationSupport(common, registrationPlan, loaderInternalName);
        fragments.add(new RuntimeFragment("common.c", common.toString()));

        int helperShardCount = Math.max(1, Math.min(helpers.size(), requestedHelperShardCount));
        ArrayList<HelperBucket> buckets = new ArrayList<>(helperShardCount);
        for (int index = 0; index < helperShardCount; index++) {
            buckets.add(new HelperBucket(new ArrayList<>(), 0));
        }

        ArrayList<HelperWithSource> renderedHelpers = new ArrayList<>(helpers.size());
        for (HelperSpec helper : helpers) {
            String source = renderSingleHelper(helper);
            renderedHelpers.add(new HelperWithSource(helper, source, source.length()));
        }
        renderedHelpers.sort((left, right) -> Integer.compare(right.estimatedBytes(), left.estimatedBytes()));
        for (HelperWithSource renderedHelper : renderedHelpers) {
            HelperBucket bucket = smallestHelperBucket(buckets);
            bucket.helpers().add(renderedHelper);
            bucket.estimatedBytes += renderedHelper.estimatedBytes();
        }

        int bucketIndex = 0;
        for (HelperBucket bucket : buckets) {
            if (bucket.helpers().isEmpty()) {
                continue;
            }
            StringBuilder shard = new StringBuilder();
            appendRuntimeIncludes(shard);
            appendRuntimeEnvExtern(shard);
            appendSupportDeclarations(shard);
            for (HelperWithSource helper : bucket.helpers()) {
                shard.append(helper.source()).append('\n');
            }
            fragments.add(new RuntimeFragment("helper-" + String.format("%02d", bucketIndex++) + ".c", shard.toString()));
        }

        return List.copyOf(fragments);
    }

    private HelperBucket smallestHelperBucket(List<HelperBucket> buckets) {
        HelperBucket smallest = buckets.getFirst();
        for (int index = 1; index < buckets.size(); index++) {
            HelperBucket current = buckets.get(index);
            if (current.estimatedBytes() < smallest.estimatedBytes()) {
                smallest = current;
            }
        }
        return smallest;
    }

    private void appendRuntimeIncludes(StringBuilder out) {
        out.append("#include <jni.h>\n#include <stddef.h>\n#include <stdint.h>\n#include <stdlib.h>\n#include <stdio.h>\n\n");
        out.append("#if defined(_MSC_VER)\n");
        out.append("#define IR_NOINLINE __declspec(noinline)\n");
        out.append("#else\n");
        out.append("#define IR_NOINLINE __attribute__((noinline))\n");
        out.append("#endif\n\n");
    }

    private void appendRuntimeEnvExtern(StringBuilder out) {
        out.append("extern _Thread_local void* ").append(runtimeNames.currentEnvGlobal()).append(";\n\n");
    }

    private void appendEnvAccessorDefinition(StringBuilder out, boolean internalLinkage) {
        out.append(linkagePrefix(internalLinkage)).append("JNIEnv* ").append(runtimeNames.envAccessor()).append("(void) {\n")
                .append("    return (JNIEnv*)").append(runtimeNames.currentEnvGlobal()).append(";\n}\n\n");
    }

    private void appendSupportDeclarations(StringBuilder out) {
        out.append("JNIEnv* ").append(runtimeNames.envAccessor()).append("(void);\n");
        out.append("uint32_t ").append(runtimeNames.decodeU32()).append("(uint32_t encoded, uint32_t mask);\n");
        out.append("uint32_t ").append(runtimeNames.opaqueGate()).append("(uint32_t seed);\n");
        out.append("uint32_t ").append(runtimeNames.rotl32()).append("(uint32_t value, int shift);\n");
        out.append("uint32_t ").append(runtimeNames.load32Le()).append("(const uint8_t* input);\n");
        out.append("void ").append(runtimeNames.store32Le()).append("(uint8_t* output, uint32_t value);\n");
        out.append("uint8_t ").append(runtimeNames.rotl8()).append("(uint8_t value, int shift);\n");
        out.append("void ").append(runtimeNames.chacha20Block()).append("(const uint8_t key[32], uint32_t counter, const uint8_t nonce[12], uint8_t output[64]);\n");
        out.append("void ").append(runtimeNames.chacha20Xor()).append("(const uint8_t key[32], const uint8_t nonce[12], const uint8_t* input, size_t length, uint8_t* output);\n");
        out.append("void ").append(runtimeNames.deriveStringKey()).append("(uint32_t site_id, const uint8_t seed_a[32], const uint8_t seed_b[32], uint8_t out_key[32]);\n");
        out.append("jstring ").append(runtimeNames.newUtf8String()).append("(JNIEnv* env, const uint8_t* bytes, size_t length);\n");
        out.append("char* ").append(runtimeNames.decodeMetaCString()).append("(const uint8_t* bytes, size_t length, uint8_t seed);\n");
        out.append("jclass ").append(runtimeNames.findClassObf()).append("(JNIEnv* env, const uint8_t* bytes, size_t length, uint8_t seed);\n");
        out.append("jstring ").append(runtimeNames.newStringUtfObf()).append("(JNIEnv* env, const uint8_t* bytes, size_t length, uint8_t seed);\n");
        out.append("jmethodID ").append(runtimeNames.getMethodIdObf()).append("(JNIEnv* env, jclass clazz, uint8_t is_static, const uint8_t* name_bytes, size_t name_length, uint8_t name_seed, const uint8_t* desc_bytes, size_t desc_length, uint8_t desc_seed);\n");
        out.append("jfieldID ").append(runtimeNames.getFieldIdObf()).append("(JNIEnv* env, jclass clazz, uint8_t is_static, const uint8_t* name_bytes, size_t name_length, uint8_t name_seed, const uint8_t* desc_bytes, size_t desc_length, uint8_t desc_seed);\n\n");
    }

    private String renderSingleHelper(HelperSpec helper) {
        StringBuilder out = new StringBuilder();
        appendHelper(out, helper.emittedName(), helper.semanticName(), helper.sig());
        return out.toString();
    }

    private void appendHelperDefinitions(StringBuilder out, List<HelperSpec> helpers) {
        for (HelperSpec helper : helpers) {
            appendHelper(out, helper.emittedName(), helper.semanticName(), helper.sig());
            out.append('\n');
        }
    }

    private String linkagePrefix(boolean internalLinkage) {
        return internalLinkage ? "static " : "";
    }

    private void appendStringObfuscationSupport(StringBuilder out, boolean internalLinkage) {
        String linkage = linkagePrefix(internalLinkage);
        out.append(linkage).append("uint32_t ").append(runtimeNames.decodeU32()).append("(uint32_t encoded, uint32_t mask) {\n");
        out.append("    return encoded ^ mask;\n");
        out.append("}\n\n");
        out.append(linkage).append("uint32_t ").append(runtimeNames.opaqueGate()).append("(uint32_t seed) {\n");
        out.append("    volatile uint32_t local = seed;\n");
        out.append("    return (").append(runtimeNames.decodeU32()).append("(local, local) + 1u);\n");
        out.append("}\n\n");
        out.append(linkage).append("uint32_t ").append(runtimeNames.rotl32()).append("(uint32_t value, int shift) {\n");
        out.append("    return (value << shift) | (value >> (32 - shift));\n");
        out.append("}\n\n");
        out.append(linkage).append("uint32_t ").append(runtimeNames.load32Le()).append("(const uint8_t* input) {\n");
        out.append("    return (uint32_t)input[0]\n");
        out.append("         | ((uint32_t)input[1] << 8)\n");
        out.append("         | ((uint32_t)input[2] << 16)\n");
        out.append("         | ((uint32_t)input[3] << 24);\n");
        out.append("}\n\n");
        out.append(linkage).append("void ").append(runtimeNames.store32Le()).append("(uint8_t* output, uint32_t value) {\n");
        out.append("    output[0] = (uint8_t)value;\n");
        out.append("    output[1] = (uint8_t)(value >> 8);\n");
        out.append("    output[2] = (uint8_t)(value >> 16);\n");
        out.append("    output[3] = (uint8_t)(value >> 24);\n");
        out.append("}\n\n");
        out.append(linkage).append("uint8_t ").append(runtimeNames.rotl8()).append("(uint8_t value, int shift) {\n");
        out.append("    shift &= 7;\n");
        out.append("    if (shift == 0) return value;\n");
        out.append("    return (uint8_t)((value << shift) | (value >> (8 - shift)));\n");
        out.append("}\n\n");
        out.append(linkage).append("void ").append(runtimeNames.chacha20Block()).append("(const uint8_t key[32], uint32_t counter, const uint8_t nonce[12], uint8_t output[64]) {\n");
        out.append("    uint32_t state[16] = {\n");
        out.append("        ").append(obfuscatedU32(runtimeNames.decodeU32(), 0x61707865, 1)).append(", ")
                .append(obfuscatedU32(runtimeNames.decodeU32(), 0x3320646e, 2)).append(", ")
                .append(obfuscatedU32(runtimeNames.decodeU32(), 0x79622d32, 3)).append(", ")
                .append(obfuscatedU32(runtimeNames.decodeU32(), 0x6b206574, 4)).append(",\n");
        out.append("        ").append(runtimeNames.load32Le()).append("(key + 0), ").append(runtimeNames.load32Le()).append("(key + 4), ").append(runtimeNames.load32Le()).append("(key + 8), ").append(runtimeNames.load32Le()).append("(key + 12),\n");
        out.append("        ").append(runtimeNames.load32Le()).append("(key + 16), ").append(runtimeNames.load32Le()).append("(key + 20), ").append(runtimeNames.load32Le()).append("(key + 24), ").append(runtimeNames.load32Le()).append("(key + 28),\n");
        out.append("        counter, ").append(runtimeNames.load32Le()).append("(nonce + 0), ").append(runtimeNames.load32Le()).append("(nonce + 4), ").append(runtimeNames.load32Le()).append("(nonce + 8)\n");
        out.append("    };\n");
        out.append("    uint32_t working[16];\n");
        out.append("    for (int i = 0; i < 16; i++) working[i] = state[i];\n");
        out.append("    for (int round = 0; round < 10; round++) {\n");
        out.append("        #define QR(a, b, c, d) \\\n");
        out.append("            working[a] += working[b]; working[d] = ").append(runtimeNames.rotl32()).append("(working[d] ^ working[a], (int)")
                .append(obfuscatedU32(runtimeNames.decodeU32(), 16, 16)).append("); \\\n");
        out.append("            working[c] += working[d]; working[b] = ").append(runtimeNames.rotl32()).append("(working[b] ^ working[c], (int)")
                .append(obfuscatedU32(runtimeNames.decodeU32(), 12, 12)).append("); \\\n");
        out.append("            working[a] += working[b]; working[d] = ").append(runtimeNames.rotl32()).append("(working[d] ^ working[a], (int)")
                .append(obfuscatedU32(runtimeNames.decodeU32(), 8, 8)).append("); \\\n");
        out.append("            working[c] += working[d]; working[b] = ").append(runtimeNames.rotl32()).append("(working[b] ^ working[c], (int)")
                .append(obfuscatedU32(runtimeNames.decodeU32(), 7, 7)).append(");\n");
        out.append("        QR(0, 4, 8, 12);\n");
        out.append("        QR(1, 5, 9, 13);\n");
        out.append("        QR(2, 6, 10, 14);\n");
        out.append("        QR(3, 7, 11, 15);\n");
        out.append("        QR(0, 5, 10, 15);\n");
        out.append("        QR(1, 6, 11, 12);\n");
        out.append("        QR(2, 7, 8, 13);\n");
        out.append("        QR(3, 4, 9, 14);\n");
        out.append("        #undef QR\n");
        out.append("    }\n");
        out.append("    for (int i = 0; i < 16; i++) {\n");
        out.append("        ").append(runtimeNames.store32Le()).append("(output + (i * 4), working[i] + state[i]);\n");
        out.append("    }\n");
        out.append("}\n\n");
        out.append(linkage).append("void ").append(runtimeNames.chacha20Xor()).append("(const uint8_t key[32], const uint8_t nonce[12], const uint8_t* input, size_t length, uint8_t* output) {\n");
        out.append("    uint32_t counter = 1u;\n");
        out.append("    size_t offset = 0;\n");
        out.append("    uint8_t block[64];\n");
        out.append("    while (offset < length) {\n");
        out.append("        ").append(runtimeNames.chacha20Block()).append("(key, counter++, nonce, block);\n");
        out.append("        size_t block_length = (length - offset) < 64 ? (length - offset) : 64;\n");
        out.append("        for (size_t i = 0; i < block_length; i++) {\n");
        out.append("            output[offset + i] = (uint8_t)(input[offset + i] ^ block[i]);\n");
        out.append("        }\n");
        out.append("        offset += block_length;\n");
        out.append("    }\n");
        out.append("}\n\n");
        out.append(linkage).append("void ").append(runtimeNames.deriveStringKey()).append("(uint32_t site_id, const uint8_t seed_a[32], const uint8_t seed_b[32], uint8_t out_key[32]) {\n");
        out.append("    for (int i = 0; i < 32; i++) {\n");
        out.append("        uint8_t mix = seed_b[(i * ").append(obfuscatedU32(runtimeNames.decodeU32(), 7, 107))
                .append(" + site_id) & ").append(obfuscatedU32(runtimeNames.decodeU32(), 31, 131)).append("];\n");
        out.append("        uint8_t rotated = ").append(runtimeNames.rotl8()).append("(mix, (int)((site_id + (uint32_t)i) & 7u));\n");
        out.append("        uint8_t spice = (uint8_t)((site_id * ").append(obfuscatedU32(runtimeNames.decodeU32(), 131, 17))
                .append(" + (uint32_t)i * ").append(obfuscatedU32(runtimeNames.decodeU32(), 17, 131)).append(") & 0xffu);\n");
        out.append("        out_key[i] = (uint8_t)(seed_a[i] ^ rotated ^ spice);\n");
        out.append("    }\n");
        out.append("}\n\n");
        out.append(linkage).append("jstring ").append(runtimeNames.newUtf8String()).append("(JNIEnv* env, const uint8_t* bytes, size_t length) {\n");
        out.append("    if (length == 0) {\n");
        out.append("        static const jchar empty_chars[1] = {0};\n");
        out.append("        return (*env)->NewString(env, empty_chars, 0);\n");
        out.append("    }\n");
        out.append("    size_t utf16_length = 0;\n");
        out.append("    for (size_t i = 0; i < length;) {\n");
        out.append("        uint8_t b0 = bytes[i];\n");
        out.append("        if ((b0 & 0x80u) == 0) {\n");
        out.append("            utf16_length += 1;\n");
        out.append("            i += 1;\n");
        out.append("            continue;\n");
        out.append("        }\n");
        out.append("        if ((b0 & 0xE0u) == 0xC0u) {\n");
        out.append("            if (i + 1 >= length) return NULL;\n");
        out.append("            utf16_length += 1;\n");
        out.append("            i += 2;\n");
        out.append("            continue;\n");
        out.append("        }\n");
        out.append("        if ((b0 & 0xF0u) == 0xE0u) {\n");
        out.append("            if (i + 2 >= length) return NULL;\n");
        out.append("            utf16_length += 1;\n");
        out.append("            i += 3;\n");
        out.append("            continue;\n");
        out.append("        }\n");
        out.append("        if ((b0 & 0xF8u) == 0xF0u) {\n");
        out.append("            if (i + 3 >= length) return NULL;\n");
        out.append("            utf16_length += 2;\n");
        out.append("            i += 4;\n");
        out.append("            continue;\n");
        out.append("        }\n");
        out.append("        return NULL;\n");
        out.append("    }\n");
        out.append("    jchar* chars = utf16_length == 0 ? NULL : (jchar*)malloc(sizeof(jchar) * utf16_length);\n");
        out.append("    if (utf16_length != 0 && chars == NULL) return NULL;\n");
        out.append("    size_t out_index = 0;\n");
        out.append("    for (size_t i = 0; i < length;) {\n");
        out.append("        uint8_t b0 = bytes[i];\n");
        out.append("        uint32_t code_point;\n");
        out.append("        if ((b0 & 0x80u) == 0) {\n");
        out.append("            code_point = (uint32_t)b0;\n");
        out.append("            i += 1;\n");
        out.append("        } else if ((b0 & 0xE0u) == 0xC0u) {\n");
        out.append("            uint8_t b1 = bytes[i + 1];\n");
        out.append("            if ((b1 & 0xC0u) != 0x80u) { free(chars); return NULL; }\n");
        out.append("            code_point = ((uint32_t)(b0 & 0x1Fu) << 6) | (uint32_t)(b1 & 0x3Fu);\n");
        out.append("            i += 2;\n");
        out.append("        } else if ((b0 & 0xF0u) == 0xE0u) {\n");
        out.append("            uint8_t b1 = bytes[i + 1];\n");
        out.append("            uint8_t b2 = bytes[i + 2];\n");
        out.append("            if ((b1 & 0xC0u) != 0x80u || (b2 & 0xC0u) != 0x80u) { free(chars); return NULL; }\n");
        out.append("            code_point = ((uint32_t)(b0 & 0x0Fu) << 12)\n");
        out.append("                       | ((uint32_t)(b1 & 0x3Fu) << 6)\n");
        out.append("                       | (uint32_t)(b2 & 0x3Fu);\n");
        out.append("            i += 3;\n");
        out.append("        } else {\n");
        out.append("            uint8_t b1 = bytes[i + 1];\n");
        out.append("            uint8_t b2 = bytes[i + 2];\n");
        out.append("            uint8_t b3 = bytes[i + 3];\n");
        out.append("            if ((b1 & 0xC0u) != 0x80u || (b2 & 0xC0u) != 0x80u || (b3 & 0xC0u) != 0x80u) { free(chars); return NULL; }\n");
        out.append("            code_point = ((uint32_t)(b0 & 0x07u) << 18)\n");
        out.append("                       | ((uint32_t)(b1 & 0x3Fu) << 12)\n");
        out.append("                       | ((uint32_t)(b2 & 0x3Fu) << 6)\n");
        out.append("                       | (uint32_t)(b3 & 0x3Fu);\n");
        out.append("            i += 4;\n");
        out.append("        }\n");
        out.append("        if (code_point <= 0xFFFFu) {\n");
        out.append("            chars[out_index++] = (jchar)code_point;\n");
        out.append("        } else {\n");
        out.append("            uint32_t value = code_point - 0x10000u;\n");
        out.append("            chars[out_index++] = (jchar)(0xD800u | ((value >> 10) & 0x3FFu));\n");
        out.append("            chars[out_index++] = (jchar)(0xDC00u | (value & 0x3FFu));\n");
        out.append("        }\n");
        out.append("    }\n");
        out.append("    jstring result = (*env)->NewString(env, chars, (jsize)utf16_length);\n");
        out.append("    free(chars);\n");
        out.append("    return result;\n");
        out.append("}\n\n");
        out.append(linkage).append("char* ").append(runtimeNames.decodeMetaCString()).append("(const uint8_t* bytes, size_t length, uint8_t seed) {\n");
        out.append("    char* value = (char*)malloc(length + 1);\n");
        out.append("    if (value == NULL) return NULL;\n");
        out.append("    for (size_t i = 0; i < length; i++) {\n");
        out.append("        uint8_t mask = (uint8_t)(seed + (uint8_t)(i * ").append(obfuscatedU32(runtimeNames.decodeU32(), 29, 29)).append("));\n");
        out.append("        value[i] = (char)(bytes[i] ^ mask);\n");
        out.append("    }\n");
        out.append("    value[length] = '\\0';\n");
        out.append("    return value;\n");
        out.append("}\n\n");
        out.append(linkage).append("jclass ").append(runtimeNames.findClassObf()).append("(JNIEnv* env, const uint8_t* bytes, size_t length, uint8_t seed) {\n");
        out.append("    char* name = ").append(runtimeNames.decodeMetaCString()).append("(bytes, length, seed);\n");
        out.append("    if (name == NULL) return NULL;\n");
        out.append("    jclass clazz = (*env)->FindClass(env, name);\n");
        out.append("    free(name);\n");
        out.append("    return clazz;\n");
        out.append("}\n\n");
        out.append(linkage).append("jstring ").append(runtimeNames.newStringUtfObf()).append("(JNIEnv* env, const uint8_t* bytes, size_t length, uint8_t seed) {\n");
        out.append("    char* value = ").append(runtimeNames.decodeMetaCString()).append("(bytes, length, seed);\n");
        out.append("    if (value == NULL) return NULL;\n");
        out.append("    jstring stringValue = (*env)->NewStringUTF(env, value);\n");
        out.append("    free(value);\n");
        out.append("    return stringValue;\n");
        out.append("}\n\n");
        out.append(linkage).append("jmethodID ").append(runtimeNames.getMethodIdObf()).append("(JNIEnv* env, jclass clazz, uint8_t is_static,\n");
        out.append("        const uint8_t* name_bytes, size_t name_length, uint8_t name_seed,\n");
        out.append("        const uint8_t* desc_bytes, size_t desc_length, uint8_t desc_seed) {\n");
        out.append("    char* name = ").append(runtimeNames.decodeMetaCString()).append("(name_bytes, name_length, name_seed);\n");
        out.append("    if (name == NULL) return NULL;\n");
        out.append("    char* desc = ").append(runtimeNames.decodeMetaCString()).append("(desc_bytes, desc_length, desc_seed);\n");
        out.append("    if (desc == NULL) { free(name); return NULL; }\n");
        out.append("    jmethodID method = is_static\n");
        out.append("            ? (*env)->GetStaticMethodID(env, clazz, name, desc)\n");
        out.append("            : (*env)->GetMethodID(env, clazz, name, desc);\n");
        out.append("    if (method == NULL) {\n");
        out.append("        fprintf(stderr, \"[ir] method lookup failed static=%u name=%s desc=%s\\n\", (unsigned)is_static, name, desc);\n");
        out.append("        if ((*env)->ExceptionCheck(env)) { (*env)->ExceptionDescribe(env); }\n");
        out.append("    }\n");
        out.append("    free(desc);\n");
        out.append("    free(name);\n");
        out.append("    return method;\n");
        out.append("}\n\n");
        out.append(linkage).append("jfieldID ").append(runtimeNames.getFieldIdObf()).append("(JNIEnv* env, jclass clazz, uint8_t is_static,\n");
        out.append("        const uint8_t* name_bytes, size_t name_length, uint8_t name_seed,\n");
        out.append("        const uint8_t* desc_bytes, size_t desc_length, uint8_t desc_seed) {\n");
        out.append("    char* name = ").append(runtimeNames.decodeMetaCString()).append("(name_bytes, name_length, name_seed);\n");
        out.append("    if (name == NULL) return NULL;\n");
        out.append("    char* desc = ").append(runtimeNames.decodeMetaCString()).append("(desc_bytes, desc_length, desc_seed);\n");
        out.append("    if (desc == NULL) { free(name); return NULL; }\n");
        out.append("    jfieldID field = is_static\n");
        out.append("            ? (*env)->GetStaticFieldID(env, clazz, name, desc)\n");
        out.append("            : (*env)->GetFieldID(env, clazz, name, desc);\n");
        out.append("    if (field == NULL) {\n");
        out.append("        fprintf(stderr, \"[ir] field lookup failed static=%u name=%s desc=%s\\n\", (unsigned)is_static, name, desc);\n");
        out.append("        if ((*env)->ExceptionCheck(env)) { (*env)->ExceptionDescribe(env); }\n");
        out.append("    }\n");
        out.append("    free(desc);\n");
        out.append("    free(name);\n");
        out.append("    return field;\n");
        out.append("}\n\n");
    }

    private void appendNativeBridgeDeclarations(StringBuilder out, NativeRegistrationPlan registrationPlan) {
        if (registrationPlan.classes().isEmpty()) {
            return;
        }
        for (NativeRegistrationPlan.ClassRegistration classRegistration : registrationPlan.classes()) {
            for (NativeRegistrationPlan.MethodRegistration method : classRegistration.methods()) {
                out.append("extern void ").append(method.bridgeSymbol()).append("(void);\n");
            }
        }
        out.append('\n');
    }

    private void appendRegistrationSupport(StringBuilder out, NativeRegistrationPlan registrationPlan, String loaderInternalName) {
        if (registrationPlan.classes().isEmpty() || loaderInternalName == null || loaderInternalName.isBlank()) {
            return;
        }

        String entryTypeName = registrationEntryTypeName();
        out.append("typedef struct {\n");
        out.append("    const uint8_t* name_bytes;\n");
        out.append("    size_t name_length;\n");
        out.append("    uint8_t name_seed;\n");
        out.append("    const uint8_t* desc_bytes;\n");
        out.append("    size_t desc_length;\n");
        out.append("    uint8_t desc_seed;\n");
        out.append("    void* fn_ptr;\n");
        out.append("} ").append(entryTypeName).append(";\n\n");

        for (NativeRegistrationPlan.ClassRegistration classRegistration : registrationPlan.classes()) {
            appendClassRegistrationHelper(out, classRegistration, entryTypeName);
        }

        out.append("static IR_NOINLINE void JNICALL ").append(runtimeNames.registerNativesForClass()).append("(JNIEnv* env, jclass loader_class, jint index, jclass clazz) {\n");
        out.append("    (void)loader_class;\n");
        out.append("    switch (index) {\n");
        for (NativeRegistrationPlan.ClassRegistration classRegistration : registrationPlan.classes()) {
            out.append("        case ").append(classRegistration.index()).append(":\n");
            out.append("            ").append(registrationHelperName(classRegistration.index())).append("(env, clazz);\n");
            out.append("            return;\n");
        }
        out.append("        default:\n");
        out.append("            return;\n");
        out.append("    }\n");
        out.append("}\n\n");

        out.append("JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {\n");
        out.append("    (void)reserved;\n");
        out.append("    JNIEnv* env = NULL;\n");
        out.append("    if ((*vm)->GetEnv(vm, (void**)&env, JNI_VERSION_1_8) != JNI_OK || env == NULL) return JNI_ERR;\n");
        appendEncodedCString(out, "loader_method_name", "registerNativesForClass", 4);
        appendEncodedCString(out, "loader_method_desc", "(ILjava/lang/Class;)V", 4);
        out.append("    char* loader_method_name_cstr = ").append(runtimeNames.decodeMetaCString()).append("(loader_method_name, loader_method_name_len, loader_method_name_seed);\n");
        out.append("    char* loader_method_desc_cstr = ").append(runtimeNames.decodeMetaCString()).append("(loader_method_desc, loader_method_desc_len, loader_method_desc_seed);\n");
        out.append("    if (loader_method_name_cstr == NULL || loader_method_desc_cstr == NULL) return JNI_ERR;\n");
        out.append("    JNINativeMethod loader_methods[] = {\n");
        out.append("        { loader_method_name_cstr, loader_method_desc_cstr, (void*)&").append(runtimeNames.registerNativesForClass()).append(" }\n");
        out.append("    };\n");
        appendEncodedCString(out, "loader_internal_name", loaderInternalName, 4);
        out.append("    jclass loader_class = ").append(runtimeNames.findClassObf()).append("(env, loader_internal_name, loader_internal_name_len, loader_internal_name_seed);\n");
        out.append("    if (loader_class == NULL) return JNI_ERR;\n");
        out.append("    if ((*env)->RegisterNatives(env, loader_class, loader_methods, 1) != 0) {\n");
        out.append("        (*env)->DeleteLocalRef(env, loader_class);\n");
        out.append("        return JNI_ERR;\n");
        out.append("    }\n");
        out.append("    (*env)->DeleteLocalRef(env, loader_class);\n");
        out.append("    return JNI_VERSION_1_8;\n");
        out.append("}\n\n");
    }

    private void appendClassRegistrationHelper(StringBuilder out, NativeRegistrationPlan.ClassRegistration classRegistration, String entryTypeName) {
        out.append("static IR_NOINLINE void ").append(registrationHelperName(classRegistration.index())).append("(JNIEnv* env, jclass clazz) {\n");
        int methodIndex = 0;
        for (NativeRegistrationPlan.MethodRegistration method : classRegistration.methods()) {
            appendEncodedCString(out, "method_name_" + classRegistration.index() + "_" + methodIndex, method.name(), 12);
            appendEncodedCString(out, "method_desc_" + classRegistration.index() + "_" + methodIndex, method.descriptor(), 12);
            methodIndex++;
        }
        out.append("    static const ").append(entryTypeName).append(" entries[] = {\n");
        methodIndex = 0;
        for (NativeRegistrationPlan.MethodRegistration method : classRegistration.methods()) {
            out.append("        { method_name_").append(classRegistration.index()).append('_').append(methodIndex)
                    .append(", method_name_").append(classRegistration.index()).append('_').append(methodIndex).append("_len")
                    .append(", method_name_").append(classRegistration.index()).append('_').append(methodIndex).append("_seed")
                    .append(", method_desc_").append(classRegistration.index()).append('_').append(methodIndex)
                    .append(", method_desc_").append(classRegistration.index()).append('_').append(methodIndex).append("_len")
                    .append(", method_desc_").append(classRegistration.index()).append('_').append(methodIndex).append("_seed")
                    .append(", (void*)&").append(method.bridgeSymbol()).append(" },\n");
            methodIndex++;
        }
        out.append("    };\n");
        out.append("    const int method_count = (int)(sizeof(entries) / sizeof(entries[0]));\n");
        out.append("    JNINativeMethod* methods = (JNINativeMethod*)calloc((size_t)method_count, sizeof(JNINativeMethod));\n");
        out.append("    if (methods == NULL) return;\n");
        out.append("    for (int method_index = 0; method_index < method_count; method_index++) {\n");
        out.append("        methods[method_index].name = ").append(runtimeNames.decodeMetaCString()).append("(entries[method_index].name_bytes, entries[method_index].name_length, entries[method_index].name_seed);\n");
        out.append("        methods[method_index].signature = ").append(runtimeNames.decodeMetaCString()).append("(entries[method_index].desc_bytes, entries[method_index].desc_length, entries[method_index].desc_seed);\n");
        out.append("        methods[method_index].fnPtr = entries[method_index].fn_ptr;\n");
        out.append("        if (methods[method_index].name == NULL || methods[method_index].signature == NULL) {\n");
        out.append("            for (int cleanup_index = 0; cleanup_index < method_count; cleanup_index++) {\n");
        out.append("                if (methods[cleanup_index].name != NULL) free((void*)methods[cleanup_index].name);\n");
        out.append("                if (methods[cleanup_index].signature != NULL) free((void*)methods[cleanup_index].signature);\n");
        out.append("            }\n");
        out.append("            free(methods);\n");
        out.append("            return;\n");
        out.append("        }\n");
        out.append("    }\n");
        out.append("    (*env)->RegisterNatives(env, clazz, methods, method_count);\n");
        out.append("    for (int method_index = 0; method_index < method_count; method_index++) {\n");
        out.append("        free((void*)methods[method_index].name);\n");
        out.append("        free((void*)methods[method_index].signature);\n");
        out.append("    }\n");
        out.append("    free(methods);\n");
        out.append("}\n\n");
    }

    private String registrationHelperName(int classIndex) {
        return JniMangler.opaqueSymbol("runtime|register-class|" + classIndex, 24);
    }

    private String registrationEntryTypeName() {
        return JniMangler.opaqueSymbol("runtime|register-entry", 20);
    }

    private List<String> parseParams(String raw) {
        if (raw.isBlank()) {
            return List.of();
        }
        String[] parts = raw.split(",");
        ArrayList<String> parameters = new ArrayList<>(parts.length);
        for (String part : parts) {
            parameters.add(part.trim());
        }
        return List.copyOf(parameters);
    }

    private void appendHelper(StringBuilder out, String emittedName, String semanticName, Sig sig) {
        out.append(cType(sig.returnType())).append(' ').append(emittedName).append('(').append(renderParams(sig.params())).append(") {\n");
        Helper helper = parseHelper(semanticName, sig);
        appendOpaqueHelperPreamble(out, emittedName, semanticName, sig);
        switch (helper.kind()) {
            case THROW -> {
                out.append("    JNIEnv* env = ").append(runtimeNames.envAccessor()).append("();\n");
                out.append("    if (env == NULL) return;\n");
                out.append("    (*env)->Throw(env, (jthrowable)arg0);\n");
            }
            case CURRENT_EXCEPTION -> {
                out.append("    JNIEnv* env = ").append(runtimeNames.envAccessor()).append("();\n");
                out.append("    if (env == NULL) return NULL;\n");
                out.append("    jthrowable pending = (*env)->ExceptionOccurred(env);\n");
                out.append("    if (pending == NULL) return NULL;\n");
                out.append("    (*env)->ExceptionClear(env);\n");
                out.append("    return (void*)pending;\n");
            }
            case EXCEPTION_PENDING -> {
                out.append("    JNIEnv* env = ").append(runtimeNames.envAccessor()).append("();\n");
                out.append("    if (env == NULL) return 0;\n");
                out.append("    return (uint8_t)((*env)->ExceptionCheck(env) ? 1 : 0);\n");
            }
            case NEW_OBJECT -> {
                out.append("    JNIEnv* env = ").append(runtimeNames.envAccessor()).append("();\n");
                out.append("    if (env == NULL) return NULL;\n");
                appendEncodedCString(out, "owner_name", helper.owner(), 4);
                out.append("    jclass clazz = ").append(runtimeNames.findClassObf()).append("(env, owner_name, owner_name_len, owner_name_seed);\n");
                out.append("    if (clazz == NULL) return NULL;\n");
                out.append("    jobject object = (*env)->AllocObject(env, clazz);\n");
                out.append("    (*env)->DeleteLocalRef(env, clazz);\n");
                out.append("    return object;\n");
            }
            case NEW_INIT -> appendNewInitHelper(out, helper, sig);
            case LDC_STRING -> {
                out.append("    JNIEnv* env = ").append(runtimeNames.envAccessor()).append("();\n");
                out.append("    if (env == NULL) return NULL;\n");
                out.append("    static const unsigned char bytes[] = {")
                        .append(renderHexBytes(encodeModifiedUtf8(helper.payload())))
                        .append("0x00};\n");
                out.append("    return (void*)(*env)->NewStringUTF(env, (const char*)bytes);\n");
            }
            case OBFUSCATED_STRING -> appendObfuscatedStringHelper(out, helper);
            case LDC_CLASS -> appendClassLiteralHelper(out, helper);
            case CONCAT -> appendConcatHelper(out, helper);
            case OBFUSCATED_CONCAT -> appendObfuscatedConcatHelper(out, helper);
            case LAMBDA -> appendLambdaHelper(out, helper);
            case INSTANCEOF -> appendInstanceOfHelper(out, helper);
            case REF_CMP -> appendReferenceCompareHelper(out, helper);
            case CMP -> appendCmpHelper(out, helper, sig);
            case TYPE_SWITCH -> appendTypeSwitchHelper(out, helper);
            case RECORD -> appendRecordHelper(out, helper, sig);
            case FIELD -> appendFieldHelper(out, helper, sig);
            case CALL -> appendCallHelper(out, helper, sig);
            case ARRAY_NEW -> appendArrayNewHelper(out, helper);
            case ARRAY_MULTI_NEW -> appendMultiArrayNewHelper(out, helper);
            case ARRAY_LOAD -> appendArrayLoadHelper(out, helper, sig);
            case ARRAY_STORE -> appendArrayStoreHelper(out, helper);
            case ARRAY_LENGTH -> {
                out.append("    JNIEnv* env = ").append(runtimeNames.envAccessor()).append("();\n");
                out.append("    if (env == NULL) return 0;\n");
                out.append("    return (int32_t)(*env)->GetArrayLength(env, (jarray)arg0);\n");
            }
            case MONITOR -> appendMonitorHelper(out, helper);
            case DEFAULT -> appendDefaultHelper(out, sig);
        }
        out.append("}\n");
    }

    private void appendOpaqueHelperPreamble(StringBuilder out, String emittedName, String semanticName, Sig sig) {
        long seed = Integer.toUnsignedLong((emittedName + "|" + semanticName + "|" + sig.returnType() + "|" + sig.params().size()).hashCode());
        if (seed == 0L) {
            seed = 0x13579BDFL;
        }
        out.append("    if (").append(runtimeNames.opaqueGate()).append("(")
                .append(renderUnsignedIntLiteral(seed)).append("u) == 0u) { ")
                .append(defaultReturn(sig.returnType())).append(" }\n");
    }

    private void appendFieldHelper(StringBuilder out, Helper helper, Sig sig) {
        out.append("    JNIEnv* env = ").append(runtimeNames.envAccessor()).append("();\n");
        out.append("    if (env == NULL) { ").append(defaultReturn(sig.returnType())).append(" }\n");
        appendEncodedCString(out, "owner_name", helper.owner(), 4);
        appendEncodedCString(out, "field_name", helper.name(), 4);
        appendEncodedCString(out, "field_desc", helper.descriptor(), 4);
        if (helper.isStatic()) {
            out.append("    jclass clazz = ").append(runtimeNames.findClassObf()).append("(env, owner_name, owner_name_len, owner_name_seed);\n");
            out.append("    if (clazz == NULL) { ").append(defaultReturn(sig.returnType())).append(" }\n");
            out.append("    jfieldID field = ").append(runtimeNames.getFieldIdObf()).append("(env, clazz, 1, field_name, field_name_len, field_name_seed, field_desc, field_desc_len, field_desc_seed);\n");
            out.append("    if (field == NULL) { (*env)->DeleteLocalRef(env, clazz); ").append(defaultReturn(sig.returnType())).append(" }\n");
            if (helper.isLoad()) {
                out.append("    ").append(cType(sig.returnType())).append(" value = ").append(fieldGetter(true, helper.displayType())).append(";\n");
                out.append("    (*env)->DeleteLocalRef(env, clazz);\n    return value;\n");
            } else {
                out.append("    ").append(fieldSetter(true, helper.displayType(), 0)).append(";\n");
                out.append("    (*env)->DeleteLocalRef(env, clazz);\n    return;\n");
            }
            return;
        }
        out.append("    jobject owner = (jobject)arg0;\n");
        out.append("    jclass clazz = ").append(runtimeNames.findClassObf()).append("(env, owner_name, owner_name_len, owner_name_seed);\n");
        out.append("    if (clazz == NULL) { ").append(defaultReturn(sig.returnType())).append(" }\n");
        out.append("    jfieldID field = ").append(runtimeNames.getFieldIdObf()).append("(env, clazz, 0, field_name, field_name_len, field_name_seed, field_desc, field_desc_len, field_desc_seed);\n");
        out.append("    if (field == NULL) { (*env)->DeleteLocalRef(env, clazz); ").append(defaultReturn(sig.returnType())).append(" }\n");
        if (helper.isLoad()) {
            out.append("    ").append(cType(sig.returnType())).append(" value = ").append(fieldGetter(false, helper.displayType())).append(";\n");
            out.append("    (*env)->DeleteLocalRef(env, clazz);\n    return value;\n");
        } else {
            out.append("    ").append(fieldSetter(false, helper.displayType(), 1)).append(";\n");
            out.append("    (*env)->DeleteLocalRef(env, clazz);\n    return;\n");
        }
    }

    private void appendCallHelper(StringBuilder out, Helper helper, Sig sig) {
        if (isMethodHandlePolymorphicInvoke(helper)) {
            appendMethodHandlePolymorphicCallHelper(out, helper, sig);
            return;
        }
        String ownerClassName = classLookupName(helper.owner());
        String methodName = runtimeMethodName(helper);
        out.append("    JNIEnv* env = ").append(runtimeNames.envAccessor()).append("();\n");
        out.append("    if (env == NULL) { ").append(defaultReturn(sig.returnType())).append(" }\n");
        if (!helper.isStatic()) {
            out.append("    jobject receiver = (jobject)arg0;\n");
        }
        appendEncodedCString(out, "owner_name", ownerClassName, 4);
        appendEncodedCString(out, "method_name", methodName, 4);
        appendEncodedCString(out, "method_desc", helper.descriptor(), 4);
        out.append("    jclass clazz = ").append(runtimeNames.findClassObf()).append("(env, owner_name, owner_name_len, owner_name_seed);\n");
        out.append("    if (clazz == NULL) { ").append(defaultReturn(sig.returnType())).append(" }\n");
        out.append("    jmethodID method = ").append(runtimeNames.getMethodIdObf()).append("(env, clazz, ")
                .append(helper.isStatic() ? "1" : "0")
                .append(", method_name, method_name_len, method_name_seed, method_desc, method_desc_len, method_desc_seed);\n");
        out.append("    if (method == NULL) { (*env)->DeleteLocalRef(env, clazz); ").append(defaultReturn(sig.returnType())).append(" }\n");
        int valueArgs = sig.params().size() - (helper.isStatic() ? 0 : 1);
        out.append("    jvalue args[").append(Math.max(1, valueArgs)).append("];\n");
        for (int index = 0; index < valueArgs; index++) {
            int argIndex = helper.isStatic() ? index : index + 1;
            out.append("    ").append(jvalueAssign("args[" + index + "]", helper.paramTypes().get(index), "arg" + argIndex)).append('\n');
        }
        String expr = callExpr(helper);
        if (helper.returnType().equals("void")) {
            out.append("    ").append(expr).append(";\n");
            out.append("    (*env)->DeleteLocalRef(env, clazz);\n    return;\n");
        } else {
            out.append("    ").append(cType(sig.returnType())).append(" result = ").append(castReturn(helper.returnType(), expr)).append(";\n");
            out.append("    (*env)->DeleteLocalRef(env, clazz);\n    return result;\n");
        }
    }

    private void appendNewInitHelper(StringBuilder out, Helper helper, Sig sig) {
        out.append("    JNIEnv* env = ").append(runtimeNames.envAccessor()).append("();\n");
        out.append("    if (env == NULL) return NULL;\n");
        appendEncodedCString(out, "owner_name", helper.owner(), 4);
        appendEncodedCString(out, "ctor_name", "<init>", 4);
        appendEncodedCString(out, "ctor_desc", methodDesc(helper.paramTypes(), "void"), 4);
        out.append("    jclass clazz = ").append(runtimeNames.findClassObf()).append("(env, owner_name, owner_name_len, owner_name_seed);\n");
        out.append("    if (clazz == NULL) return NULL;\n");
        out.append("    jmethodID method = ").append(runtimeNames.getMethodIdObf()).append("(env, clazz, 0, ctor_name, ctor_name_len, ctor_name_seed, ctor_desc, ctor_desc_len, ctor_desc_seed);\n");
        out.append("    if (method == NULL) { (*env)->DeleteLocalRef(env, clazz); return NULL; }\n");
        out.append("    jvalue args[").append(Math.max(1, sig.params().size())).append("];\n");
        for (int index = 0; index < sig.params().size(); index++) {
            out.append("    ").append(jvalueAssign("args[" + index + "]", helper.paramTypes().get(index), "arg" + index)).append('\n');
        }
        out.append("    jobject object = (*env)->NewObjectA(env, clazz, method, args);\n");
        out.append("    (*env)->DeleteLocalRef(env, clazz);\n");
        out.append("    return (void*)object;\n");
    }

    private void appendMethodHandlePolymorphicCallHelper(StringBuilder out, Helper helper, Sig sig) {
        out.append("    JNIEnv* env = ").append(runtimeNames.envAccessor()).append("();\n");
        out.append("    if (env == NULL) { ").append(defaultReturn(sig.returnType())).append(" }\n");
        out.append("    jobject receiver = (jobject)arg0;\n");
        appendFindClassLookup(out, "methodHandleClass", "java/lang/invoke/MethodHandle", 4);
        out.append("    if (methodHandleClass == NULL) { ").append(defaultReturn(sig.returnType())).append(" }\n");
        appendGetMethodIdLookup(out, "invokeWithArguments", "methodHandleClass", false, "invokeWithArguments", "([Ljava/lang/Object;)Ljava/lang/Object;", 4);
        out.append("    if (invokeWithArguments == NULL) { (*env)->DeleteLocalRef(env, methodHandleClass); ").append(defaultReturn(sig.returnType())).append(" }\n");
        appendFindClassLookup(out, "objectClass", "java/lang/Object", 4);
        out.append("    if (objectClass == NULL) { (*env)->DeleteLocalRef(env, methodHandleClass); ").append(defaultReturn(sig.returnType())).append(" }\n");
        out.append("    jobjectArray argsArray = (*env)->NewObjectArray(env, ").append(helper.paramTypes().size()).append(", objectClass, NULL);\n");
        out.append("    if (argsArray == NULL) { (*env)->DeleteLocalRef(env, objectClass); (*env)->DeleteLocalRef(env, methodHandleClass); ").append(defaultReturn(sig.returnType())).append(" }\n");
        for (int index = 0; index < helper.paramTypes().size(); index++) {
            String boxedName = "boxedArg" + index;
            appendBoxedArgument(out, helper.paramTypes().get(index), "arg" + (index + 1), boxedName, defaultFailureLiteral(sig.returnType()));
            out.append("    (*env)->SetObjectArrayElement(env, argsArray, ").append(index).append(", ").append(boxedName).append(");\n");
            out.append("    if ((*env)->ExceptionCheck(env)) { (*env)->DeleteLocalRef(env, argsArray); (*env)->DeleteLocalRef(env, objectClass); (*env)->DeleteLocalRef(env, methodHandleClass); ").append(defaultReturn(sig.returnType())).append(" }\n");
        }
        out.append("    jobject result = (*env)->CallObjectMethod(env, receiver, invokeWithArguments, argsArray);\n");
        out.append("    (*env)->DeleteLocalRef(env, argsArray);\n");
        out.append("    (*env)->DeleteLocalRef(env, objectClass);\n");
        out.append("    (*env)->DeleteLocalRef(env, methodHandleClass);\n");
        if ("void".equals(helper.returnType())) {
            out.append("    return;\n");
            return;
        }
        appendMethodHandleReturn(out, helper.returnType(), "result", sig.returnType());
    }

    private void appendArrayNewHelper(StringBuilder out, Helper helper) {
        out.append("    JNIEnv* env = ").append(runtimeNames.envAccessor()).append("();\n");
        out.append("    if (env == NULL) return NULL;\n");
        switch (helper.displayType()) {
            case "boolean[]" -> out.append("    return (void*)(*env)->NewBooleanArray(env, (jsize)arg0);\n");
            case "byte[]" -> out.append("    return (void*)(*env)->NewByteArray(env, (jsize)arg0);\n");
            case "char[]" -> out.append("    return (void*)(*env)->NewCharArray(env, (jsize)arg0);\n");
            case "short[]" -> out.append("    return (void*)(*env)->NewShortArray(env, (jsize)arg0);\n");
            case "int[]" -> out.append("    return (void*)(*env)->NewIntArray(env, (jsize)arg0);\n");
            case "long[]" -> out.append("    return (void*)(*env)->NewLongArray(env, (jsize)arg0);\n");
            case "float[]" -> out.append("    return (void*)(*env)->NewFloatArray(env, (jsize)arg0);\n");
            case "double[]" -> out.append("    return (void*)(*env)->NewDoubleArray(env, (jsize)arg0);\n");
            default -> {
                appendEncodedCString(out, "element_class_name", arrayComponentClassName(helper.displayType()), 4);
                out.append("    jclass elementClass = ").append(runtimeNames.findClassObf())
                        .append("(env, element_class_name, element_class_name_len, element_class_name_seed);\n");
                out.append("    if (elementClass == NULL) return NULL;\n");
                out.append("    jobjectArray array = (*env)->NewObjectArray(env, (jsize)arg0, elementClass, NULL);\n");
                out.append("    (*env)->DeleteLocalRef(env, elementClass);\n");
                out.append("    return (void*)array;\n");
            }
        }
    }

    private void appendMultiArrayNewHelper(StringBuilder out, Helper helper) {
        int dimensions = helper.sig().params().size();
        String fullDescriptor = desc(helper.displayType());
        if (dimensions <= 0 || dimensions > fullDescriptor.length()) {
            out.append("    return NULL;\n");
            return;
        }
        String componentDescriptor = fullDescriptor.substring(dimensions);
        out.append("    JNIEnv* env = ").append(runtimeNames.envAccessor()).append("();\n");
        out.append("    if (env == NULL) return NULL;\n");
        appendClassLoadFromDescriptor(out, "componentClass", componentDescriptor);
        out.append("    if (componentClass == NULL) return NULL;\n");
        appendFindClassLookup(out, "reflectArrayClass", "java/lang/reflect/Array", 4);
        out.append("    if (reflectArrayClass == NULL) { (*env)->DeleteLocalRef(env, componentClass); return NULL; }\n");
        appendGetMethodIdLookup(out, "newInstance", "reflectArrayClass", true, "newInstance", "(Ljava/lang/Class;[I)Ljava/lang/Object;", 4);
        out.append("    if (newInstance == NULL) { (*env)->DeleteLocalRef(env, reflectArrayClass); (*env)->DeleteLocalRef(env, componentClass); return NULL; }\n");
        out.append("    jint dims[").append(Math.max(1, dimensions)).append("];\n");
        for (int index = 0; index < dimensions; index++) {
            out.append("    dims[").append(index).append("] = (jint)arg").append(index).append(";\n");
        }
        out.append("    jintArray dimsArray = (*env)->NewIntArray(env, ").append(dimensions).append(");\n");
        out.append("    if (dimsArray == NULL) { (*env)->DeleteLocalRef(env, reflectArrayClass); (*env)->DeleteLocalRef(env, componentClass); return NULL; }\n");
        out.append("    (*env)->SetIntArrayRegion(env, dimsArray, 0, ").append(dimensions).append(", dims);\n");
        out.append("    if ((*env)->ExceptionCheck(env)) { (*env)->DeleteLocalRef(env, dimsArray); (*env)->DeleteLocalRef(env, reflectArrayClass); (*env)->DeleteLocalRef(env, componentClass); return NULL; }\n");
        out.append("    jobject array = (*env)->CallStaticObjectMethod(env, reflectArrayClass, newInstance, componentClass, dimsArray);\n");
        out.append("    (*env)->DeleteLocalRef(env, dimsArray);\n");
        out.append("    (*env)->DeleteLocalRef(env, reflectArrayClass);\n");
        out.append("    (*env)->DeleteLocalRef(env, componentClass);\n");
        out.append("    return (void*)array;\n");
    }

    private void appendArrayLoadHelper(StringBuilder out, Helper helper, Sig sig) {
        out.append("    JNIEnv* env = ").append(runtimeNames.envAccessor()).append("();\n");
        out.append("    if (env == NULL) { ").append(defaultReturn(sig.returnType())).append(" }\n");
        switch (helper.displayType()) {
            case "boolean[]" -> {
                out.append("    jboolean value = 0;\n");
                out.append("    (*env)->GetBooleanArrayRegion(env, (jbooleanArray)arg0, (jsize)arg1, 1, &value);\n");
                out.append("    return (uint8_t)(value ? 1 : 0);\n");
            }
            case "byte[]" -> {
                out.append("    jbyte value = 0;\n");
                out.append("    (*env)->GetByteArrayRegion(env, (jbyteArray)arg0, (jsize)arg1, 1, &value);\n");
                out.append("    return (int8_t)value;\n");
            }
            case "char[]" -> {
                out.append("    jchar value = 0;\n");
                out.append("    (*env)->GetCharArrayRegion(env, (jcharArray)arg0, (jsize)arg1, 1, &value);\n");
                out.append("    return (uint16_t)value;\n");
            }
            case "short[]" -> {
                out.append("    jshort value = 0;\n");
                out.append("    (*env)->GetShortArrayRegion(env, (jshortArray)arg0, (jsize)arg1, 1, &value);\n");
                out.append("    return (int16_t)value;\n");
            }
            case "int[]" -> {
                out.append("    jint value = 0;\n");
                out.append("    (*env)->GetIntArrayRegion(env, (jintArray)arg0, (jsize)arg1, 1, &value);\n");
                out.append("    return (int32_t)value;\n");
            }
            case "long[]" -> {
                out.append("    jlong value = 0;\n");
                out.append("    (*env)->GetLongArrayRegion(env, (jlongArray)arg0, (jsize)arg1, 1, &value);\n");
                out.append("    return (int64_t)value;\n");
            }
            case "float[]" -> {
                out.append("    jfloat value = 0;\n");
                out.append("    (*env)->GetFloatArrayRegion(env, (jfloatArray)arg0, (jsize)arg1, 1, &value);\n");
                out.append("    return (float)value;\n");
            }
            case "double[]" -> {
                out.append("    jdouble value = 0;\n");
                out.append("    (*env)->GetDoubleArrayRegion(env, (jdoubleArray)arg0, (jsize)arg1, 1, &value);\n");
                out.append("    return (double)value;\n");
            }
            default -> out.append("    return (void*)(*env)->GetObjectArrayElement(env, (jobjectArray)arg0, (jsize)arg1);\n");
        }
    }

    private void appendArrayStoreHelper(StringBuilder out, Helper helper) {
        out.append("    JNIEnv* env = ").append(runtimeNames.envAccessor()).append("();\n");
        out.append("    if (env == NULL) return;\n");
        switch (helper.displayType()) {
            case "boolean[]" -> {
                out.append("    jboolean value = arg2 ? JNI_TRUE : JNI_FALSE;\n");
                out.append("    (*env)->SetBooleanArrayRegion(env, (jbooleanArray)arg0, (jsize)arg1, 1, &value);\n");
            }
            case "byte[]" -> {
                out.append("    jbyte value = (jbyte)arg2;\n");
                out.append("    (*env)->SetByteArrayRegion(env, (jbyteArray)arg0, (jsize)arg1, 1, &value);\n");
            }
            case "char[]" -> {
                out.append("    jchar value = (jchar)arg2;\n");
                out.append("    (*env)->SetCharArrayRegion(env, (jcharArray)arg0, (jsize)arg1, 1, &value);\n");
            }
            case "short[]" -> {
                out.append("    jshort value = (jshort)arg2;\n");
                out.append("    (*env)->SetShortArrayRegion(env, (jshortArray)arg0, (jsize)arg1, 1, &value);\n");
            }
            case "int[]" -> {
                out.append("    jint value = (jint)arg2;\n");
                out.append("    (*env)->SetIntArrayRegion(env, (jintArray)arg0, (jsize)arg1, 1, &value);\n");
            }
            case "long[]" -> {
                out.append("    jlong value = (jlong)arg2;\n");
                out.append("    (*env)->SetLongArrayRegion(env, (jlongArray)arg0, (jsize)arg1, 1, &value);\n");
            }
            case "float[]" -> {
                out.append("    jfloat value = (jfloat)arg2;\n");
                out.append("    (*env)->SetFloatArrayRegion(env, (jfloatArray)arg0, (jsize)arg1, 1, &value);\n");
            }
            case "double[]" -> {
                out.append("    jdouble value = (jdouble)arg2;\n");
                out.append("    (*env)->SetDoubleArrayRegion(env, (jdoubleArray)arg0, (jsize)arg1, 1, &value);\n");
            }
            default -> out.append("    (*env)->SetObjectArrayElement(env, (jobjectArray)arg0, (jsize)arg1, (jobject)arg2);\n");
        }
        out.append("    return;\n");
    }

    private void appendMonitorHelper(StringBuilder out, Helper helper) {
        out.append("    JNIEnv* env = ").append(runtimeNames.envAccessor()).append("();\n");
        out.append("    if (env == NULL) return;\n");
        if ("enter".equals(helper.name())) {
            out.append("    (*env)->MonitorEnter(env, (jobject)arg0);\n");
        } else {
            out.append("    (*env)->MonitorExit(env, (jobject)arg0);\n");
        }
        out.append("    return;\n");
    }

    private void appendReferenceCompareHelper(StringBuilder out, Helper helper) {
        out.append("    JNIEnv* env = ").append(runtimeNames.envAccessor()).append("();\n");
        out.append("    if (env == NULL) return 0;\n");
        out.append("    uint8_t same = (uint8_t)((*env)->IsSameObject(env, (jobject)arg0, (jobject)arg1) ? 1 : 0);\n");
        if ("eq".equals(helper.name())) {
            out.append("    return same;\n");
        } else {
            out.append("    return (uint8_t)(same ? 0 : 1);\n");
        }
    }

    private void appendClassLiteralHelper(StringBuilder out, Helper helper) {
        out.append("    JNIEnv* env = ").append(runtimeNames.envAccessor()).append("();\n");
        out.append("    if (env == NULL) return NULL;\n");
        switch (helper.displayType()) {
            case "boolean" -> out.append("    jclass boxed = (*env)->FindClass(env, \"java/lang/Boolean\");\n")
                    .append("    if (boxed == NULL) return NULL;\n")
                    .append("    jfieldID field = (*env)->GetStaticFieldID(env, boxed, \"TYPE\", \"Ljava/lang/Class;\");\n")
                    .append("    if (field == NULL) { (*env)->DeleteLocalRef(env, boxed); return NULL; }\n")
                    .append("    jobject klass = (*env)->GetStaticObjectField(env, boxed, field);\n")
                    .append("    (*env)->DeleteLocalRef(env, boxed);\n")
                    .append("    return (void*)klass;\n");
            case "byte" -> out.append("    jclass boxed = (*env)->FindClass(env, \"java/lang/Byte\");\n")
                    .append("    if (boxed == NULL) return NULL;\n")
                    .append("    jfieldID field = (*env)->GetStaticFieldID(env, boxed, \"TYPE\", \"Ljava/lang/Class;\");\n")
                    .append("    if (field == NULL) { (*env)->DeleteLocalRef(env, boxed); return NULL; }\n")
                    .append("    jobject klass = (*env)->GetStaticObjectField(env, boxed, field);\n")
                    .append("    (*env)->DeleteLocalRef(env, boxed);\n")
                    .append("    return (void*)klass;\n");
            case "char" -> out.append("    jclass boxed = (*env)->FindClass(env, \"java/lang/Character\");\n")
                    .append("    if (boxed == NULL) return NULL;\n")
                    .append("    jfieldID field = (*env)->GetStaticFieldID(env, boxed, \"TYPE\", \"Ljava/lang/Class;\");\n")
                    .append("    if (field == NULL) { (*env)->DeleteLocalRef(env, boxed); return NULL; }\n")
                    .append("    jobject klass = (*env)->GetStaticObjectField(env, boxed, field);\n")
                    .append("    (*env)->DeleteLocalRef(env, boxed);\n")
                    .append("    return (void*)klass;\n");
            case "short" -> out.append("    jclass boxed = (*env)->FindClass(env, \"java/lang/Short\");\n")
                    .append("    if (boxed == NULL) return NULL;\n")
                    .append("    jfieldID field = (*env)->GetStaticFieldID(env, boxed, \"TYPE\", \"Ljava/lang/Class;\");\n")
                    .append("    if (field == NULL) { (*env)->DeleteLocalRef(env, boxed); return NULL; }\n")
                    .append("    jobject klass = (*env)->GetStaticObjectField(env, boxed, field);\n")
                    .append("    (*env)->DeleteLocalRef(env, boxed);\n")
                    .append("    return (void*)klass;\n");
            case "int" -> out.append("    jclass boxed = (*env)->FindClass(env, \"java/lang/Integer\");\n")
                    .append("    if (boxed == NULL) return NULL;\n")
                    .append("    jfieldID field = (*env)->GetStaticFieldID(env, boxed, \"TYPE\", \"Ljava/lang/Class;\");\n")
                    .append("    if (field == NULL) { (*env)->DeleteLocalRef(env, boxed); return NULL; }\n")
                    .append("    jobject klass = (*env)->GetStaticObjectField(env, boxed, field);\n")
                    .append("    (*env)->DeleteLocalRef(env, boxed);\n")
                    .append("    return (void*)klass;\n");
            case "long" -> out.append("    jclass boxed = (*env)->FindClass(env, \"java/lang/Long\");\n")
                    .append("    if (boxed == NULL) return NULL;\n")
                    .append("    jfieldID field = (*env)->GetStaticFieldID(env, boxed, \"TYPE\", \"Ljava/lang/Class;\");\n")
                    .append("    if (field == NULL) { (*env)->DeleteLocalRef(env, boxed); return NULL; }\n")
                    .append("    jobject klass = (*env)->GetStaticObjectField(env, boxed, field);\n")
                    .append("    (*env)->DeleteLocalRef(env, boxed);\n")
                    .append("    return (void*)klass;\n");
            case "float" -> out.append("    jclass boxed = (*env)->FindClass(env, \"java/lang/Float\");\n")
                    .append("    if (boxed == NULL) return NULL;\n")
                    .append("    jfieldID field = (*env)->GetStaticFieldID(env, boxed, \"TYPE\", \"Ljava/lang/Class;\");\n")
                    .append("    if (field == NULL) { (*env)->DeleteLocalRef(env, boxed); return NULL; }\n")
                    .append("    jobject klass = (*env)->GetStaticObjectField(env, boxed, field);\n")
                    .append("    (*env)->DeleteLocalRef(env, boxed);\n")
                    .append("    return (void*)klass;\n");
            case "double" -> out.append("    jclass boxed = (*env)->FindClass(env, \"java/lang/Double\");\n")
                    .append("    if (boxed == NULL) return NULL;\n")
                    .append("    jfieldID field = (*env)->GetStaticFieldID(env, boxed, \"TYPE\", \"Ljava/lang/Class;\");\n")
                    .append("    if (field == NULL) { (*env)->DeleteLocalRef(env, boxed); return NULL; }\n")
                    .append("    jobject klass = (*env)->GetStaticObjectField(env, boxed, field);\n")
                    .append("    (*env)->DeleteLocalRef(env, boxed);\n")
                    .append("    return (void*)klass;\n");
            default -> {
                String className = helper.displayType().endsWith("[]") ? desc(helper.displayType()) : helper.displayType();
                appendEncodedCString(out, "class_name", className, 4);
        out.append("    return (void*)").append(runtimeNames.findClassObf()).append("(env, class_name, class_name_len, class_name_seed);\n");
            }
        }
    }

    private void appendConcatHelper(StringBuilder out, Helper helper) {
        out.append("    JNIEnv* env = ").append(runtimeNames.envAccessor()).append("();\n");
        out.append("    if (env == NULL) return NULL;\n");
        appendFindClassLookup(out, "builderClass", "java/lang/StringBuilder", 4);
        out.append("    if (builderClass == NULL) return NULL;\n");
        appendGetMethodIdLookup(out, "init", "builderClass", false, "<init>", "()V", 4);
        appendGetMethodIdLookup(out, "appendString", "builderClass", false, "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;", 4);
        appendGetMethodIdLookup(out, "appendObject", "builderClass", false, "append", "(Ljava/lang/Object;)Ljava/lang/StringBuilder;", 4);
        appendGetMethodIdLookup(out, "appendBoolean", "builderClass", false, "append", "(Z)Ljava/lang/StringBuilder;", 4);
        appendGetMethodIdLookup(out, "appendChar", "builderClass", false, "append", "(C)Ljava/lang/StringBuilder;", 4);
        appendGetMethodIdLookup(out, "appendInt", "builderClass", false, "append", "(I)Ljava/lang/StringBuilder;", 4);
        appendGetMethodIdLookup(out, "appendLong", "builderClass", false, "append", "(J)Ljava/lang/StringBuilder;", 4);
        appendGetMethodIdLookup(out, "appendFloat", "builderClass", false, "append", "(F)Ljava/lang/StringBuilder;", 4);
        appendGetMethodIdLookup(out, "appendDouble", "builderClass", false, "append", "(D)Ljava/lang/StringBuilder;", 4);
        appendGetMethodIdLookup(out, "toString", "builderClass", false, "toString", "()Ljava/lang/String;", 4);
        out.append("    if (init == NULL || appendString == NULL || appendObject == NULL || appendBoolean == NULL || appendChar == NULL || appendInt == NULL || appendLong == NULL || appendFloat == NULL || appendDouble == NULL || toString == NULL) {\n");
        out.append("        (*env)->DeleteLocalRef(env, builderClass);\n");
        out.append("        return NULL;\n");
        out.append("    }\n");
        out.append("    jobject builder = (*env)->NewObject(env, builderClass, init);\n");
        out.append("    if (builder == NULL) {\n");
        out.append("        (*env)->DeleteLocalRef(env, builderClass);\n");
        out.append("        return NULL;\n");
        out.append("    }\n");

        String recipe = helper.payload();
        int cursor = 0;
        int argumentIndex = 0;
        while (cursor < recipe.length()) {
            int marker = recipe.indexOf('\u0001', cursor);
            if (marker < 0) {
                appendConcatLiteral(out, recipe.substring(cursor));
                break;
            }
            appendConcatLiteral(out, recipe.substring(cursor, marker));
            appendConcatArgument(out, helper.paramTypes().get(argumentIndex), argumentIndex);
            argumentIndex++;
            cursor = marker + 1;
        }

        out.append("    jobject result = (*env)->CallObjectMethod(env, builder, toString);\n");
        out.append("    (*env)->DeleteLocalRef(env, builder);\n");
        out.append("    (*env)->DeleteLocalRef(env, builderClass);\n");
        out.append("    return (void*)result;\n");
    }

    private void appendObfuscatedStringHelper(StringBuilder out, Helper helper) {
        ObfuscatedStringHelperData data = obfuscatedStringData(helper);
        out.append("    JNIEnv* env = ").append(runtimeNames.envAccessor()).append("();\n");
        out.append("    if (env == NULL) return NULL;\n");
        if (data.cacheStrings()) {
            out.append("    static jobject cached = NULL;\n");
            out.append("    if (cached != NULL) return (void*)cached;\n");
        }
        appendStaticByteArray(out, "nonce", data.nonceHex(), 12);
        appendStaticByteArray(out, "seed_a", data.seedAHex(), 32);
        appendStaticByteArray(out, "seed_b", data.seedBHex(), 32);
        appendStaticByteArray(out, "cipher", data.cipherHex(), hexByteLength(data.cipherHex()));
        out.append("    uint8_t key[32];\n");
        out.append("    ").append(runtimeNames.deriveStringKey()).append("(0x").append(String.format("%08x", data.siteId())).append("u, seed_a, seed_b, key);\n");
        out.append("    uint8_t plain[").append(Math.max(1, hexByteLength(data.cipherHex()))).append("];\n");
        out.append("    ").append(runtimeNames.chacha20Xor()).append("(key, nonce, cipher, cipher_len, plain);\n");
        out.append("    jstring decoded = ").append(runtimeNames.newUtf8String()).append("(env, plain, cipher_len);\n");
        out.append("    if (decoded == NULL) return NULL;\n");
        if (!data.cacheStrings()) {
            out.append("    return (void*)decoded;\n");
            return;
        }
        out.append("    cached = (*env)->NewGlobalRef(env, decoded);\n");
        out.append("    (*env)->DeleteLocalRef(env, decoded);\n");
        out.append("    if (cached == NULL) return NULL;\n");
        out.append("    return (void*)cached;\n");
    }

    private void appendObfuscatedConcatHelper(StringBuilder out, Helper helper) {
        ObfuscatedStringHelperData data = obfuscatedStringData(helper);
        out.append("    JNIEnv* env = ").append(runtimeNames.envAccessor()).append("();\n");
        out.append("    if (env == NULL) return NULL;\n");
        appendFindClassLookup(out, "builderClass", "java/lang/StringBuilder", 4);
        out.append("    if (builderClass == NULL) return NULL;\n");
        appendGetMethodIdLookup(out, "init", "builderClass", false, "<init>", "()V", 4);
        appendGetMethodIdLookup(out, "appendString", "builderClass", false, "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;", 4);
        appendGetMethodIdLookup(out, "appendObject", "builderClass", false, "append", "(Ljava/lang/Object;)Ljava/lang/StringBuilder;", 4);
        appendGetMethodIdLookup(out, "appendBoolean", "builderClass", false, "append", "(Z)Ljava/lang/StringBuilder;", 4);
        appendGetMethodIdLookup(out, "appendChar", "builderClass", false, "append", "(C)Ljava/lang/StringBuilder;", 4);
        appendGetMethodIdLookup(out, "appendInt", "builderClass", false, "append", "(I)Ljava/lang/StringBuilder;", 4);
        appendGetMethodIdLookup(out, "appendLong", "builderClass", false, "append", "(J)Ljava/lang/StringBuilder;", 4);
        appendGetMethodIdLookup(out, "appendFloat", "builderClass", false, "append", "(F)Ljava/lang/StringBuilder;", 4);
        appendGetMethodIdLookup(out, "appendDouble", "builderClass", false, "append", "(D)Ljava/lang/StringBuilder;", 4);
        appendGetMethodIdLookup(out, "toString", "builderClass", false, "toString", "()Ljava/lang/String;", 4);
        out.append("    if (init == NULL || appendString == NULL || appendObject == NULL || appendBoolean == NULL || appendChar == NULL || appendInt == NULL || appendLong == NULL || appendFloat == NULL || appendDouble == NULL || toString == NULL) return NULL;\n");
        out.append("    jobject builder = (*env)->NewObject(env, builderClass, init);\n");
        out.append("    if (builder == NULL) return NULL;\n");
        appendStaticByteArray(out, "nonce", data.nonceHex(), 12);
        appendStaticByteArray(out, "seed_a", data.seedAHex(), 32);
        appendStaticByteArray(out, "seed_b", data.seedBHex(), 32);
        appendStaticByteArray(out, "cipher", data.cipherHex(), hexByteLength(data.cipherHex()));
        out.append("    uint8_t key[32];\n");
        out.append("    ").append(runtimeNames.deriveStringKey()).append("(0x").append(String.format("%08x", data.siteId())).append("u, seed_a, seed_b, key);\n");
        out.append("    uint8_t recipe[").append(Math.max(1, hexByteLength(data.cipherHex()))).append("];\n");
        out.append("    ").append(runtimeNames.chacha20Xor()).append("(key, nonce, cipher, cipher_len, recipe);\n");
        out.append("    size_t literal_start = 0;\n");
        out.append("    int placeholder_index = 0;\n");
        out.append("    for (size_t cursor = 0; cursor < cipher_len; cursor++) {\n");
        out.append("        if (recipe[cursor] != 0x01) continue;\n");
        out.append("        if (cursor > literal_start) {\n");
            out.append("            jstring literal = ").append(runtimeNames.newUtf8String()).append("(env, recipe + literal_start, cursor - literal_start);\n");
        out.append("            if (literal == NULL) { (*env)->DeleteLocalRef(env, builder); (*env)->DeleteLocalRef(env, builderClass); return NULL; }\n");
        out.append("            (*env)->CallObjectMethod(env, builder, appendString, literal);\n");
        out.append("            (*env)->DeleteLocalRef(env, literal);\n");
        out.append("            if ((*env)->ExceptionCheck(env)) { (*env)->DeleteLocalRef(env, builder); (*env)->DeleteLocalRef(env, builderClass); return NULL; }\n");
        out.append("        }\n");
        out.append("        switch (placeholder_index) {\n");
        for (int index = 0; index < helper.paramTypes().size(); index++) {
            out.append("            case ").append(index).append(":\n");
            appendConcatArgument(out, helper.paramTypes().get(index), "arg" + index, 12);
            out.append("                break;\n");
        }
        out.append("            default:\n");
        out.append("                break;\n");
        out.append("        }\n");
        out.append("        if ((*env)->ExceptionCheck(env)) { (*env)->DeleteLocalRef(env, builder); (*env)->DeleteLocalRef(env, builderClass); return NULL; }\n");
        out.append("        placeholder_index++;\n");
        out.append("        literal_start = cursor + 1;\n");
        out.append("    }\n");
        out.append("    if (literal_start < cipher_len) {\n");
        out.append("        jstring literal = ").append(runtimeNames.newUtf8String()).append("(env, recipe + literal_start, cipher_len - literal_start);\n");
        out.append("        if (literal == NULL) { (*env)->DeleteLocalRef(env, builder); (*env)->DeleteLocalRef(env, builderClass); return NULL; }\n");
        out.append("        (*env)->CallObjectMethod(env, builder, appendString, literal);\n");
        out.append("        (*env)->DeleteLocalRef(env, literal);\n");
        out.append("        if ((*env)->ExceptionCheck(env)) { (*env)->DeleteLocalRef(env, builder); (*env)->DeleteLocalRef(env, builderClass); return NULL; }\n");
        out.append("    }\n");
        out.append("    jobject result = (*env)->CallObjectMethod(env, builder, toString);\n");
        out.append("    (*env)->DeleteLocalRef(env, builder);\n");
        out.append("    (*env)->DeleteLocalRef(env, builderClass);\n");
        out.append("    return (void*)result;\n");
    }

    private void appendConcatLiteral(StringBuilder out, String literal) {
        if (literal.isEmpty()) {
            return;
        }
        out.append("    {\n");
        out.append("        static const unsigned char literal_bytes[] = {")
                .append(renderHexBytes(encodeModifiedUtf8(literal)))
                .append("0x00};\n");
        out.append("        jstring literal = (*env)->NewStringUTF(env, (const char*)literal_bytes);\n");
        out.append("        if (literal == NULL) {\n");
        out.append("            (*env)->DeleteLocalRef(env, builder);\n");
        out.append("            (*env)->DeleteLocalRef(env, builderClass);\n");
        out.append("            return NULL;\n");
        out.append("        }\n");
        out.append("        (*env)->CallObjectMethod(env, builder, appendString, literal);\n");
        out.append("        (*env)->DeleteLocalRef(env, literal);\n");
        out.append("    }\n");
    }

    private void appendConcatArgument(StringBuilder out, String parameterType, int argumentIndex) {
        appendConcatArgument(out, parameterType, "arg" + argumentIndex, 4);
    }

    private void appendConcatArgument(StringBuilder out, String parameterType, String source, int indentSpaces) {
        String indent = " ".repeat(indentSpaces);
        switch (parameterType) {
            case "boolean" -> out.append(indent).append("(*env)->CallObjectMethod(env, builder, appendBoolean, (jboolean)")
                    .append(source)
                    .append(");\n");
            case "char" -> out.append(indent).append("(*env)->CallObjectMethod(env, builder, appendChar, (jchar)")
                    .append(source)
                    .append(");\n");
            case "byte", "short", "int" -> out.append(indent).append("(*env)->CallObjectMethod(env, builder, appendInt, (jint)")
                    .append(source)
                    .append(");\n");
            case "long" -> out.append(indent).append("(*env)->CallObjectMethod(env, builder, appendLong, (jlong)")
                    .append(source)
                    .append(");\n");
            case "float" -> out.append(indent).append("(*env)->CallObjectMethod(env, builder, appendFloat, (jfloat)")
                    .append(source)
                    .append(");\n");
            case "double" -> out.append(indent).append("(*env)->CallObjectMethod(env, builder, appendDouble, (jdouble)")
                    .append(source)
                    .append(");\n");
            default -> out.append(indent).append("(*env)->CallObjectMethod(env, builder, appendObject, (jobject)")
                    .append(source)
                    .append(");\n");
        }
    }

    private void appendStaticByteArray(StringBuilder out, String variableName, String hex, int declaredLength) {
        out.append("    static const uint8_t ").append(variableName).append("[] = {");
        if (hex == null || hex.isEmpty()) {
            out.append("0x00");
        } else {
            out.append(renderHexBytes(hex));
            out.setLength(out.length() - 2);
        }
        out.append("};\n");
        out.append("    const size_t ").append(variableName).append("_len = ").append(declaredLength).append(";\n");
    }

    private void appendEncodedCString(StringBuilder out, String variableName, String value, int indentSpaces) {
        EncodedMetaCString encoded = encodeMetaCString(value);
        String indent = " ".repeat(indentSpaces);
        out.append(indent).append("static const uint8_t ").append(variableName).append("[] = {");
        if (encoded.bytes().length == 0) {
            out.append("0x00");
        } else {
            out.append(renderHexBytes(encoded.bytes()));
            out.setLength(out.length() - 2);
        }
        out.append("};\n");
        out.append(indent).append("static const size_t ").append(variableName).append("_len = ").append(encoded.bytes().length).append(";\n");
        out.append(indent).append("static const uint8_t ").append(variableName).append("_seed = 0x")
                .append(String.format("%02x", encoded.seed() & 0xff))
                .append(";\n");
    }

    private void appendFindClassLookup(StringBuilder out, String targetName, String className, int indentSpaces) {
        appendEncodedCString(out, targetName + "_class_name", className, indentSpaces);
        String indent = " ".repeat(indentSpaces);
        out.append(indent).append("jclass ").append(targetName).append(" = ")
                .append(runtimeNames.findClassObf()).append("(env, ")
                .append(targetName).append("_class_name, ")
                .append(targetName).append("_class_name_len, ")
                .append(targetName).append("_class_name_seed);\n");
    }

    private void appendGetMethodIdLookup(StringBuilder out, String targetName, String clazzName,
                                         boolean isStatic, String methodName, String descriptor, int indentSpaces) {
        appendEncodedCString(out, targetName + "_name", methodName, indentSpaces);
        appendEncodedCString(out, targetName + "_desc", descriptor, indentSpaces);
        String indent = " ".repeat(indentSpaces);
        out.append(indent).append("jmethodID ").append(targetName).append(" = ")
                .append(runtimeNames.getMethodIdObf()).append("(env, ").append(clazzName).append(", ")
                .append(isStatic ? '1' : '0').append(", ")
                .append(targetName).append("_name, ").append(targetName).append("_name_len, ").append(targetName).append("_name_seed, ")
                .append(targetName).append("_desc, ").append(targetName).append("_desc_len, ").append(targetName).append("_desc_seed);\n");
    }

    private void appendGetFieldIdLookup(StringBuilder out, String targetName, String clazzName,
                                        boolean isStatic, String fieldName, String descriptor, int indentSpaces) {
        appendEncodedCString(out, targetName + "_name", fieldName, indentSpaces);
        appendEncodedCString(out, targetName + "_desc", descriptor, indentSpaces);
        String indent = " ".repeat(indentSpaces);
        out.append(indent).append("jfieldID ").append(targetName).append(" = ")
                .append(runtimeNames.getFieldIdObf()).append("(env, ").append(clazzName).append(", ")
                .append(isStatic ? '1' : '0').append(", ")
                .append(targetName).append("_name, ").append(targetName).append("_name_len, ").append(targetName).append("_name_seed, ")
                .append(targetName).append("_desc, ").append(targetName).append("_desc_len, ").append(targetName).append("_desc_seed);\n");
    }

    private ObfuscatedStringHelperData obfuscatedStringData(Helper helper) {
        String[] pieces = helper.payload().split("\\|", -1);
        if (pieces.length != 5 && pieces.length != 6) {
            throw new IllegalArgumentException("Invalid obfuscated string helper payload: " + helper.payload());
        }
        boolean cacheStrings = pieces.length == 6 && "1".equals(pieces[0]);
        int baseIndex = pieces.length == 6 ? 1 : 0;
        return new ObfuscatedStringHelperData(
                cacheStrings,
                Integer.parseUnsignedInt(pieces[baseIndex], 16),
                pieces[baseIndex + 1],
                pieces[baseIndex + 2],
                pieces[baseIndex + 3],
                pieces[baseIndex + 4]
        );
    }

    private int hexByteLength(String hex) {
        return hex == null ? 0 : hex.length() / 2;
    }

    private void appendLambdaHelper(StringBuilder out, Helper helper) {
        out.append("    JNIEnv* env = ").append(runtimeNames.envAccessor()).append("();\n");
        out.append("    if (env == NULL) return NULL;\n");
        out.append("    static jobject cachedLambdaFactory = NULL;\n");
        appendEncodedCString(out, "lambda_caller_class", lambdaCallerOwner(helper), 4);
        appendEncodedCString(out, "lambda_target_class", helper.owner(), 4);
        appendEncodedCString(out, "lambda_impl_descriptor", helper.descriptor(), 4);
        appendEncodedCString(out, "lambda_method_name", helper.name(), 4);
        appendEncodedCString(out, "lambda_factory_descriptor", methodDesc(helper.paramTypes(), helper.displayType()), 4);
        appendEncodedCString(out, "lambda_sam_descriptor", lambdaSamDescriptor(helper), 4);
        appendEncodedCString(out, "lambda_instantiated_descriptor", lambdaInstantiatedDescriptor(helper), 4);
        appendEncodedCString(out, "lambda_sam_name", lambdaSamName(helper), 4);
        appendEncodedCString(out, "lambda_interface_class", helper.displayType(), 4);
        out.append("    jclass interfaceClass = ").append(runtimeNames.findClassObf())
                .append("(env, lambda_interface_class, lambda_interface_class_len, lambda_interface_class_seed);\n");
        appendFindClassLookup(out, "objectClass", "java/lang/Object", 4);
        appendFindClassLookup(out, "methodHandleClass", "java/lang/invoke/MethodHandle", 4);
        appendGetMethodIdLookup(out, "invokeWithArguments", "methodHandleClass", false, "invokeWithArguments", "([Ljava/lang/Object;)Ljava/lang/Object;", 4);
        out.append("    if (interfaceClass == NULL || objectClass == NULL || methodHandleClass == NULL || invokeWithArguments == NULL) return NULL;\n");
        out.append("    if (cachedLambdaFactory == NULL) {\n");
        out.append("        jclass callerClass = ").append(runtimeNames.findClassObf()).append("(env, lambda_caller_class, lambda_caller_class_len, lambda_caller_class_seed);\n");
        out.append("        jclass targetClass = ").append(runtimeNames.findClassObf()).append("(env, lambda_target_class, lambda_target_class_len, lambda_target_class_seed);\n");
        appendFindClassLookup(out, "classClass", "java/lang/Class", 8);
        appendFindClassLookup(out, "methodTypeClass", "java/lang/invoke/MethodType", 8);
        appendFindClassLookup(out, "methodHandlesClass", "java/lang/invoke/MethodHandles", 8);
        appendFindClassLookup(out, "lookupClass", "java/lang/invoke/MethodHandles$Lookup", 8);
        appendFindClassLookup(out, "callSiteClass", "java/lang/invoke/CallSite", 8);
        appendFindClassLookup(out, "lambdaMetafactoryClass", "java/lang/invoke/LambdaMetafactory", 8);
        appendFindClassLookup(out, "integerClass", "java/lang/Integer", 8);
        out.append("        if (callerClass == NULL || targetClass == NULL || classClass == NULL || methodTypeClass == NULL || methodHandlesClass == NULL || lookupClass == NULL || callSiteClass == NULL || lambdaMetafactoryClass == NULL || integerClass == NULL) return NULL;\n");
        appendGetMethodIdLookup(out, "getClassLoader", "classClass", false, "getClassLoader", "()Ljava/lang/ClassLoader;", 8);
        appendGetMethodIdLookup(out, "fromMethodDescriptorString", "methodTypeClass", true, "fromMethodDescriptorString", "(Ljava/lang/String;Ljava/lang/ClassLoader;)Ljava/lang/invoke/MethodType;", 8);
        appendGetMethodIdLookup(out, "lookup", "methodHandlesClass", true, "lookup", "()Ljava/lang/invoke/MethodHandles$Lookup;", 8);
        appendGetMethodIdLookup(out, "publicLookup", "methodHandlesClass", true, "publicLookup", "()Ljava/lang/invoke/MethodHandles$Lookup;", 8);
        appendGetMethodIdLookup(out, "privateLookupIn", "methodHandlesClass", true, "privateLookupIn", "(Ljava/lang/Class;Ljava/lang/invoke/MethodHandles$Lookup;)Ljava/lang/invoke/MethodHandles$Lookup;", 8);
        appendGetMethodIdLookup(out, "findConstructor", "lookupClass", false, "findConstructor", "(Ljava/lang/Class;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/MethodHandle;", 8);
        appendGetMethodIdLookup(out, "findStatic", "lookupClass", false, "findStatic", "(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/MethodHandle;", 8);
        appendGetMethodIdLookup(out, "findVirtual", "lookupClass", false, "findVirtual", "(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/MethodHandle;", 8);
        appendGetMethodIdLookup(out, "findSpecial", "lookupClass", false, "findSpecial", "(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/Class;)Ljava/lang/invoke/MethodHandle;", 8);
        appendGetMethodIdLookup(out, "metafactory", "lambdaMetafactoryClass", true, "metafactory", "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;", 8);
        appendGetMethodIdLookup(out, "altMetafactory", "lambdaMetafactoryClass", true, "altMetafactory", "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;[Ljava/lang/Object;)Ljava/lang/invoke/CallSite;", 8);
        appendGetMethodIdLookup(out, "integerValueOf", "integerClass", true, "valueOf", "(I)Ljava/lang/Integer;", 8);
        appendGetMethodIdLookup(out, "getTarget", "callSiteClass", false, "getTarget", "()Ljava/lang/invoke/MethodHandle;", 8);
        out.append("        if (getClassLoader == NULL || fromMethodDescriptorString == NULL || lookup == NULL || publicLookup == NULL || privateLookupIn == NULL || findConstructor == NULL || findStatic == NULL || findVirtual == NULL || findSpecial == NULL || metafactory == NULL || altMetafactory == NULL || integerValueOf == NULL || getTarget == NULL) return NULL;\n");
        out.append("        jobject classLoader = (*env)->CallObjectMethod(env, callerClass, getClassLoader);\n");
        out.append("        if ((*env)->ExceptionCheck(env)) return NULL;\n");
        out.append("        jstring implDescriptor = ").append(runtimeNames.newStringUtfObf()).append("(env, lambda_impl_descriptor, lambda_impl_descriptor_len, lambda_impl_descriptor_seed);\n");
        out.append("        if (implDescriptor == NULL) return NULL;\n");
        out.append("        jobject implMethodType = (*env)->CallStaticObjectMethod(env, methodTypeClass, fromMethodDescriptorString, implDescriptor, classLoader);\n");
        out.append("        (*env)->DeleteLocalRef(env, implDescriptor);\n");
        out.append("        if (implMethodType == NULL || (*env)->ExceptionCheck(env)) return NULL;\n");
        if (lambdaCallerOwner(helper).startsWith("java/")) {
            out.append("        jobject callerLookup = (*env)->CallStaticObjectMethod(env, methodHandlesClass, publicLookup);\n");
        } else {
            out.append("        jobject baseLookup = (*env)->CallStaticObjectMethod(env, methodHandlesClass, lookup);\n");
            out.append("        if (baseLookup == NULL || (*env)->ExceptionCheck(env)) return NULL;\n");
        out.append("        jobject callerLookup = (*env)->CallStaticObjectMethod(env, methodHandlesClass, privateLookupIn, callerClass, baseLookup);\n");
        }
        out.append("        if (callerLookup == NULL || (*env)->ExceptionCheck(env)) return NULL;\n");
        switch (lambdaInvokeKind(helper)) {
            case "constructor" -> out.append("        jobject implHandle = (*env)->CallObjectMethod(env, callerLookup, findConstructor, targetClass, implMethodType);\n");
            case "static", "virtual", "interface", "special" -> {
                out.append("        jstring methodName = ").append(runtimeNames.newStringUtfObf()).append("(env, lambda_method_name, lambda_method_name_len, lambda_method_name_seed);\n");
                out.append("        if (methodName == NULL) return NULL;\n");
            }
            default -> throw new IllegalArgumentException("Unsupported lambda invoke kind: " + lambdaInvokeKind(helper));
        }
        switch (lambdaInvokeKind(helper)) {
            case "static" -> out.append("        jobject implHandle = (*env)->CallObjectMethod(env, callerLookup, findStatic, targetClass, methodName, implMethodType);\n");
            case "virtual", "interface" -> out.append("        jobject implHandle = (*env)->CallObjectMethod(env, callerLookup, findVirtual, targetClass, methodName, implMethodType);\n");
            case "special" -> out.append("        jobject implHandle = (*env)->CallObjectMethod(env, callerLookup, findSpecial, targetClass, methodName, implMethodType, callerClass);\n");
            case "constructor" -> {
            }
            default -> throw new IllegalArgumentException("Unsupported lambda invoke kind: " + lambdaInvokeKind(helper));
        }
        switch (lambdaInvokeKind(helper)) {
            case "static", "virtual", "interface", "special" -> out.append("        (*env)->DeleteLocalRef(env, methodName);\n");
            case "constructor" -> {
            }
            default -> throw new IllegalArgumentException("Unsupported lambda invoke kind: " + lambdaInvokeKind(helper));
        }
        out.append("        if (implHandle == NULL || (*env)->ExceptionCheck(env)) return NULL;\n");
        out.append("        jstring factoryDescriptor = ").append(runtimeNames.newStringUtfObf()).append("(env, lambda_factory_descriptor, lambda_factory_descriptor_len, lambda_factory_descriptor_seed);\n");
        out.append("        if (factoryDescriptor == NULL) return NULL;\n");
        out.append("        jobject factoryType = (*env)->CallStaticObjectMethod(env, methodTypeClass, fromMethodDescriptorString, factoryDescriptor, classLoader);\n");
        out.append("        (*env)->DeleteLocalRef(env, factoryDescriptor);\n");
        out.append("        if (factoryType == NULL || (*env)->ExceptionCheck(env)) return NULL;\n");
        out.append("        jstring samDescriptor = ").append(runtimeNames.newStringUtfObf()).append("(env, lambda_sam_descriptor, lambda_sam_descriptor_len, lambda_sam_descriptor_seed);\n");
        out.append("        if (samDescriptor == NULL) return NULL;\n");
        out.append("        jobject samMethodType = (*env)->CallStaticObjectMethod(env, methodTypeClass, fromMethodDescriptorString, samDescriptor, classLoader);\n");
        out.append("        (*env)->DeleteLocalRef(env, samDescriptor);\n");
        out.append("        if (samMethodType == NULL || (*env)->ExceptionCheck(env)) return NULL;\n");
        out.append("        jstring instantiatedDescriptor = ").append(runtimeNames.newStringUtfObf()).append("(env, lambda_instantiated_descriptor, lambda_instantiated_descriptor_len, lambda_instantiated_descriptor_seed);\n");
        out.append("        if (instantiatedDescriptor == NULL) return NULL;\n");
        out.append("        jobject instantiatedMethodType = (*env)->CallStaticObjectMethod(env, methodTypeClass, fromMethodDescriptorString, instantiatedDescriptor, classLoader);\n");
        out.append("        (*env)->DeleteLocalRef(env, instantiatedDescriptor);\n");
        out.append("        if (instantiatedMethodType == NULL || (*env)->ExceptionCheck(env)) return NULL;\n");
        out.append("        jstring samName = ").append(runtimeNames.newStringUtfObf()).append("(env, lambda_sam_name, lambda_sam_name_len, lambda_sam_name_seed);\n");
        out.append("        if (samName == NULL) return NULL;\n");
        if ("altMetafactory".equals(lambdaBootstrapMethod(helper))) {
            List<String> markerInterfaces = lambdaMarkerInterfaces(helper);
            List<String> bridgeDescriptors = lambdaBridgeDescriptors(helper);
            int bootstrapArgCount = 4;
            if ((lambdaAltFlags(helper) & 2) != 0) {
                bootstrapArgCount += 1 + markerInterfaces.size();
            }
            if ((lambdaAltFlags(helper) & 4) != 0) {
                bootstrapArgCount += 1 + bridgeDescriptors.size();
            }
            out.append("        jobjectArray bootstrapArgs = (*env)->NewObjectArray(env, ").append(bootstrapArgCount).append(", objectClass, NULL);\n");
            out.append("        if (bootstrapArgs == NULL) return NULL;\n");
            out.append("        jobject lambdaFlags = (*env)->CallStaticObjectMethod(env, integerClass, integerValueOf, ").append(lambdaAltFlags(helper)).append(");\n");
            out.append("        if (lambdaFlags == NULL || (*env)->ExceptionCheck(env)) return NULL;\n");
            out.append("        (*env)->SetObjectArrayElement(env, bootstrapArgs, 0, samMethodType);\n");
            out.append("        (*env)->SetObjectArrayElement(env, bootstrapArgs, 1, implHandle);\n");
            out.append("        (*env)->SetObjectArrayElement(env, bootstrapArgs, 2, instantiatedMethodType);\n");
            out.append("        (*env)->SetObjectArrayElement(env, bootstrapArgs, 3, lambdaFlags);\n");
            out.append("        if ((*env)->ExceptionCheck(env)) return NULL;\n");
            int bootstrapArgIndex = 4;
            if ((lambdaAltFlags(helper) & 2) != 0) {
                out.append("        jobject markerCount = (*env)->CallStaticObjectMethod(env, integerClass, integerValueOf, ").append(markerInterfaces.size()).append(");\n");
                out.append("        if (markerCount == NULL || (*env)->ExceptionCheck(env)) return NULL;\n");
                out.append("        (*env)->SetObjectArrayElement(env, bootstrapArgs, ").append(bootstrapArgIndex++).append(", markerCount);\n");
                out.append("        if ((*env)->ExceptionCheck(env)) return NULL;\n");
                for (String markerInterface : markerInterfaces) {
                    String variableName = "marker_interface_" + bootstrapArgIndex;
                    appendEncodedCString(out, variableName, markerInterface, 8);
                    out.append("        jclass ").append(variableName).append("_class = ").append(runtimeNames.findClassObf())
                            .append("(env, ").append(variableName).append(", ").append(variableName).append("_len, ").append(variableName).append("_seed);\n");
                    out.append("        if (").append(variableName).append("_class == NULL) return NULL;\n");
                    out.append("        (*env)->SetObjectArrayElement(env, bootstrapArgs, ").append(bootstrapArgIndex++).append(", ").append(variableName).append("_class);\n");
                    out.append("        if ((*env)->ExceptionCheck(env)) return NULL;\n");
                }
            }
            if ((lambdaAltFlags(helper) & 4) != 0) {
                out.append("        jobject bridgeCount = (*env)->CallStaticObjectMethod(env, integerClass, integerValueOf, ").append(bridgeDescriptors.size()).append(");\n");
                out.append("        if (bridgeCount == NULL || (*env)->ExceptionCheck(env)) return NULL;\n");
                out.append("        (*env)->SetObjectArrayElement(env, bootstrapArgs, ").append(bootstrapArgIndex++).append(", bridgeCount);\n");
                out.append("        if ((*env)->ExceptionCheck(env)) return NULL;\n");
                for (String bridgeDescriptor : bridgeDescriptors) {
                    String variableName = "bridge_descriptor_" + bootstrapArgIndex;
                    appendEncodedCString(out, variableName, bridgeDescriptor, 8);
                    out.append("        jstring ").append(variableName).append("_string = ").append(runtimeNames.newStringUtfObf())
                            .append("(env, ").append(variableName).append(", ").append(variableName).append("_len, ").append(variableName).append("_seed);\n");
                    out.append("        if (").append(variableName).append("_string == NULL) return NULL;\n");
                    out.append("        jobject ").append(variableName).append("_type = (*env)->CallStaticObjectMethod(env, methodTypeClass, fromMethodDescriptorString, ")
                            .append(variableName).append("_string, classLoader);\n");
                    out.append("        (*env)->DeleteLocalRef(env, ").append(variableName).append("_string);\n");
                    out.append("        if (").append(variableName).append("_type == NULL || (*env)->ExceptionCheck(env)) return NULL;\n");
                    out.append("        (*env)->SetObjectArrayElement(env, bootstrapArgs, ").append(bootstrapArgIndex++).append(", ").append(variableName).append("_type);\n");
                    out.append("        if ((*env)->ExceptionCheck(env)) return NULL;\n");
                }
            }
            out.append("        jobject callSite = (*env)->CallStaticObjectMethod(env, lambdaMetafactoryClass, altMetafactory, callerLookup, samName, factoryType, bootstrapArgs);\n");
        } else {
            out.append("        jobject callSite = (*env)->CallStaticObjectMethod(env, lambdaMetafactoryClass, metafactory, callerLookup, samName, factoryType, samMethodType, implHandle, instantiatedMethodType);\n");
        }
        out.append("        (*env)->DeleteLocalRef(env, samName);\n");
        out.append("        if (callSite == NULL || (*env)->ExceptionCheck(env)) return NULL;\n");
        out.append("        jobject lambdaFactory = (*env)->CallObjectMethod(env, callSite, getTarget);\n");
        out.append("        if (lambdaFactory == NULL || (*env)->ExceptionCheck(env)) return NULL;\n");
        out.append("        cachedLambdaFactory = (*env)->NewGlobalRef(env, lambdaFactory);\n");
        out.append("        if (cachedLambdaFactory == NULL || (*env)->ExceptionCheck(env)) return NULL;\n");
        out.append("    }\n");
        out.append("    jobjectArray capturedArgs = (*env)->NewObjectArray(env, ").append(helper.paramTypes().size()).append(", objectClass, NULL);\n");
        out.append("    if (capturedArgs == NULL) return NULL;\n");
        for (int index = 0; index < helper.paramTypes().size(); index++) {
            appendBoxedArgument(out, helper.paramTypes().get(index), "arg" + index, "capturedArg" + index, "NULL");
            out.append("    (*env)->SetObjectArrayElement(env, capturedArgs, ").append(index).append(", capturedArg").append(index).append(");\n");
            out.append("    if ((*env)->ExceptionCheck(env)) return NULL;\n");
        }
        out.append("    jobject lambda = (*env)->CallObjectMethod(env, cachedLambdaFactory, invokeWithArguments, capturedArgs);\n");
        out.append("    if (lambda == NULL || (*env)->ExceptionCheck(env)) return NULL;\n");
        out.append("    return (void*)lambda;\n");
    }

    private void appendInstanceOfHelper(StringBuilder out, Helper helper) {
        out.append("    JNIEnv* env = ").append(runtimeNames.envAccessor()).append("();\n");
        out.append("    if (env == NULL) return 0;\n");
        out.append("    jobject value = (jobject)arg0;\n");
        out.append("    if (value == NULL) return 0;\n");
        String className = helper.displayType().endsWith("[]") ? desc(helper.displayType()) : helper.displayType();
        appendEncodedCString(out, "target_class_name", className, 4);
        out.append("    jclass targetClass = ").append(runtimeNames.findClassObf()).append("(env, target_class_name, target_class_name_len, target_class_name_seed);\n");
        out.append("    if (targetClass == NULL) return 0;\n");
        out.append("    return (uint8_t)((*env)->IsInstanceOf(env, value, targetClass) ? 1 : 0);\n");
    }

    private void appendTypeSwitchHelper(StringBuilder out, Helper helper) {
        out.append("    JNIEnv* env = ").append(runtimeNames.envAccessor()).append("();\n");
        out.append("    if (env == NULL) return -1;\n");
        out.append("    jobject value = (jobject)arg0;\n");
        out.append("    int32_t start = (int32_t)arg1;\n");
        out.append("    if (value == NULL) return -1;\n");
        out.append("    if (start < 0) start = 0;\n");
        for (int index = 0; index < helper.paramTypes().size(); index++) {
            String caseToken = helper.paramTypes().get(index);
            out.append("    if (start <= ").append(index).append(") {\n");
            if (isEnumTypeSwitchCase(caseToken)) {
                String owner = enumTypeSwitchOwner(caseToken);
                String constantName = enumTypeSwitchConstant(caseToken);
                appendEncodedCString(out, "case_class_name_" + index, owner, 8);
                appendEncodedCString(out, "case_field_name_" + index, constantName, 8);
                appendEncodedCString(out, "case_field_desc_" + index, "L" + owner + ";", 8);
                out.append("        jclass caseClass = ").append(runtimeNames.findClassObf()).append("(env, case_class_name_").append(index).append(", case_class_name_").append(index).append("_len, case_class_name_").append(index).append("_seed);\n");
                out.append("        if (caseClass == NULL) return -1;\n");
                out.append("        jfieldID caseField = ").append(runtimeNames.getFieldIdObf()).append("(env, caseClass, 1, case_field_name_").append(index).append(", case_field_name_").append(index).append("_len, case_field_name_").append(index).append("_seed, case_field_desc_").append(index).append(", case_field_desc_").append(index).append("_len, case_field_desc_").append(index).append("_seed);\n");
                out.append("        if (caseField == NULL) { (*env)->DeleteLocalRef(env, caseClass); return -1; }\n");
                out.append("        jobject caseValue = (*env)->GetStaticObjectField(env, caseClass, caseField);\n");
                out.append("        (*env)->DeleteLocalRef(env, caseClass);\n");
                out.append("        if (caseValue == NULL) return -1;\n");
                out.append("        uint8_t matches = (uint8_t)((*env)->IsSameObject(env, value, caseValue) ? 1 : 0);\n");
                out.append("        (*env)->DeleteLocalRef(env, caseValue);\n");
                out.append("        if (matches) return ").append(index).append(";\n");
            } else {
                appendEncodedCString(out, "case_class_name_" + index, caseToken, 8);
                out.append("        jclass caseClass = ").append(runtimeNames.findClassObf()).append("(env, case_class_name_").append(index).append(", case_class_name_").append(index).append("_len, case_class_name_").append(index).append("_seed);\n");
                out.append("        if (caseClass == NULL) return -1;\n");
                out.append("        if ((*env)->IsInstanceOf(env, value, caseClass)) return ").append(index).append(";\n");
                out.append("        (*env)->DeleteLocalRef(env, caseClass);\n");
            }
            out.append("    }\n");
        }
        out.append("    return -1;\n");
    }

    private void appendRecordHelper(StringBuilder out, Helper helper, Sig sig) {
        List<String> componentLabels = recordComponentLabels(helper);
        List<String> fieldNames = recordFieldNames(helper);
        out.append("    JNIEnv* env = ").append(runtimeNames.envAccessor()).append("();\n");
        out.append("    if (env == NULL) { ").append(defaultReturn(sig.returnType())).append(" }\n");
        out.append("    jobject receiver = (jobject)arg0;\n");
        out.append("    if (receiver == NULL) { ").append(defaultReturn(sig.returnType())).append(" }\n");
        appendEncodedCString(out, "record_class_name", helper.owner(), 4);
        out.append("    jclass recordClass = ").append(runtimeNames.findClassObf()).append("(env, record_class_name, record_class_name_len, record_class_name_seed);\n");
        out.append("    if (recordClass == NULL) { ").append(defaultReturn(sig.returnType())).append(" }\n");
        for (int index = 0; index < fieldNames.size() && index < helper.paramTypes().size(); index++) {
            appendEncodedCString(out, "field_name_" + index, fieldNames.get(index), 8);
            appendEncodedCString(out, "field_desc_" + index, desc(helper.paramTypes().get(index)), 8);
            out.append("    jfieldID field").append(index).append(" = ").append(runtimeNames.getFieldIdObf()).append("(env, recordClass, 0, field_name_")
                    .append(index).append(", field_name_").append(index).append("_len, field_name_").append(index).append("_seed, field_desc_")
                    .append(index).append(", field_desc_").append(index).append("_len, field_desc_").append(index).append("_seed);\n");
            out.append("    if (field").append(index).append(" == NULL) { ").append(defaultReturn(sig.returnType())).append(" }\n");
        }

        switch (helper.name()) {
            case "toString" -> {
                appendFindClassLookup(out, "builderClass", "java/lang/StringBuilder", 4);
                out.append("    if (builderClass == NULL) return NULL;\n");
                appendGetMethodIdLookup(out, "init", "builderClass", false, "<init>", "()V", 4);
                appendGetMethodIdLookup(out, "appendString", "builderClass", false, "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;", 4);
                appendGetMethodIdLookup(out, "appendObject", "builderClass", false, "append", "(Ljava/lang/Object;)Ljava/lang/StringBuilder;", 4);
                appendGetMethodIdLookup(out, "toString", "builderClass", false, "toString", "()Ljava/lang/String;", 4);
                out.append("    if (init == NULL || appendString == NULL || appendObject == NULL || toString == NULL) return NULL;\n");
                out.append("    jobject builder = (*env)->NewObject(env, builderClass, init);\n");
                out.append("    if (builder == NULL) return NULL;\n");
                appendConcatLiteral(out, recordSimpleName(helper.owner()) + "[");
                for (int index = 0; index < componentLabels.size() && index < helper.paramTypes().size(); index++) {
                    if (index > 0) {
                        appendConcatLiteral(out, ", ");
                    }
                    appendConcatLiteral(out, componentLabels.get(index) + "=");
                    appendRecordBoxedComponent(out, helper.paramTypes().get(index), "field" + index, "receiver", "boxed" + index, "toString");
                    out.append("    (*env)->CallObjectMethod(env, builder, appendObject, boxed").append(index).append(");\n");
                }
                appendConcatLiteral(out, "]");
                out.append("    jobject result = (*env)->CallObjectMethod(env, builder, toString);\n");
                out.append("    return (void*)result;\n");
            }
            case "hashCode" -> {
                appendFindClassLookup(out, "objectClass", "java/lang/Object", 4);
                appendFindClassLookup(out, "objectsClass", "java/util/Objects", 4);
                out.append("    if (objectClass == NULL || objectsClass == NULL) return 0;\n");
                out.append("    jobjectArray values = (*env)->NewObjectArray(env, ").append(componentLabels.size()).append(", objectClass, NULL);\n");
                out.append("    if (values == NULL) return 0;\n");
                appendGetMethodIdLookup(out, "hash", "objectsClass", true, "hash", "([Ljava/lang/Object;)I", 4);
                out.append("    if (hash == NULL) return 0;\n");
                for (int index = 0; index < componentLabels.size() && index < helper.paramTypes().size(); index++) {
                    appendRecordBoxedComponent(out, helper.paramTypes().get(index), "field" + index, "receiver", "boxed" + index, "hashCode");
                    out.append("    (*env)->SetObjectArrayElement(env, values, ").append(index).append(", boxed").append(index).append(");\n");
                }
                out.append("    return (int32_t)(*env)->CallStaticIntMethod(env, objectsClass, hash, values);\n");
            }
            case "equals" -> {
                out.append("    jobject other = (jobject)arg1;\n");
                out.append("    if ((*env)->IsSameObject(env, receiver, other)) return 1;\n");
                out.append("    if (other == NULL) return 0;\n");
                out.append("    jclass otherClass = (*env)->GetObjectClass(env, other);\n");
                out.append("    if (otherClass == NULL) return 0;\n");
                out.append("    if (!(*env)->IsSameObject(env, recordClass, otherClass)) return 0;\n");
                appendFindClassLookup(out, "objectsClass", "java/util/Objects", 4);
                out.append("    if (objectsClass == NULL) return 0;\n");
                appendGetMethodIdLookup(out, "equals", "objectsClass", true, "equals", "(Ljava/lang/Object;Ljava/lang/Object;)Z", 4);
                out.append("    if (equals == NULL) return 0;\n");
                for (int index = 0; index < componentLabels.size() && index < helper.paramTypes().size(); index++) {
                    appendRecordBoxedComponent(out, helper.paramTypes().get(index), "field" + index, "receiver", "left" + index, "equals");
                    appendRecordBoxedComponent(out, helper.paramTypes().get(index), "field" + index, "other", "right" + index, "equals");
                    out.append("    if (!(*env)->CallStaticBooleanMethod(env, objectsClass, equals, left").append(index).append(", right").append(index).append(")) return 0;\n");
                }
                out.append("    return 1;\n");
            }
            default -> out.append("    ").append(defaultReturn(sig.returnType())).append('\n');
        }
    }

    private void appendDefaultHelper(StringBuilder out, Sig sig) {
        for (int index = 0; index < sig.params().size(); index++) {
            out.append("    (void)arg").append(index).append(";\n");
        }
        out.append("    ").append(defaultReturn(sig.returnType())).append('\n');
    }

    private void appendCmpHelper(StringBuilder out, Helper helper, Sig sig) {
        String nanResult = switch (helper.name()) {
            case "fcmpl", "dcmpl" -> "-1";
            case "fcmpg", "dcmpg" -> "1";
            default -> null;
        };
        if (nanResult != null) {
            out.append("    if (arg0 != arg0 || arg1 != arg1) return ").append(nanResult).append(";\n");
        }
        out.append("    if (arg0 < arg1) return -1;\n");
        out.append("    if (arg0 > arg1) return 1;\n");
        out.append("    return 0;\n");
    }

    private Helper parseHelper(String helperName, Sig sig) {
        if (helperName.equals("ir_rt_throw")) return new Helper(Kind.THROW, sig, null, null, null, null, List.of(), false, false, null, null);
        if (helperName.equals("ir_rt_current_exception")) return new Helper(Kind.CURRENT_EXCEPTION, sig, null, null, null, "java/lang/Throwable", List.of(), false, false, null, null);
        if (helperName.equals("ir_rt_exception_pending")) return new Helper(Kind.EXCEPTION_PENDING, sig, null, null, null, "boolean", List.of(), false, false, null, null);
        if (helperName.equals("ir_rt_array_length")) return new Helper(Kind.ARRAY_LENGTH, sig, null, null, null, "int", List.of(), false, false, null, null);
        if (helperName.equals("ir_rt_monitor_enter")) return new Helper(Kind.MONITOR, sig, null, "enter", null, null, List.of(), false, false, null, null);
        if (helperName.equals("ir_rt_monitor_exit")) return new Helper(Kind.MONITOR, sig, null, "exit", null, null, List.of(), false, false, null, null);
        if (helperName.equals("ir_rt_ref_eq")) return new Helper(Kind.REF_CMP, sig, null, "eq", null, "boolean", List.of(), false, false, null, null);
        if (helperName.equals("ir_rt_ref_ne")) return new Helper(Kind.REF_CMP, sig, null, "ne", null, "boolean", List.of(), false, false, null, null);
        if (helperName.equals("ir_rt_lcmp")) return new Helper(Kind.CMP, sig, null, "lcmp", null, "int", List.of(), false, false, null, null);
        if (helperName.equals("ir_rt_fcmpl")) return new Helper(Kind.CMP, sig, null, "fcmpl", null, "int", List.of(), false, false, null, null);
        if (helperName.equals("ir_rt_fcmpg")) return new Helper(Kind.CMP, sig, null, "fcmpg", null, "int", List.of(), false, false, null, null);
        if (helperName.equals("ir_rt_dcmpl")) return new Helper(Kind.CMP, sig, null, "dcmpl", null, "int", List.of(), false, false, null, null);
        if (helperName.equals("ir_rt_dcmpg")) return new Helper(Kind.CMP, sig, null, "dcmpg", null, "int", List.of(), false, false, null, null);
        if (helperName.startsWith("ir_rt_new_init__")) {
            String[] pieces = helperName.split("__");
            ArrayList<String> paramTypes = new ArrayList<>();
            for (int index = 2; index < pieces.length; index++) {
                paramTypes.add(decodeDisplayToken(pieces[index]));
            }
            String owner = decodeToken(pieces[1]);
            return new Helper(Kind.NEW_INIT, sig, owner, "<init>", null, owner, List.copyOf(paramTypes), false, false, null, null);
        }
        if (helperName.startsWith("ir_rt_ldc_string__")) {
            String payload = decodeUtf8Hex(helperName.substring("ir_rt_ldc_string__".length()));
            return new Helper(Kind.LDC_STRING, sig, null, null, null, "java/lang/String", List.of(), false, false, null, payload);
        }
        if (helperName.startsWith("ir_rt_sobf__")) {
            String[] pieces = helperName.split("__", -1);
            if (pieces.length == 7) {
                return new Helper(
                        Kind.OBFUSCATED_STRING,
                        sig,
                        null,
                        null,
                        null,
                        "java/lang/String",
                        List.of(),
                        false,
                        false,
                        null,
                        String.join("|", pieces[1], pieces[2], pieces[3], pieces[4], pieces[5], pieces[6])
                );
            }
        }
        if (helperName.startsWith("ir_rt_ldc_class__")) return new Helper(Kind.LDC_CLASS, sig, null, null, null, decodeDisplayToken(helperName.substring("ir_rt_ldc_class__".length())), List.of(), false, false, null, null);
        if (helperName.startsWith("ir_rt_new__")) return new Helper(Kind.NEW_OBJECT, sig, decodeToken(helperName.substring("ir_rt_new__".length())), null, null, null, List.of(), false, false, null, null);
        if (helperName.startsWith("ir_rt_new_array__")) return new Helper(Kind.ARRAY_NEW, sig, null, null, null, decodeDisplayToken(helperName.substring("ir_rt_new_array__".length())), List.of(), false, false, null, null);
        if (helperName.startsWith("ir_rt_multi_new_array__")) return new Helper(Kind.ARRAY_MULTI_NEW, sig, null, null, null, decodeDisplayToken(helperName.substring("ir_rt_multi_new_array__".length())), List.of(), false, false, null, null);
        if (helperName.startsWith("ir_rt_array_load__")) return new Helper(Kind.ARRAY_LOAD, sig, null, null, null, decodeDisplayToken(helperName.substring("ir_rt_array_load__".length())), List.of(), false, false, null, null);
        if (helperName.startsWith("ir_rt_array_store__")) return new Helper(Kind.ARRAY_STORE, sig, null, null, null, decodeDisplayToken(helperName.substring("ir_rt_array_store__".length())), List.of(), false, false, null, null);
        if (helperName.startsWith("ir_rt_instanceof__")) {
            return new Helper(Kind.INSTANCEOF, sig, null, null, null, decodeDisplayToken(helperName.substring("ir_rt_instanceof__".length())), List.of(), false, false, null, null);
        }
        if (helperName.startsWith("ir_rt_concat__")) {
            String[] pieces = helperName.split("__");
            if (pieces.length >= 2) {
                ArrayList<String> paramTypes = new ArrayList<>();
                for (int index = 2; index < pieces.length; index++) {
                    paramTypes.add(decodeDisplayToken(pieces[index]));
                }
                return new Helper(
                        Kind.CONCAT,
                        sig,
                        null,
                        null,
                        null,
                        "java/lang/String",
                        List.copyOf(paramTypes),
                        false,
                        false,
                        null,
                        decodeUtf8Hex(pieces[1])
                );
            }
        }
        if (helperName.startsWith("ir_rt_sobf_concat__")) {
            String[] pieces = helperName.split("__", -1);
            if (pieces.length >= 6) {
                ArrayList<String> paramTypes = new ArrayList<>();
                for (int index = 6; index < pieces.length; index++) {
                    paramTypes.add(decodeDisplayToken(pieces[index]));
                }
                return new Helper(
                        Kind.OBFUSCATED_CONCAT,
                        sig,
                        null,
                        null,
                        null,
                        "java/lang/String",
                        List.copyOf(paramTypes),
                        false,
                        false,
                        null,
                        String.join("|", pieces[1], pieces[2], pieces[3], pieces[4], pieces[5])
                );
            }
        }
        if (helperName.startsWith("ir_rt_lambda__")) {
            String[] pieces = helperName.split("__");
            int captureCount = sig.params().size();
            int invokeKindIndex = pieces.length - 1 - captureCount;
            int instantiatedDescriptorIndex = invokeKindIndex - 1;
            int implDescriptorIndex = invokeKindIndex - 2;
            if (pieces.length >= 10 && implDescriptorIndex > 6) {
                ArrayList<String> captureTypes = new ArrayList<>();
                for (int index = invokeKindIndex + 1; index < pieces.length; index++) {
                    captureTypes.add(decodeDisplayHexAware(pieces[index]));
                }
                String implMethodToken = String.join("__", java.util.Arrays.copyOfRange(pieces, 6, implDescriptorIndex));
                return new Helper(
                        Kind.LAMBDA,
                        sig,
                        decodeUtf8Hex(pieces[5]),
                        normMethod(decodeUtf8Hex(implMethodToken)),
                        decodeUtf8Hex(pieces[implDescriptorIndex]),
                        decodeDisplayHexAware(pieces[1]),
                        List.copyOf(captureTypes),
                        false,
                        false,
                        decodeUtf8Hex(pieces[2]),
                        decodeUtf8Hex(pieces[3]) + "\u0001"
                                + decodeUtf8Hex(pieces[instantiatedDescriptorIndex]) + "\u0001"
                                + decodeUtf8Hex(pieces[4]) + "\u0001"
                                + decodeUtf8Hex(pieces[invokeKindIndex])
                );
            }
        }
        if (helperName.startsWith("ir_rt_type_switch__")) {
            String[] pieces = helperName.split("__");
            ArrayList<String> caseTypes = new ArrayList<>();
            for (int index = 1; index < pieces.length; index++) {
                caseTypes.add(decodeDisplayToken(pieces[index]));
            }
            return new Helper(Kind.TYPE_SWITCH, sig, null, null, null, null, List.copyOf(caseTypes), false, false, null, null);
        }
        if (helperName.startsWith("ir_rt_record__")) {
            String[] pieces = helperName.split("__");
            if (pieces.length >= 6) {
                ArrayList<String> fieldNames = new ArrayList<>();
                ArrayList<String> componentTypes = new ArrayList<>();
                for (int index = 4; index + 1 < pieces.length; index += 2) {
                    fieldNames.add(decodeToken(pieces[index]));
                    componentTypes.add(decodeDisplayToken(pieces[index + 1]));
                }
                String[] componentLabels = decodeUtf8Hex(pieces[3]).split(";", -1);
                return new Helper(
                        Kind.RECORD,
                        sig,
                        decodeToken(pieces[1]),
                        decodeToken(pieces[2]),
                        null,
                        null,
                        List.copyOf(componentTypes),
                        false,
                        false,
                        null,
                        String.join("\u0001", componentLabels) + "\u0002" + String.join("\u0001", fieldNames)
                );
            }
        }
        if (helperName.startsWith("ir_rt_get_static__") || helperName.startsWith("ir_rt_put_static__") || helperName.startsWith("ir_rt_get_field__") || helperName.startsWith("ir_rt_put_field__")) {
            int split = helperName.indexOf("__");
            String prefix = helperName.substring(0, split);
            String[] pieces = helperName.substring(split + 2).split("__");
            if (pieces.length >= 3) {
                String displayType = decodeDisplayToken(pieces[2]);
                return new Helper(Kind.FIELD, sig, decodeToken(pieces[0]), decodeToken(pieces[1]), desc(displayType), displayType, List.of(), prefix.contains("static"), prefix.startsWith("ir_rt_get"), null, null);
            }
        }
        if (helperName.startsWith("ir_rt_call__")) {
            String[] pieces = helperName.split("__");
            if (pieces.length >= 5) {
                ArrayList<String> paramTypes = new ArrayList<>();
                for (int index = 4; index < pieces.length - 1; index++) paramTypes.add(decodeDisplayToken(pieces[index]));
                String returnType = decodeDisplayToken(pieces[pieces.length - 1]);
                return new Helper(Kind.CALL, sig, decodeToken(pieces[2]), normMethod(decodeToken(pieces[3])), methodDesc(paramTypes, returnType), returnType, List.copyOf(paramTypes), pieces[1].equals("static"), false, null, pieces[1]);
            }
        }
        return new Helper(Kind.DEFAULT, sig, null, null, null, null, List.of(), false, false, null, null);
    }

    private String renderParams(List<String> params) {
        if (params.isEmpty()) return "void";
        ArrayList<String> rendered = new ArrayList<>(params.size());
        for (int index = 0; index < params.size(); index++) rendered.add(cType(params.get(index)) + " arg" + index);
        return String.join(", ", rendered);
    }

    private String cType(String llvmType) {
        return switch (llvmType) {
            case "void" -> "void";
            case "i1" -> "uint8_t";
            case "i8" -> "int8_t";
            case "i16" -> "int16_t";
            case "i32" -> "int32_t";
            case "i64" -> "int64_t";
            case "float" -> "float";
            case "double" -> "double";
            case "ptr" -> "void*";
            default -> throw new IllegalArgumentException("Unsupported LLVM stub type: " + llvmType);
        };
    }

    private String defaultReturn(String llvmType) {
        return switch (llvmType) {
            case "void" -> "return;";
            case "ptr" -> "return NULL;";
            case "float" -> "return 0.0f;";
            case "double" -> "return 0.0;";
            case "i1", "i8", "i16", "i32", "i64" -> "return 0;";
            default -> throw new IllegalArgumentException("Unsupported LLVM stub return type: " + llvmType);
        };
    }

    private String decode(String token) {
        return token.replace("0lb0", "[").replace("0rb0", "]").replace("_u_", "\u0001").replace("_s_", "/").replace("_d_", "$").replace("\u0001", "_");
    }

    private String decodeToken(String token) {
        return looksLikeHexToken(token) ? decodeUtf8Hex(token) : decode(token);
    }

    private String normMethod(String name) {
        return name.equals("_init") || name.equals("_init_") ? "<init>" : name;
    }

    private String methodDesc(List<String> params, String returnType) {
        StringBuilder out = new StringBuilder("(");
        for (String param : params) out.append(desc(param));
        return out.append(')').append(desc(returnType)).toString();
    }

    private String decodeDisplay(String token) {
        String decoded = decode(token);
        return stripSyntheticTypePrefix(decoded);
    }

    private String decodeDisplayToken(String token) {
        return stripSyntheticTypePrefix(decodeToken(token));
    }

    private String decodeDisplayHexAware(String token) {
        return stripSyntheticTypePrefix(decodeUtf8Hex(token));
    }

    private boolean looksLikeHexToken(String token) {
        if ((token.length() & 1) != 0 || token.isEmpty()) {
            return false;
        }
        for (int index = 0; index < token.length(); index++) {
            char current = token.charAt(index);
            boolean hex = (current >= '0' && current <= '9')
                    || (current >= 'a' && current <= 'f')
                    || (current >= 'A' && current <= 'F');
            if (!hex) {
                return false;
            }
        }
        return true;
    }

    private boolean isEnumTypeSwitchCase(String token) {
        return token.startsWith("enum:");
    }

    private String enumTypeSwitchOwner(String token) {
        int split = token.lastIndexOf(':');
        if (split <= "enum:".length()) {
            throw new IllegalArgumentException("Malformed enum typeSwitch token: " + token);
        }
        return token.substring("enum:".length(), split);
    }

    private String enumTypeSwitchConstant(String token) {
        int split = token.lastIndexOf(':');
        if (split < 0 || split == token.length() - 1) {
            throw new IllegalArgumentException("Malformed enum typeSwitch token: " + token);
        }
        return token.substring(split + 1);
    }

    private String desc(String displayType) {
        String normalized = stripSyntheticTypePrefix(displayType);
        return switch (normalized) {
            case "void" -> "V";
            case "boolean" -> "Z";
            case "byte" -> "B";
            case "short" -> "S";
            case "char" -> "C";
            case "int" -> "I";
            case "long" -> "J";
            case "float" -> "F";
            case "double" -> "D";
            default -> normalized.endsWith("[]") ? "[" + desc(normalized.substring(0, normalized.length() - 2)) : "L" + normalized + ";";
        };
    }

    private String stripSyntheticTypePrefix(String displayType) {
        String decoded = displayType;
        while (decoded.startsWith("_")) {
            String candidate = decoded.substring(1);
            if (candidate.equals("void") || candidate.equals("boolean") || candidate.equals("byte")
                    || candidate.equals("short") || candidate.equals("char") || candidate.equals("int")
                    || candidate.equals("long") || candidate.equals("float") || candidate.equals("double")
                    || candidate.endsWith("[]") || candidate.contains("/") || candidate.startsWith("[")) {
                decoded = candidate;
                continue;
            }
            break;
        }
        return decoded;
    }

    private String renderHexBytes(String hex) {
        StringBuilder out = new StringBuilder();
        for (int index = 0; index + 1 < hex.length(); index += 2) {
            out.append("0x").append(hex, index, index + 2).append(", ");
        }
        return out.toString();
    }

    private String renderHexBytes(byte[] bytes) {
        StringBuilder out = new StringBuilder();
        for (byte current : bytes) {
            out.append("0x").append(String.format("%02x", current & 0xff)).append(", ");
        }
        return out.toString();
    }

    private String escapeCString(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private String decodeUtf8Hex(String hex) {
        if ((hex.length() & 1) != 0 || !hex.matches("[0-9a-fA-F]+")) {
            return decode(hex);
        }
        byte[] bytes = new byte[hex.length() / 2];
        for (int index = 0; index + 1 < hex.length(); index += 2) {
            bytes[index / 2] = (byte) Integer.parseInt(hex.substring(index, index + 2), 16);
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private EncodedMetaCString encodeMetaCString(String value) {
        byte[] plain = value.getBytes(StandardCharsets.UTF_8);
        int seed = 0x41;
        for (byte current : plain) {
            seed = (seed * 131 + (current & 0xff)) & 0xff;
        }
        byte[] encoded = new byte[plain.length];
        for (int index = 0; index < plain.length; index++) {
            int mask = (seed + (index * 29)) & 0xff;
            encoded[index] = (byte) ((plain[index] & 0xff) ^ mask);
        }
        return new EncodedMetaCString(encoded, (byte) seed);
    }

    private byte[] encodeModifiedUtf8(String value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(value.length() * 3);
        for (int index = 0; index < value.length(); index++) {
            int current = value.charAt(index);
            if (current >= 0x0001 && current <= 0x007f) {
                out.write(current);
            } else if (current <= 0x07ff) {
                out.write(0xc0 | ((current >> 6) & 0x1f));
                out.write(0x80 | (current & 0x3f));
            } else {
                out.write(0xe0 | ((current >> 12) & 0x0f));
                out.write(0x80 | ((current >> 6) & 0x3f));
                out.write(0x80 | (current & 0x3f));
            }
        }
        return out.toByteArray();
    }

    private String lambdaSamName(Helper helper) {
        return helper.hexBytes();
    }

    private String emittedBuiltinHelperName(String semanticName) {
        return JniMangler.opaqueSymbol("helper|" + semanticName, 24);
    }

    private String obfuscatedU32(String decodeFunctionName, int value, int salt) {
        long unsignedValue = Integer.toUnsignedLong(value);
        long mask = Integer.toUnsignedLong(Integer.rotateLeft(value ^ (salt * 0x45d9f3b), (salt & 15) + 1) ^ 0xA5A5A5A5);
        if (mask == 0L) {
            mask = 0x9E3779B9L ^ Integer.toUnsignedLong(salt);
        }
        long encoded = unsignedValue ^ mask;
        return decodeFunctionName + "(" + renderUnsignedIntLiteral(encoded) + "u, " + renderUnsignedIntLiteral(mask) + "u)";
    }

    private String renderUnsignedIntLiteral(long value) {
        return String.format("0x%08x", value & 0xffffffffL);
    }

    private record EncodedMetaCString(byte[] bytes, byte seed) {
    }

    /**
     * Names emitted into generated C for runtime-internal support functions/globals.
     * Semantic helper identifiers stay in Java-side metadata so the generator can still
     * parse behavior, but the native output uses the same opaque naming style as other
     * generated functions.
     */
    private record RuntimeNames(
            String currentEnvGlobal,
            String envAccessor,
            String decodeU32,
            String opaqueGate,
            String rotl32,
            String load32Le,
            String store32Le,
            String rotl8,
            String chacha20Block,
            String chacha20Xor,
            String deriveStringKey,
            String newUtf8String,
            String decodeMetaCString,
            String findClassObf,
            String newStringUtfObf,
            String getMethodIdObf,
            String getFieldIdObf,
            String registerNativesForClass
    ) {
        private static RuntimeNames create() {
            return new RuntimeNames(
                    JniMangler.opaqueSymbol("runtime|current-env-global", 24),
                    opaque("env-accessor"),
                    opaque("decode-u32"),
                    opaque("opaque-gate"),
                    opaque("rotl32"),
                    opaque("load32-le"),
                    opaque("store32-le"),
                    opaque("rotl8"),
                    opaque("chacha20-block"),
                    opaque("chacha20-xor"),
                    opaque("derive-string-key"),
                    opaque("new-utf8-string"),
                    opaque("decode-meta-cstr"),
                    opaque("find-class-obf"),
                    opaque("new-string-utf-obf"),
                    opaque("get-method-id-obf"),
                    opaque("get-field-id-obf"),
                    opaque("register-natives-for-class")
            );
        }

        private static String opaque(String purpose) {
            return JniMangler.opaqueSymbol("runtime-internal|" + purpose, 24);
        }
    }

    private String lambdaSamDescriptor(Helper helper) {
        return lambdaPayloadPieces(helper)[0];
    }

    private String lambdaInstantiatedDescriptor(Helper helper) {
        return lambdaPayloadPieces(helper)[1];
    }

    private String lambdaCallerOwner(Helper helper) {
        return lambdaPayloadPieces(helper)[2];
    }

    private String lambdaInvokeKind(Helper helper) {
        String[] payload = lambdaInvokePayloadPieces(helper);
        return payload[0];
    }

    private String lambdaBootstrapMethod(Helper helper) {
        String[] payload = lambdaInvokePayloadPieces(helper);
        return payload.length >= 2 ? payload[1] : "metafactory";
    }

    private int lambdaAltFlags(Helper helper) {
        String[] payload = lambdaInvokePayloadPieces(helper);
        return payload.length >= 3 && !payload[2].isEmpty() ? Integer.parseInt(payload[2]) : 0;
    }

    private List<String> lambdaMarkerInterfaces(Helper helper) {
        String[] payload = lambdaInvokePayloadPieces(helper);
        if (payload.length < 4 || payload[3].isEmpty()) {
            return List.of();
        }
        return List.of(payload[3].split("\u0003", -1));
    }

    private List<String> lambdaBridgeDescriptors(Helper helper) {
        String[] payload = lambdaInvokePayloadPieces(helper);
        if (payload.length < 5 || payload[4].isEmpty()) {
            return List.of();
        }
        return List.of(payload[4].split("\u0003", -1));
    }

    private List<String> recordComponentLabels(Helper helper) {
        if (helper.payload() == null || helper.payload().isBlank()) {
            return List.of();
        }
        String[] pieces = helper.payload().split("\u0002", 2);
        return pieces[0].isEmpty() ? List.of() : List.of(pieces[0].split("\u0001", -1));
    }

    private List<String> recordFieldNames(Helper helper) {
        if (helper.payload() == null || helper.payload().isBlank()) {
            return List.of();
        }
        String[] pieces = helper.payload().split("\u0002", 2);
        if (pieces.length < 2 || pieces[1].isEmpty()) {
            return List.of();
        }
        return List.of(pieces[1].split("\u0001", -1));
    }

    private String recordSimpleName(String ownerInternalName) {
        int slash = ownerInternalName.lastIndexOf('/');
        int dollar = ownerInternalName.lastIndexOf('$');
        return ownerInternalName.substring(Math.max(slash, dollar) + 1);
    }

    private String[] lambdaPayloadPieces(Helper helper) {
        if (helper.payload() == null) {
            throw new IllegalArgumentException("lambda helper payload is missing");
        }
        String[] pieces = helper.payload().split("\u0001", 4);
        if (pieces.length < 3) {
            throw new IllegalArgumentException("lambda helper payload is malformed");
        }
        return pieces;
    }

    private String[] lambdaInvokePayloadPieces(Helper helper) {
        String[] payload = lambdaPayloadPieces(helper);
        if (payload.length < 4 || payload[3].isEmpty()) {
            return new String[]{"virtual"};
        }
        return payload[3].split("\u0002", -1);
    }

    private void appendRecordBoxedComponent(StringBuilder out, String componentType, String fieldId, String sourceObject,
                                            String targetName, String contextName) {
        String failure = switch (contextName) {
            case "toString" -> "NULL";
            case "hashCode", "equals" -> "0";
            default -> "NULL";
        };
        switch (componentType) {
            case "boolean" -> appendPrimitiveBox(out, targetName, sourceObject, fieldId, "jboolean", "GetBooleanField",
                    "java/lang/Boolean", "valueOf", "(Z)Ljava/lang/Boolean;", failure);
            case "byte" -> appendPrimitiveBox(out, targetName, sourceObject, fieldId, "jbyte", "GetByteField",
                    "java/lang/Byte", "valueOf", "(B)Ljava/lang/Byte;", failure);
            case "char" -> appendPrimitiveBox(out, targetName, sourceObject, fieldId, "jchar", "GetCharField",
                    "java/lang/Character", "valueOf", "(C)Ljava/lang/Character;", failure);
            case "short" -> appendPrimitiveBox(out, targetName, sourceObject, fieldId, "jshort", "GetShortField",
                    "java/lang/Short", "valueOf", "(S)Ljava/lang/Short;", failure);
            case "int" -> appendPrimitiveBox(out, targetName, sourceObject, fieldId, "jint", "GetIntField",
                    "java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;", failure);
            case "long" -> appendPrimitiveBox(out, targetName, sourceObject, fieldId, "jlong", "GetLongField",
                    "java/lang/Long", "valueOf", "(J)Ljava/lang/Long;", failure);
            case "float" -> appendPrimitiveBox(out, targetName, sourceObject, fieldId, "jfloat", "GetFloatField",
                    "java/lang/Float", "valueOf", "(F)Ljava/lang/Float;", failure);
            case "double" -> appendPrimitiveBox(out, targetName, sourceObject, fieldId, "jdouble", "GetDoubleField",
                    "java/lang/Double", "valueOf", "(D)Ljava/lang/Double;", failure);
            default -> {
                out.append("    jobject ").append(targetName).append(" = (*env)->GetObjectField(env, ").append(sourceObject)
                        .append(", ").append(fieldId).append(");\n");
            }
        }
    }

    private void appendPrimitiveBox(StringBuilder out, String targetName, String sourceObject, String fieldId,
                                    String primitiveCType, String fieldGetCall, String boxedClassName,
                                    String boxedMethodName, String boxedDescriptor, String failure) {
        out.append("    ").append(primitiveCType).append(' ').append(targetName).append("_value = (*env)->")
                .append(fieldGetCall).append("(env, ").append(sourceObject).append(", ").append(fieldId).append(");\n");
        appendFindClassLookup(out, targetName + "_boxClass", boxedClassName, 4);
        out.append("    if (").append(targetName).append("_boxClass == NULL) return ").append(failure).append(";\n");
        appendGetMethodIdLookup(out, targetName + "_box", targetName + "_boxClass", true, boxedMethodName, boxedDescriptor, 4);
        out.append("    if (").append(targetName).append("_box == NULL) return ").append(failure).append(";\n");
        out.append("    jobject ").append(targetName).append(" = (*env)->CallStaticObjectMethod(env, ")
                .append(targetName).append("_boxClass, ").append(targetName).append("_box, ").append(targetName).append("_value);\n");
        out.append("    if (").append(targetName).append(" == NULL && (*env)->ExceptionCheck(env)) return ").append(failure).append(";\n");
    }

    private void appendClassLiteralLoad(StringBuilder out, String variableName, String displayType) {
        switch (displayType) {
            case "boolean" -> appendPrimitiveTypeLookup(out, variableName, "java/lang/Boolean");
            case "byte" -> appendPrimitiveTypeLookup(out, variableName, "java/lang/Byte");
            case "char" -> appendPrimitiveTypeLookup(out, variableName, "java/lang/Character");
            case "short" -> appendPrimitiveTypeLookup(out, variableName, "java/lang/Short");
            case "int" -> appendPrimitiveTypeLookup(out, variableName, "java/lang/Integer");
            case "long" -> appendPrimitiveTypeLookup(out, variableName, "java/lang/Long");
            case "float" -> appendPrimitiveTypeLookup(out, variableName, "java/lang/Float");
            case "double" -> appendPrimitiveTypeLookup(out, variableName, "java/lang/Double");
            default -> {
                String className = displayType.endsWith("[]") ? desc(displayType) : displayType;
                appendEncodedCString(out, variableName + "_class_name", className, 4);
                out.append("    jclass ").append(variableName).append(" = ").append(runtimeNames.findClassObf())
                        .append("(env, ").append(variableName).append("_class_name, ")
                        .append(variableName).append("_class_name_len, ")
                        .append(variableName).append("_class_name_seed);\n");
            }
        }
    }

    private void appendPrimitiveTypeLookup(StringBuilder out, String variableName, String boxedInternalName) {
        appendFindClassLookup(out, variableName + "_box", boxedInternalName, 4);
        out.append("    if (").append(variableName).append("_box == NULL) {\n");
        out.append("        return NULL;\n");
        out.append("    }\n");
        appendGetFieldIdLookup(out, variableName + "_field", variableName + "_box", true, "TYPE", "Ljava/lang/Class;", 4);
        out.append("    if (").append(variableName).append("_field == NULL) {\n");
        out.append("        (*env)->DeleteLocalRef(env, ").append(variableName).append("_box);\n");
        out.append("        return NULL;\n");
        out.append("    }\n");
        out.append("    jclass ").append(variableName).append(" = (jclass)(*env)->GetStaticObjectField(env, ").append(variableName).append("_box, ").append(variableName).append("_field);\n");
        out.append("    (*env)->DeleteLocalRef(env, ").append(variableName).append("_box);\n");
    }

    private void appendClassLoadFromDescriptor(StringBuilder out, String variableName, String descriptor) {
        switch (descriptor) {
            case "Z" -> appendPrimitiveTypeLookup(out, variableName, "java/lang/Boolean");
            case "B" -> appendPrimitiveTypeLookup(out, variableName, "java/lang/Byte");
            case "C" -> appendPrimitiveTypeLookup(out, variableName, "java/lang/Character");
            case "S" -> appendPrimitiveTypeLookup(out, variableName, "java/lang/Short");
            case "I" -> appendPrimitiveTypeLookup(out, variableName, "java/lang/Integer");
            case "J" -> appendPrimitiveTypeLookup(out, variableName, "java/lang/Long");
            case "F" -> appendPrimitiveTypeLookup(out, variableName, "java/lang/Float");
            case "D" -> appendPrimitiveTypeLookup(out, variableName, "java/lang/Double");
            default -> {
                String className = descriptor;
                if (descriptor.startsWith("L") && descriptor.endsWith(";")) {
                    className = descriptor.substring(1, descriptor.length() - 1);
                }
                appendEncodedCString(out, variableName + "_class_name", className, 4);
                out.append("    jclass ").append(variableName).append(" = ").append(runtimeNames.findClassObf())
                        .append("(env, ").append(variableName).append("_class_name, ")
                        .append(variableName).append("_class_name_len, ")
                        .append(variableName).append("_class_name_seed);\n");
            }
        }
    }

    private void appendBoxedArgument(StringBuilder out, String displayType, String sourceName, String targetName, String failure) {
        switch (displayType) {
            case "boolean" -> appendBoxValue(out, targetName, sourceName, "(jboolean)", "java/lang/Boolean", "(Z)Ljava/lang/Boolean;", failure);
            case "byte" -> appendBoxValue(out, targetName, sourceName, "(jbyte)", "java/lang/Byte", "(B)Ljava/lang/Byte;", failure);
            case "char" -> appendBoxValue(out, targetName, sourceName, "(jchar)", "java/lang/Character", "(C)Ljava/lang/Character;", failure);
            case "short" -> appendBoxValue(out, targetName, sourceName, "(jshort)", "java/lang/Short", "(S)Ljava/lang/Short;", failure);
            case "int" -> appendBoxValue(out, targetName, sourceName, "(jint)", "java/lang/Integer", "(I)Ljava/lang/Integer;", failure);
            case "long" -> appendBoxValue(out, targetName, sourceName, "(jlong)", "java/lang/Long", "(J)Ljava/lang/Long;", failure);
            case "float" -> appendBoxValue(out, targetName, sourceName, "(jfloat)", "java/lang/Float", "(F)Ljava/lang/Float;", failure);
            case "double" -> appendBoxValue(out, targetName, sourceName, "(jdouble)", "java/lang/Double", "(D)Ljava/lang/Double;", failure);
            default -> out.append("    jobject ").append(targetName).append(" = (jobject)").append(sourceName).append(";\n");
        }
    }

    private boolean isMethodHandlePolymorphicInvoke(Helper helper) {
        return helper.kind() == Kind.CALL
                && !helper.isStatic()
                && "java/lang/invoke/MethodHandle".equals(helper.owner())
                && ("invokeExact".equals(helper.name()) || "invoke".equals(helper.name()));
    }

    private void appendMethodHandleReturn(StringBuilder out, String displayType, String sourceName, String llvmReturnType) {
        switch (displayType) {
            case "boolean" -> appendMethodHandlePrimitiveReturn(out, sourceName, "java/lang/Boolean", "booleanValue", "()Z", "(uint8_t)", llvmReturnType);
            case "byte" -> appendMethodHandlePrimitiveReturn(out, sourceName, "java/lang/Byte", "byteValue", "()B", "(int8_t)", llvmReturnType);
            case "char" -> appendMethodHandlePrimitiveReturn(out, sourceName, "java/lang/Character", "charValue", "()C", "(int16_t)", llvmReturnType);
            case "short" -> appendMethodHandlePrimitiveReturn(out, sourceName, "java/lang/Short", "shortValue", "()S", "(int16_t)", llvmReturnType);
            case "int" -> appendMethodHandlePrimitiveReturn(out, sourceName, "java/lang/Integer", "intValue", "()I", "(int32_t)", llvmReturnType);
            case "long" -> appendMethodHandlePrimitiveReturn(out, sourceName, "java/lang/Long", "longValue", "()J", "(int64_t)", llvmReturnType);
            case "float" -> appendMethodHandlePrimitiveReturn(out, sourceName, "java/lang/Float", "floatValue", "()F", "(float)", llvmReturnType);
            case "double" -> appendMethodHandlePrimitiveReturn(out, sourceName, "java/lang/Double", "doubleValue", "()D", "(double)", llvmReturnType);
            default -> out.append("    return (void*)").append(sourceName).append(";\n");
        }
    }

    private void appendMethodHandlePrimitiveReturn(StringBuilder out, String sourceName, String boxedInternalName,
                                                   String accessorName, String accessorDescriptor, String castPrefix,
                                                   String llvmReturnType) {
        out.append("    if (").append(sourceName).append(" == NULL) { ").append(defaultReturn(llvmReturnType)).append(" }\n");
        appendFindClassLookup(out, "boxClass", boxedInternalName, 4);
        out.append("    if (boxClass == NULL) { ").append(defaultReturn(llvmReturnType)).append(" }\n");
        appendGetMethodIdLookup(out, "accessor", "boxClass", false, accessorName, accessorDescriptor, 4);
        out.append("    if (accessor == NULL) { (*env)->DeleteLocalRef(env, boxClass); ").append(defaultReturn(llvmReturnType)).append(" }\n");
        out.append("    ").append(cType(llvmReturnType)).append(" value = ").append(castPrefix)
                .append("(*env)->Call")
                .append(wrapperAccessorSuffix(boxedInternalName))
                .append("Method(env, ").append(sourceName).append(", accessor);\n");
        out.append("    (*env)->DeleteLocalRef(env, boxClass);\n");
        out.append("    return value;\n");
    }

    private String wrapperAccessorSuffix(String boxedInternalName) {
        return switch (boxedInternalName) {
            case "java/lang/Boolean" -> "Boolean";
            case "java/lang/Byte" -> "Byte";
            case "java/lang/Character" -> "Char";
            case "java/lang/Short" -> "Short";
            case "java/lang/Integer" -> "Int";
            case "java/lang/Long" -> "Long";
            case "java/lang/Float" -> "Float";
            case "java/lang/Double" -> "Double";
            default -> throw new IllegalArgumentException("Unsupported boxed return type: " + boxedInternalName);
        };
    }

    private String defaultFailureLiteral(String llvmType) {
        return switch (llvmType) {
            case "void" -> "";
            case "ptr" -> "NULL";
            case "float" -> "0.0f";
            case "double" -> "0.0";
            case "i1", "i8", "i16", "i32", "i64" -> "0";
            default -> throw new IllegalArgumentException("Unsupported LLVM stub failure type: " + llvmType);
        };
    }

    private void appendBoxValue(StringBuilder out, String targetName, String sourceName, String castPrefix,
                                String boxedInternalName, String descriptor, String failure) {
        appendFindClassLookup(out, targetName + "_boxClass", boxedInternalName, 4);
        out.append("    if (").append(targetName).append("_boxClass == NULL) return ").append(failure).append(";\n");
        appendGetMethodIdLookup(out, targetName + "_valueOf", targetName + "_boxClass", true, "valueOf", descriptor, 4);
        out.append("    if (").append(targetName).append("_valueOf == NULL) return ").append(failure).append(";\n");
        out.append("    jobject ").append(targetName).append(" = (*env)->CallStaticObjectMethod(env, ")
                .append(targetName).append("_boxClass, ").append(targetName).append("_valueOf, ")
                .append(castPrefix).append(sourceName).append(");\n");
        out.append("    if (").append(targetName).append(" == NULL && (*env)->ExceptionCheck(env)) return ").append(failure).append(";\n");
    }

    private String arrayComponentClassName(String arrayDisplayType) {
        String component = arrayDisplayType.substring(0, arrayDisplayType.length() - 2);
        return component.endsWith("[]") ? desc(component) : component;
    }

    private String classLookupName(String ownerOrDisplay) {
        if (ownerOrDisplay == null) {
            return null;
        }
        if (ownerOrDisplay.endsWith("[]")) {
            return desc(ownerOrDisplay);
        }
        if (ownerOrDisplay.startsWith("[L") && !ownerOrDisplay.endsWith(";")) {
            return ownerOrDisplay + ";";
        }
        return ownerOrDisplay;
    }

    private String runtimeMethodName(Helper helper) {
        if (helper.owner() != null && helper.owner().startsWith("[") && "_clone".equals(helper.name())) {
            return "clone";
        }
        return helper.name();
    }

    private String fieldGetter(boolean isStatic, String type) {
        return switch (type) {
            case "boolean" -> isStatic ? "(uint8_t)(*env)->GetStaticBooleanField(env, clazz, field)" : "(uint8_t)(*env)->GetBooleanField(env, owner, field)";
            case "byte" -> isStatic ? "(int8_t)(*env)->GetStaticByteField(env, clazz, field)" : "(int8_t)(*env)->GetByteField(env, owner, field)";
            case "char" -> isStatic ? "(int16_t)(*env)->GetStaticCharField(env, clazz, field)" : "(int16_t)(*env)->GetCharField(env, owner, field)";
            case "short" -> isStatic ? "(int16_t)(*env)->GetStaticShortField(env, clazz, field)" : "(int16_t)(*env)->GetShortField(env, owner, field)";
            case "int" -> isStatic ? "(int32_t)(*env)->GetStaticIntField(env, clazz, field)" : "(int32_t)(*env)->GetIntField(env, owner, field)";
            case "long" -> isStatic ? "(int64_t)(*env)->GetStaticLongField(env, clazz, field)" : "(int64_t)(*env)->GetLongField(env, owner, field)";
            case "float" -> isStatic ? "(float)(*env)->GetStaticFloatField(env, clazz, field)" : "(float)(*env)->GetFloatField(env, owner, field)";
            case "double" -> isStatic ? "(double)(*env)->GetStaticDoubleField(env, clazz, field)" : "(double)(*env)->GetDoubleField(env, owner, field)";
            default -> isStatic ? "(void*)(*env)->GetStaticObjectField(env, clazz, field)" : "(void*)(*env)->GetObjectField(env, owner, field)";
        };
    }

    private String fieldSetter(boolean isStatic, String type, int argIndex) {
        String source = "arg" + argIndex;
        return switch (type) {
            case "boolean" -> isStatic ? "(*env)->SetStaticBooleanField(env, clazz, field, (jboolean)" + source + ")" : "(*env)->SetBooleanField(env, owner, field, (jboolean)" + source + ")";
            case "byte" -> isStatic ? "(*env)->SetStaticByteField(env, clazz, field, (jbyte)" + source + ")" : "(*env)->SetByteField(env, owner, field, (jbyte)" + source + ")";
            case "char" -> isStatic ? "(*env)->SetStaticCharField(env, clazz, field, (jchar)" + source + ")" : "(*env)->SetCharField(env, owner, field, (jchar)" + source + ")";
            case "short" -> isStatic ? "(*env)->SetStaticShortField(env, clazz, field, (jshort)" + source + ")" : "(*env)->SetShortField(env, owner, field, (jshort)" + source + ")";
            case "int" -> isStatic ? "(*env)->SetStaticIntField(env, clazz, field, (jint)" + source + ")" : "(*env)->SetIntField(env, owner, field, (jint)" + source + ")";
            case "long" -> isStatic ? "(*env)->SetStaticLongField(env, clazz, field, (jlong)" + source + ")" : "(*env)->SetLongField(env, owner, field, (jlong)" + source + ")";
            case "float" -> isStatic ? "(*env)->SetStaticFloatField(env, clazz, field, (jfloat)" + source + ")" : "(*env)->SetFloatField(env, owner, field, (jfloat)" + source + ")";
            case "double" -> isStatic ? "(*env)->SetStaticDoubleField(env, clazz, field, (jdouble)" + source + ")" : "(*env)->SetDoubleField(env, owner, field, (jdouble)" + source + ")";
            default -> isStatic ? "(*env)->SetStaticObjectField(env, clazz, field, (jobject)" + source + ")" : "(*env)->SetObjectField(env, owner, field, (jobject)" + source + ")";
        };
    }

    private String jvalueAssign(String destination, String type, String source) {
        return switch (type) {
            case "boolean" -> destination + ".z = (jboolean)" + source + ";";
            case "byte" -> destination + ".b = (jbyte)" + source + ";";
            case "char" -> destination + ".c = (jchar)" + source + ";";
            case "short" -> destination + ".s = (jshort)" + source + ";";
            case "int" -> destination + ".i = (jint)" + source + ";";
            case "long" -> destination + ".j = (jlong)" + source + ";";
            case "float" -> destination + ".f = (jfloat)" + source + ";";
            case "double" -> destination + ".d = (jdouble)" + source + ";";
            default -> destination + ".l = (jobject)" + source + ";";
        };
    }

    private String callExpr(Helper helper) {
        String receiver = helper.isStatic() ? "clazz" : "receiver";
        if ("<init>".equals(helper.name())) {
            return "(*env)->CallNonvirtualVoidMethodA(env, receiver, clazz, method, args)";
        }
        if ("special".equals(helper.payload())) {
            return switch (helper.returnType()) {
                case "void" -> "(*env)->CallNonvirtualVoidMethodA(env, receiver, clazz, method, args)";
                case "boolean" -> "(*env)->CallNonvirtualBooleanMethodA(env, receiver, clazz, method, args)";
                case "byte" -> "(*env)->CallNonvirtualByteMethodA(env, receiver, clazz, method, args)";
                case "char" -> "(*env)->CallNonvirtualCharMethodA(env, receiver, clazz, method, args)";
                case "short" -> "(*env)->CallNonvirtualShortMethodA(env, receiver, clazz, method, args)";
                case "int" -> "(*env)->CallNonvirtualIntMethodA(env, receiver, clazz, method, args)";
                case "long" -> "(*env)->CallNonvirtualLongMethodA(env, receiver, clazz, method, args)";
                case "float" -> "(*env)->CallNonvirtualFloatMethodA(env, receiver, clazz, method, args)";
                case "double" -> "(*env)->CallNonvirtualDoubleMethodA(env, receiver, clazz, method, args)";
                default -> "(*env)->CallNonvirtualObjectMethodA(env, receiver, clazz, method, args)";
            };
        }
        return switch (helper.returnType()) {
            case "void" -> helper.isStatic() ? "(*env)->CallStaticVoidMethodA(env, clazz, method, args)" : "(*env)->CallVoidMethodA(env, " + receiver + ", method, args)";
            case "boolean" -> helper.isStatic() ? "(*env)->CallStaticBooleanMethodA(env, clazz, method, args)" : "(*env)->CallBooleanMethodA(env, " + receiver + ", method, args)";
            case "byte" -> helper.isStatic() ? "(*env)->CallStaticByteMethodA(env, clazz, method, args)" : "(*env)->CallByteMethodA(env, " + receiver + ", method, args)";
            case "char" -> helper.isStatic() ? "(*env)->CallStaticCharMethodA(env, clazz, method, args)" : "(*env)->CallCharMethodA(env, " + receiver + ", method, args)";
            case "short" -> helper.isStatic() ? "(*env)->CallStaticShortMethodA(env, clazz, method, args)" : "(*env)->CallShortMethodA(env, " + receiver + ", method, args)";
            case "int" -> helper.isStatic() ? "(*env)->CallStaticIntMethodA(env, clazz, method, args)" : "(*env)->CallIntMethodA(env, " + receiver + ", method, args)";
            case "long" -> helper.isStatic() ? "(*env)->CallStaticLongMethodA(env, clazz, method, args)" : "(*env)->CallLongMethodA(env, " + receiver + ", method, args)";
            case "float" -> helper.isStatic() ? "(*env)->CallStaticFloatMethodA(env, clazz, method, args)" : "(*env)->CallFloatMethodA(env, " + receiver + ", method, args)";
            case "double" -> helper.isStatic() ? "(*env)->CallStaticDoubleMethodA(env, clazz, method, args)" : "(*env)->CallDoubleMethodA(env, " + receiver + ", method, args)";
            default -> helper.isStatic() ? "(*env)->CallStaticObjectMethodA(env, clazz, method, args)" : "(*env)->CallObjectMethodA(env, " + receiver + ", method, args)";
        };
    }

    private String castReturn(String type, String expr) {
        return switch (type) {
            case "boolean" -> "(uint8_t)" + expr;
            case "byte" -> "(int8_t)" + expr;
            case "char" -> "(int16_t)" + expr;
            case "short" -> "(int16_t)" + expr;
            case "int" -> "(int32_t)" + expr;
            case "long" -> "(int64_t)" + expr;
            case "float" -> "(float)" + expr;
            case "double" -> "(double)" + expr;
            default -> "(void*)" + expr;
        };
    }

    private record Sig(String returnType, List<String> params) {
    }

    private record ObfuscatedStringHelperData(
            boolean cacheStrings,
            int siteId,
            String nonceHex,
            String seedAHex,
            String seedBHex,
            String cipherHex
    ) {
    }

    private record HelperSpec(String emittedName, String semanticName, Sig sig) {
    }

    private record Helper(Kind kind, Sig sig, String owner, String name, String descriptor, String displayType,
                          List<String> paramTypes, boolean isStatic, boolean isLoad, String hexBytes, String payload) {
        String returnType() {
            return displayType;
        }
    }

    public record RuntimeFragment(String fileName, String sourceText) {
    }

    public record RuntimeSourceSet(String monolithicText, List<RuntimeFragment> sourceFiles) {
    }

    private record HelperWithSource(HelperSpec helper, String source, int estimatedBytes) {
    }

    private static final class HelperBucket {
        private final ArrayList<HelperWithSource> helpers;
        private int estimatedBytes;

        private HelperBucket(ArrayList<HelperWithSource> helpers, int estimatedBytes) {
            this.helpers = helpers;
            this.estimatedBytes = estimatedBytes;
        }

        private ArrayList<HelperWithSource> helpers() {
            return helpers;
        }

        private int estimatedBytes() {
            return estimatedBytes;
        }
    }

    private enum Kind {
        THROW,
        CURRENT_EXCEPTION,
        EXCEPTION_PENDING,
        NEW_OBJECT,
        NEW_INIT,
        LDC_STRING,
        OBFUSCATED_STRING,
        LDC_CLASS,
        CONCAT,
        OBFUSCATED_CONCAT,
        LAMBDA,
        INSTANCEOF,
        REF_CMP,
        CMP,
        TYPE_SWITCH,
        RECORD,
        FIELD,
        CALL,
        ARRAY_NEW,
        ARRAY_MULTI_NEW,
        ARRAY_LOAD,
        ARRAY_STORE,
        ARRAY_LENGTH,
        MONITOR,
        DEFAULT
    }
}
