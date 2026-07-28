package xyz.melodysky.toolchain;

import java.util.List;
import xyz.melodysky.toolchain.nativetext.NativeTextCEmitter;
import xyz.melodysky.toolchain.nativetext.NativeTextEncoding;

/** Emits declarations and a cleanup-safe registration function for one owner. */
final class HostNativeOwnerRegistrationSource {
    private final NativeTextCEmitter textEmitter = new NativeTextCEmitter();

    String emit(NativeRegistrationTextPlan.Owner owner) {
        StringBuilder source = new StringBuilder();
        appendCiphertextDeclarations(source, owner);
        appendRegistrationFunction(source, owner);
        return source.toString();
    }

    private void appendCiphertextDeclarations(
            StringBuilder source,
            NativeRegistrationTextPlan.Owner owner) {
        source.append(textEmitter.ciphertextDeclaration(owner.ownerText()));
        for (NativeRegistrationTextPlan.Binding binding : owner.bindings()) {
            source.append(textEmitter.ciphertextDeclaration(binding.nameText()));
            source.append(textEmitter.ciphertextDeclaration(binding.descriptorText()));
        }
        source.append(textEmitter.ciphertextDeclaration(owner.rollbackFailureText()));
        source.append(textEmitter.ciphertextDeclaration(owner.exceptionRestoreFailureText()));
        source.append('\n');
    }

    private void appendRegistrationFunction(
            StringBuilder source,
            NativeRegistrationTextPlan.Owner owner) {
        String suffix = physicalSuffix(owner);
        int textScratchSize = owner.bindings().stream()
                .mapToInt(binding -> Math.addExact(
                        binding.nameText().decodedBufferLength(),
                        binding.descriptorText().decodedBufferLength()))
                .reduce(0, Math::addExact);
        source.append("static jint j2ll_register_")
                .append(suffix)
                .append("(JNIEnv* env, jclass* registered_owner) {\n")
                .append("    const int count = ")
                .append(owner.bindings().size())
                .append(";\n")
                .append("    jint result = JNI_ERR;\n")
                .append("    jint register_status = JNI_ERR;\n")
                .append("    jint unregister_status = JNI_ERR;\n")
                .append("    jint throw_status = JNI_ERR;\n")
                .append("    jclass owner_class = NULL;\n")
                .append("    jthrowable registration_exception = NULL;\n")
                .append("    jthrowable rollback_exception = NULL;\n")
                .append("    jboolean registration_failed = JNI_FALSE;\n")
                .append("    jboolean rollback_failed = JNI_FALSE;\n")
                .append("    JNINativeMethod* methods = NULL;\n")
                .append("    unsigned char* text_scratch = NULL;\n");
        source.append("    char owner_text[sizeof(")
                .append(owner.ownerText().symbol())
                .append("_cipher)];\n")
                .append("    char rollback_failure_text[sizeof(")
                .append(owner.rollbackFailureText().symbol())
                .append("_cipher)];\n")
                .append("    char exception_restore_failure_text[sizeof(")
                .append(owner.exceptionRestoreFailureText().symbol())
                .append("_cipher)];\n")
                .append("    if (registered_owner == NULL) {\n")
                .append("        return JNI_ERR;\n")
                .append("    }\n")
                .append("    *registered_owner = NULL;\n");
        appendDecode(source, owner.ownerText(), "owner_text");
        source.append("    owner_class = j2ll_class_for_registration(env, owner_text);\n")
                .append("    j2ll_native_text_zero(owner_text, sizeof(owner_text));\n")
                .append("    if (owner_class == NULL) {\n")
                .append("        goto cleanup;\n")
                .append("    }\n")
                .append("    methods = (JNINativeMethod*)calloc((size_t)count, sizeof(JNINativeMethod));\n")
                .append("    text_scratch = (unsigned char*)calloc(UINT64_C(")
                .append(textScratchSize)
                .append("), 1u);\n");
        source.append("    if (methods == NULL || text_scratch == NULL) {\n")
                .append("        goto cleanup;\n")
                .append("    }\n");
        appendMethodTextDecode(source, owner.bindings());
        appendStraightLineFunctions(source, owner.bindings());
        source.append("    register_status = (*env)->RegisterNatives(env, owner_class, methods, count);\n")
                .append("    if (register_status != JNI_OK || (*env)->ExceptionCheck(env)) {\n")
                .append("        registration_failed = JNI_TRUE;\n")
                .append("        if ((*env)->ExceptionCheck(env)) {\n")
                .append("            registration_exception = (*env)->ExceptionOccurred(env);\n")
                .append("            (*env)->ExceptionClear(env);\n")
                .append("            if (registration_exception == NULL) {\n")
                .append("                rollback_failed = JNI_TRUE;\n")
                .append("            }\n")
                .append("        }\n")
                .append("        unregister_status = (*env)->UnregisterNatives(env, owner_class);\n")
                .append("        if (unregister_status != JNI_OK) {\n")
                .append("            rollback_failed = JNI_TRUE;\n")
                .append("        }\n")
                .append("        if ((*env)->ExceptionCheck(env)) {\n")
                .append("            rollback_exception = (*env)->ExceptionOccurred(env);\n")
                .append("            (*env)->ExceptionClear(env);\n")
                .append("            rollback_failed = JNI_TRUE;\n")
                .append("        }\n")
                .append("        goto cleanup;\n")
                .append("    }\n")
                .append("    *registered_owner = owner_class;\n")
                .append("    owner_class = NULL;\n")
                .append("    result = JNI_OK;\n")
                .append("cleanup:\n")
                .append("    if (owner_class != NULL) {\n")
                .append("        (*env)->DeleteLocalRef(env, owner_class);\n")
                .append("    }\n")
                .append("    j2ll_native_text_zero(owner_text, sizeof(owner_text));\n")
                .append("    if (text_scratch != NULL) {\n")
                .append("        j2ll_native_text_zero(text_scratch, UINT64_C(")
                .append(textScratchSize)
                .append("));\n")
                .append("        free(text_scratch);\n")
                .append("    }\n")
                .append("    if (methods != NULL) {\n")
                .append("        j2ll_native_text_zero(methods, (size_t)count * sizeof(JNINativeMethod));\n")
                .append("        free(methods);\n")
                .append("    }\n")
                .append("    if (registration_failed) {\n")
                .append("        if (rollback_failed) {\n")
                .append("            if (registration_exception != NULL) {\n")
                .append("                (*env)->DeleteLocalRef(env, registration_exception);\n")
                .append("                registration_exception = NULL;\n")
                .append("            }\n")
                .append("            if (rollback_exception != NULL) {\n")
                .append("                (*env)->DeleteLocalRef(env, rollback_exception);\n")
                .append("                rollback_exception = NULL;\n")
                .append("            }\n");
        source.append(textEmitter.decodeInto(
                owner.rollbackFailureText(),
                "rollback_failure_text",
                "            "));
        source.append("            (*env)->FatalError(env, rollback_failure_text);\n")
                .append("            j2ll_native_text_zero(rollback_failure_text, sizeof(rollback_failure_text));\n")
                .append("            return JNI_ERR;\n")
                .append("        }\n")
                .append("        if (registration_exception != NULL) {\n")
                .append("            throw_status = (*env)->Throw(env, registration_exception);\n")
                .append("            (*env)->DeleteLocalRef(env, registration_exception);\n")
                .append("            registration_exception = NULL;\n")
                .append("            if (throw_status != JNI_OK || !(*env)->ExceptionCheck(env)) {\n");
        source.append(textEmitter.decodeInto(
                owner.exceptionRestoreFailureText(),
                "exception_restore_failure_text",
                "                "));
        source.append("                (*env)->FatalError(env, exception_restore_failure_text);\n")
                .append("                j2ll_native_text_zero(exception_restore_failure_text, sizeof(exception_restore_failure_text));\n")
                .append("                return JNI_ERR;\n")
                .append("            }\n")
                .append("        }\n")
                .append("    }\n");
        source.append("    return result;\n")
                .append("}\n\n");
    }

    private void appendMethodTextDecode(
            StringBuilder source,
            List<NativeRegistrationTextPlan.Binding> bindings) {
        int offset = 0;
        for (int index = 0; index < bindings.size(); index++) {
            NativeRegistrationTextPlan.Binding binding = bindings.get(index);
            source.append(textEmitter.decodeIntoOffset(
                    binding.nameText(),
                    "text_scratch",
                    offset,
                    "    "));
            source.append("    methods[")
                    .append(index)
                    .append("].name = (char*)(text_scratch + ")
                    .append(offset)
                    .append(");\n");
            offset = Math.addExact(offset, binding.nameText().decodedBufferLength());
            source.append(textEmitter.decodeIntoOffset(
                    binding.descriptorText(),
                    "text_scratch",
                    offset,
                    "    "));
            source.append("    methods[")
                    .append(index)
                    .append("].signature = (char*)(text_scratch + ")
                    .append(offset)
                    .append(");\n");
            offset = Math.addExact(offset, binding.descriptorText().decodedBufferLength());
        }
    }

    private void appendStraightLineFunctions(
            StringBuilder source,
            List<NativeRegistrationTextPlan.Binding> bindings) {
        for (int index = 0; index < bindings.size(); index++) {
            source.append("    methods[")
                    .append(index)
                    .append("].fnPtr = (void*)")
                    .append(bindings.get(index).registration().nativeSymbol())
                    .append(";\n");
        }
    }

    private void appendDecode(
            StringBuilder source,
            NativeTextEncoding encoding,
            String destination) {
        source.append(textEmitter.decodeInto(
                encoding,
                destination,
                "    "));
    }

    static String physicalSuffix(NativeRegistrationTextPlan.Owner owner) {
        return CIdentifier.forIdentity(owner.ownerText().symbol());
    }
}
