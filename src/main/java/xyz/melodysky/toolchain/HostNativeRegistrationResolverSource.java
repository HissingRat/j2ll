package xyz.melodysky.toolchain;

import xyz.melodysky.toolchain.nativetext.NativeTextCEmitter;

/** Emits the activation-local Loader-anchor resolver setup used by JNI_OnLoad. */
final class HostNativeRegistrationResolverSource {
    private final NativeTextCEmitter textEmitter = new NativeTextCEmitter();

    String ciphertextDeclaration(NativeRegistrationResolverPlan plan) {
        return textEmitter.ciphertextDeclaration(plan.loaderAnchorText()) + "\n";
    }

    void appendOpen(
            StringBuilder source,
            NativeRegistrationResolverPlan plan) {
        source.append("    j2ll_registration_resolver resolver = {NULL, NULL, NULL, NULL};\n")
                .append("    char loader_anchor_text[sizeof(")
                .append(plan.loaderAnchorText().symbol())
                .append("_cipher)];\n")
                .append("    if ((*env)->EnsureLocalCapacity(env, ")
                .append(plan.localCapacity())
                .append(") != JNI_OK) {\n")
                .append("        j2ll_registration_resolver_close(env, &resolver);\n")
                .append("        return JNI_ERR;\n")
                .append("    }\n");
        source.append(textEmitter.decodeInto(
                plan.loaderAnchorText(),
                "loader_anchor_text",
                "    "));
        source.append("    jint resolver_status = j2ll_registration_resolver_open(\n")
                .append("            env, loader_anchor_text, &resolver);\n")
                .append("    j2ll_native_text_zero(loader_anchor_text, sizeof(loader_anchor_text));\n")
                .append("    if (resolver_status != JNI_OK) {\n")
                .append("        j2ll_registration_resolver_close(env, &resolver);\n")
                .append("        return JNI_ERR;\n")
                .append("    }\n");
    }

    void appendClose(StringBuilder source, String indent) {
        source.append(indent)
                .append("j2ll_registration_resolver_close(env, &resolver);\n");
    }
}
