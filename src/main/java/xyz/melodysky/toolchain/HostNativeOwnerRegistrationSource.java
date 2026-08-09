package xyz.melodysky.toolchain;

import java.util.List;
import xyz.melodysky.toolchain.nativetext.NativeTextCEmitter;
import xyz.melodysky.toolchain.nativetext.NativeTextEncoding;

/** Emits declarations and a cleanup-safe registration function for one owner. */
final class HostNativeOwnerRegistrationSource {
    private final NativeTextCEmitter textEmitter = new NativeTextCEmitter();

    String emit(
            NativeRegistrationTextPlan.Owner owner,
            HostNativeRegistrationFailureLeafSource.Plan failureLeaves) {
        NativeRegistrationTextStorageLayout textLayout =
                NativeRegistrationTextStorageLayout.plan(
                        owner);
        StringBuilder source = new StringBuilder();
        appendCiphertextDeclarations(
                source,
                owner,
                textLayout);
        appendRegistrationFunction(
                source,
                owner,
                textLayout,
                failureLeaves);
        return source.toString();
    }

    private void appendCiphertextDeclarations(
            StringBuilder source,
            NativeRegistrationTextPlan.Owner owner,
            NativeRegistrationTextStorageLayout textLayout) {
        source.append(textEmitter.ciphertextDeclaration(owner.ownerText()));
        for (NativeRegistrationTextStorageLayout.Text text
                : textLayout.texts()) {
            source.append(textEmitter.ciphertextDeclaration(
                    text.encoding()));
        }
        source.append('\n');
    }

    private void appendRegistrationFunction(
            StringBuilder source,
            NativeRegistrationTextPlan.Owner owner,
            NativeRegistrationTextStorageLayout textLayout,
            HostNativeRegistrationFailureLeafSource.Plan failureLeaves) {
        String suffix = physicalSuffix(owner);
        int textScratchSize = textLayout.textBytes();
        NativeRegistrationStoragePlan storage =
                NativeRegistrationStoragePlan.plan(
                        owner.bindings().size(),
                        textScratchSize);
        source.append("static jint j2ll_register_")
                .append(suffix)
                .append("(JNIEnv* env, const j2ll_registration_resolver* resolver, jclass* registered_owner) {\n")
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
                .append("    jboolean rollback_failed = JNI_FALSE;\n");
        appendStorageDeclarations(
                source,
                storage);
        source.append("    char owner_text[sizeof(")
                .append(owner.ownerText().symbol())
                .append("_cipher)];\n")
                .append("    if (registered_owner == NULL) {\n")
                .append("        return JNI_ERR;\n")
                .append("    }\n")
                .append("    *registered_owner = NULL;\n");
        appendDecode(source, owner.ownerText(), "owner_text");
        source.append("    owner_class = j2ll_class_for_registration(env, resolver, owner_text);\n")
                .append("    j2ll_native_text_zero(owner_text, sizeof(owner_text));\n")
                .append("    if (owner_class == NULL) {\n")
                .append("        goto cleanup;\n")
                .append("    }\n");
        appendStorageAllocation(
                source,
                storage);
        appendMethodTextDecode(
                source,
                textLayout);
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
                .append("));\n");
        if (!storage.usesStack()) {
            source.append("        free(text_scratch);\n");
        }
        source.append("    }\n")
                .append("    if (methods != NULL) {\n")
                .append("        j2ll_native_text_zero(methods, (size_t)count * sizeof(JNINativeMethod));\n");
        if (!storage.usesStack()) {
            source.append("        free(methods);\n");
        }
        source.append("    }\n")
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
        source.append("            ")
                .append(failureLeaves.ownerRollback().symbol())
                .append("(env);\n")
                .append("            return JNI_ERR;\n")
                .append("        }\n")
                .append("        if (registration_exception != NULL) {\n")
                .append("            throw_status = (*env)->Throw(env, registration_exception);\n")
                .append("            (*env)->DeleteLocalRef(env, registration_exception);\n")
                .append("            registration_exception = NULL;\n")
                .append("            if (throw_status != JNI_OK || !(*env)->ExceptionCheck(env)) {\n");
        source.append("                ")
                .append(failureLeaves.ownerExceptionRestore().symbol())
                .append("(env);\n")
                .append("                return JNI_ERR;\n")
                .append("            }\n")
                .append("        }\n")
                .append("    }\n");
        source.append("    return result;\n")
                .append("}\n\n");
    }

    private void appendStorageDeclarations(
            StringBuilder source,
            NativeRegistrationStoragePlan storage) {
        if (storage.usesStack()) {
            source.append("    JNINativeMethod methods_storage[")
                    .append(storage.bindingCount())
                    .append("];\n")
                    .append("    unsigned char text_scratch_storage[")
                    .append(storage.textBytes())
                    .append("];\n")
                    .append("    JNINativeMethod* methods = methods_storage;\n")
                    .append("    unsigned char* text_scratch = text_scratch_storage;\n");
            return;
        }
        source.append("    JNINativeMethod* methods = NULL;\n")
                .append("    unsigned char* text_scratch = NULL;\n");
    }

    private void appendStorageAllocation(
            StringBuilder source,
            NativeRegistrationStoragePlan storage) {
        if (storage.usesStack()) {
            return;
        }
        source.append("    methods = (JNINativeMethod*)calloc((size_t)count, sizeof(JNINativeMethod));\n")
                .append("    text_scratch = (unsigned char*)calloc(UINT64_C(")
                .append(storage.textBytes())
                .append("), 1u);\n")
                .append("    if (methods == NULL || text_scratch == NULL) {\n")
                .append("        goto cleanup;\n")
                .append("    }\n");
    }

    private void appendMethodTextDecode(
            StringBuilder source,
            NativeRegistrationTextStorageLayout textLayout) {
        for (NativeRegistrationTextStorageLayout.Text text
                : textLayout.texts()) {
            source.append(textEmitter.decodeIntoOffset(
                    text.encoding(),
                    "text_scratch",
                    text.offset(),
                    "    "));
        }
        for (int index = 0;
                index < textLayout.bindings().size();
                index++) {
            NativeRegistrationTextStorageLayout.Binding binding =
                    textLayout.bindings().get(index);
            source.append("    methods[")
                    .append(index)
                    .append("].name = (char*)(text_scratch + ")
                    .append(binding.nameOffset())
                    .append(");\n");
            source.append("    methods[")
                    .append(index)
                    .append("].signature = (char*)(text_scratch + ")
                    .append(binding.descriptorOffset())
                    .append(");\n");
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
