package xyz.melodysky.toolchain;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import xyz.melodysky.packaging.MethodTableHidingEntry;
import xyz.melodysky.packaging.MethodTableHidingOwnerPlan;
import xyz.melodysky.packaging.MethodTableHidingPlan;
import xyz.melodysky.packaging.NativeRegistrationEntry;
import xyz.melodysky.packaging.NativeRegistrationPlan;
import xyz.melodysky.packaging.RegisterNativesTableBuilder;

/**
 * Emits native registration separately from the already-large JNI wrapper
 * generator.
 */
public final class HostNativeRegistrationSource {
    public String emit(
            NativeRegistrationPlan registrationPlan,
            MethodTableHidingPlan hidingPlan) {
        validatePlan(registrationPlan, hidingPlan);
        if (!hidingPlan.changed()) {
            return new RegisterNativesTableBuilder().emit(registrationPlan)
                    + ordinaryRegistration(registrationPlan);
        }
        return hiddenTables(hidingPlan) + hiddenRegistration(hidingPlan);
    }

    private void validatePlan(
            NativeRegistrationPlan registrationPlan,
            MethodTableHidingPlan hidingPlan) {
        Objects.requireNonNull(registrationPlan, "registrationPlan");
        Objects.requireNonNull(hidingPlan, "hidingPlan");
        if (!hidingPlan.enabled()) {
            return;
        }
        if (!hidingPlan.changed()) {
            if (!registrationPlan.entries().isEmpty()) {
                throw new IllegalArgumentException(
                        "enabled method-table hiding plan does not cover the native registration plan");
            }
            return;
        }
        List<NativeRegistrationEntry> planned = hidingPlan.owners().stream()
                .flatMap(owner -> owner.metadataOrder().stream())
                .map(MethodTableHidingEntry::registration)
                .sorted()
                .toList();
        List<NativeRegistrationEntry> requested = registrationPlan.entries().stream()
                .sorted()
                .toList();
        if (!planned.equals(requested)) {
            throw new IllegalArgumentException(
                    "method-table hiding plan does not match the native registration plan");
        }
    }

    private String hiddenTables(MethodTableHidingPlan plan) {
        StringBuilder builder = new StringBuilder();
        builder.append("""
                typedef struct {
                    uint64_t token;
                    const char* name;
                    const char* descriptor;
                } j2ll_hidden_method_metadata;

                typedef struct {
                    uint64_t masked_token;
                    void* function;
                } j2ll_hidden_method_function;

                """);
        for (MethodTableHidingOwnerPlan owner : plan.owners()) {
            String suffix = CIdentifier.forIdentity(owner.registrationOwner());
            builder.append("static const j2ll_hidden_method_metadata j2ll_hmm_")
                    .append(suffix)
                    .append("[] = {\n");
            for (MethodTableHidingEntry entry : owner.metadataOrder()) {
                builder.append("    {UINT64_C(0x")
                        .append(hex(entry.token()))
                        .append("), \"")
                        .append(CSourceEscaper.stringContents(entry.registration().methodName()))
                        .append("\", \"")
                        .append(CSourceEscaper.stringContents(entry.registration().descriptor()))
                        .append("\"},\n");
            }
            builder.append("};\n")
                    .append("static const j2ll_hidden_method_function j2ll_hmf_")
                    .append(suffix)
                    .append("[] = {\n");
            for (MethodTableHidingEntry entry : owner.functionOrder()) {
                builder.append("    {UINT64_C(0x")
                        .append(hex(entry.token() ^ owner.tokenMask()))
                        .append("), (void*)")
                        .append(entry.registration().nativeSymbol())
                        .append("},\n");
            }
            builder.append("};\n")
                    .append("static const int j2ll_hmc_")
                    .append(suffix)
                    .append(" = ")
                    .append(owner.metadataOrder().size())
                    .append(";\n\n");
        }
        return builder.toString();
    }

    private String hiddenRegistration(MethodTableHidingPlan plan) {
        StringBuilder builder = new StringBuilder();
        for (MethodTableHidingOwnerPlan owner : plan.owners()) {
            String suffix = CIdentifier.forIdentity(owner.registrationOwner());
            builder.append("static jint j2ll_register_")
                    .append(suffix)
                    .append("(JNIEnv* env) {\n")
                    .append("    const int count = j2ll_hmc_")
                    .append(suffix)
                    .append(";\n")
                    .append("    JNINativeMethod* methods = (JNINativeMethod*)calloc((size_t)count, sizeof(JNINativeMethod));\n")
                    .append("    if (methods == NULL) {\n")
                    .append("        return JNI_ERR;\n")
                    .append("    }\n")
                    .append("    for (int metadata_index = 0; metadata_index < count; metadata_index++) {\n")
                    .append("        uint64_t token = j2ll_hmm_")
                    .append(suffix)
                    .append("[metadata_index].token;\n")
                    .append("        int matched = 0;\n")
                    .append("        for (int function_index = 0; function_index < count; function_index++) {\n")
                    .append("            if ((j2ll_hmf_")
                    .append(suffix)
                    .append("[function_index].masked_token ^ UINT64_C(0x")
                    .append(hex(owner.tokenMask()))
                    .append(")) == token) {\n")
                    .append("                methods[metadata_index] = (JNINativeMethod){\n")
                    .append("                    (char*)j2ll_hmm_")
                    .append(suffix)
                    .append("[metadata_index].name,\n")
                    .append("                    (char*)j2ll_hmm_")
                    .append(suffix)
                    .append("[metadata_index].descriptor,\n")
                    .append("                    j2ll_hmf_")
                    .append(suffix)
                    .append("[function_index].function\n")
                    .append("                };\n")
                    .append("                matched = 1;\n")
                    .append("                break;\n")
                    .append("            }\n")
                    .append("        }\n")
                    .append("        if (!matched) {\n")
                    .append("            free(methods);\n")
                    .append("            return JNI_ERR;\n")
                    .append("        }\n")
                    .append("    }\n")
                    .append("    jclass owner = j2ll_class_for_registration(env, \"")
                    .append(CSourceEscaper.stringContents(owner.registrationOwner()))
                    .append("\");\n")
                    .append("    if (owner == NULL) {\n")
                    .append("        free(methods);\n")
                    .append("        return JNI_ERR;\n")
                    .append("    }\n")
                    .append("    jint status = (*env)->RegisterNatives(env, owner, methods, count);\n")
                    .append("    (*env)->DeleteLocalRef(env, owner);\n")
                    .append("    free(methods);\n")
                    .append("    return status == 0 ? JNI_OK : JNI_ERR;\n")
                    .append("}\n\n");
        }
        appendRootRegistration(builder, plan.owners().stream()
                .map(MethodTableHidingOwnerPlan::registrationOwner)
                .toList());
        return builder.toString();
    }

    private String ordinaryRegistration(NativeRegistrationPlan plan) {
        StringBuilder builder = new StringBuilder();
        for (String owner : entriesByOwner(plan).keySet()) {
            String registerSymbol = "j2ll_register_" + CIdentifier.forIdentity(owner);
            String tableName = "j2ll_natives_" + CIdentifier.forIdentity(owner);
            builder.append("static jint ")
                    .append(registerSymbol)
                    .append("(JNIEnv* env) {\n")
                    .append("    jclass owner = j2ll_class_for_registration(env, \"")
                    .append(CSourceEscaper.stringContents(owner))
                    .append("\");\n")
                    .append("    if (owner == NULL) {\n")
                    .append("        return JNI_ERR;\n")
                    .append("    }\n")
                    .append("    if ((*env)->RegisterNatives(env, owner, ")
                    .append(tableName)
                    .append(", ")
                    .append(tableName)
                    .append("_count) != 0) {\n")
                    .append("        (*env)->DeleteLocalRef(env, owner);\n")
                    .append("        return JNI_ERR;\n")
                    .append("    }\n")
                    .append("    (*env)->DeleteLocalRef(env, owner);\n")
                    .append("    return JNI_OK;\n")
                    .append("}\n\n");
        }
        appendRootRegistration(builder, new ArrayList<>(entriesByOwner(plan).keySet()));
        return builder.toString();
    }

    private void appendRootRegistration(StringBuilder builder, List<String> owners) {
        builder.append("JNIEXPORT jint JNICALL j2ll_register(JavaVM* vm) {\n")
                .append("    JNIEnv* env = NULL;\n")
                .append("    if ((*vm)->GetEnv(vm, (void**)&env, JNI_VERSION_1_8) != JNI_OK) {\n")
                .append("        return JNI_ERR;\n")
                .append("    }\n");
        for (String owner : owners.stream().sorted().toList()) {
            builder.append("    if (j2ll_register_")
                    .append(CIdentifier.forIdentity(owner))
                    .append("(env) != JNI_OK) {\n")
                    .append("        return JNI_ERR;\n")
                    .append("    }\n");
        }
        builder.append("    return JNI_VERSION_1_8;\n")
                .append("}\n\n")
                .append("JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {\n")
                .append("    (void)reserved;\n")
                .append("    return j2ll_register(vm);\n")
                .append("}\n");
    }

    private Map<String, List<NativeRegistrationEntry>> entriesByOwner(NativeRegistrationPlan plan) {
        Map<String, List<NativeRegistrationEntry>> result = new TreeMap<>();
        for (NativeRegistrationEntry entry : plan.entries()) {
            result.computeIfAbsent(entry.registrationOwner(), ignored -> new ArrayList<>())
                    .add(entry);
        }
        return result;
    }

    private String hex(long value) {
        return String.format(java.util.Locale.ROOT, "%016x", value);
    }
}
