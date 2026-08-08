package xyz.melodysky.toolchain;

import java.util.List;
import java.util.Objects;
import xyz.melodysky.packaging.MethodTableHidingEntry;
import xyz.melodysky.packaging.MethodTableHidingPlan;
import xyz.melodysky.packaging.NativeRegistrationEntry;
import xyz.melodysky.packaging.NativeRegistrationPlan;
import xyz.melodysky.toolchain.nativetext.NativeTextBuildKey;
import xyz.melodysky.toolchain.nativetext.NativeTextCEmitter;

/**
 * Orchestrates owner-local, transient JNI registration functions.
 *
 * <p>Text planning and per-owner C emission remain separate so this class only
 * owns whole-plan validation and root registration ordering.</p>
 */
public final class HostNativeRegistrationSource {
    private static final NativeTextBuildKey COMPATIBILITY_BUILD_KEY =
            NativeTextBuildKey.fromUtf8("j2ll-registration-text-compatibility-v1");

    public String emit(
            NativeRegistrationPlan registrationPlan,
            MethodTableHidingPlan hidingPlan) {
        return emit(registrationPlan, hidingPlan, COMPATIBILITY_BUILD_KEY);
    }

    public String emit(
            NativeRegistrationPlan registrationPlan,
            MethodTableHidingPlan hidingPlan,
            NativeTextBuildKey buildKey) {
        validatePlan(registrationPlan, hidingPlan);
        Objects.requireNonNull(buildKey, "buildKey");
        NativeTextCEmitter textEmitter = new NativeTextCEmitter();
        StringBuilder source = new StringBuilder(textEmitter.runtimeSource());
        HostNativeRegistrationFailureLeafSource failureLeafSource =
                new HostNativeRegistrationFailureLeafSource();
        HostNativeRegistrationFailureLeafSource.Plan failureLeaves =
                failureLeafSource.plan(buildKey);
        source.append(failureLeafSource.emit(failureLeaves));
        HostNativeOwnerRegistrationSource ownerEmitter =
                new HostNativeOwnerRegistrationSource();
        List<NativeRegistrationTextPlan.Owner> owners;
        if (hidingPlan.changed()) {
            owners = physicalOwnerOrder(
                    NativeRegistrationTextPlan.hidden(hidingPlan, buildKey));
            for (NativeRegistrationTextPlan.Owner owner : owners) {
                source.append(ownerEmitter.emit(owner, failureLeaves));
            }
        } else {
            owners = physicalOwnerOrder(
                    NativeRegistrationTextPlan.ordinary(registrationPlan, buildKey));
            for (NativeRegistrationTextPlan.Owner owner : owners) {
                source.append(ownerEmitter.emit(owner, failureLeaves));
            }
        }
        appendRootRegistration(
                source,
                owners,
                "j2ll_register_" + buildKey.hashHex().substring(0, 24),
                failureLeaves);
        return source.toString();
    }

    private List<NativeRegistrationTextPlan.Owner> physicalOwnerOrder(
            List<NativeRegistrationTextPlan.Owner> owners) {
        return owners.stream()
                .sorted(java.util.Comparator.comparing(
                        owner -> owner.ownerText().symbol()))
                .toList();
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
                .flatMap(owner -> owner.registrationOrder().stream())
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

    private void appendRootRegistration(
            StringBuilder source,
            List<NativeRegistrationTextPlan.Owner> owners,
            String aggregateSymbol,
            HostNativeRegistrationFailureLeafSource.Plan failureLeaves) {
        source.append("static jint ")
                .append(aggregateSymbol)
                .append("(JavaVM* vm) {\n")
                .append("    JNIEnv* env = NULL;\n")
                .append("    if ((*vm)->GetEnv(vm, (void**)&env, JNI_VERSION_1_8) != JNI_OK) {\n")
                .append("        return JNI_ERR;\n")
                .append("    }\n");
        if (owners.isEmpty()) {
            source.append("    return JNI_VERSION_1_8;\n");
        } else {
            source.append("    jthrowable failure_exception = NULL;\n")
                    .append("    jthrowable rollback_exception = NULL;\n")
                    .append("    jthrowable observed_exception = NULL;\n")
                    .append("    jclass rollback_owner = NULL;\n")
                    .append("    jboolean rollback_failed = JNI_FALSE;\n")
                    .append("    jint unregister_status = JNI_ERR;\n")
                    .append("    jint throw_status = JNI_ERR;\n")
                    .append("    size_t registered_count = 0u;\n")
                    .append("    jclass registered_owners[")
                    .append(owners.size())
                    .append("] = {NULL};\n");
        }
        for (int index = 0; index < owners.size(); index++) {
            NativeRegistrationTextPlan.Owner owner = owners.get(index);
            source.append("    if (j2ll_register_")
                    .append(HostNativeOwnerRegistrationSource.physicalSuffix(owner))
                    .append("(env, &registered_owners[")
                    .append(index)
                    .append("]) != JNI_OK) {\n")
                    .append("        goto rollback;\n")
                    .append("    }\n")
                    .append("    registered_count = ")
                    .append(index + 1)
                    .append("u;\n");
        }
        if (!owners.isEmpty()) {
            source.append("    while (registered_count != 0u) {\n")
                    .append("        registered_count--;\n")
                    .append("        (*env)->DeleteLocalRef(env, registered_owners[registered_count]);\n")
                    .append("        registered_owners[registered_count] = NULL;\n")
                    .append("    }\n")
                    .append("    return JNI_VERSION_1_8;\n")
                    .append("rollback:\n")
                    .append("    if ((*env)->ExceptionCheck(env)) {\n")
                    .append("        failure_exception = (*env)->ExceptionOccurred(env);\n")
                    .append("        (*env)->ExceptionClear(env);\n")
                    .append("    }\n")
                    .append("    while (registered_count != 0u) {\n")
                    .append("        registered_count--;\n")
                    .append("        rollback_owner = registered_owners[registered_count];\n")
                    .append("        unregister_status = (*env)->UnregisterNatives(env, rollback_owner);\n")
                    .append("        if (unregister_status != JNI_OK) {\n")
                    .append("            rollback_failed = JNI_TRUE;\n")
                    .append("        }\n")
                    .append("        if ((*env)->ExceptionCheck(env)) {\n")
                    .append("            rollback_failed = JNI_TRUE;\n")
                    .append("            observed_exception = (*env)->ExceptionOccurred(env);\n")
                    .append("            (*env)->ExceptionClear(env);\n")
                    .append("            if (rollback_exception == NULL) {\n")
                    .append("                rollback_exception = observed_exception;\n")
                    .append("            } else {\n")
                    .append("                (*env)->DeleteLocalRef(env, observed_exception);\n")
                    .append("            }\n")
                    .append("            observed_exception = NULL;\n")
                    .append("        }\n")
                    .append("        (*env)->DeleteLocalRef(env, rollback_owner);\n")
                    .append("        registered_owners[registered_count] = NULL;\n")
                    .append("        rollback_owner = NULL;\n")
                    .append("    }\n");
            source.append("    if (rollback_failed) {\n")
                    .append("        if (failure_exception != NULL) {\n")
                    .append("            (*env)->DeleteLocalRef(env, failure_exception);\n")
                    .append("            failure_exception = NULL;\n")
                    .append("        }\n")
                    .append("        if (rollback_exception != NULL) {\n")
                    .append("            (*env)->DeleteLocalRef(env, rollback_exception);\n")
                    .append("            rollback_exception = NULL;\n")
                    .append("        }\n");
            source.append("        ")
                    .append(failureLeaves.aggregateRollback().symbol())
                    .append("(env);\n")
                    .append("        return JNI_ERR;\n")
                    .append("    }\n")
                    .append("    if (failure_exception != NULL) {\n")
                    .append("        throw_status = (*env)->Throw(env, failure_exception);\n")
                    .append("        (*env)->DeleteLocalRef(env, failure_exception);\n")
                    .append("        failure_exception = NULL;\n")
                    .append("        if (throw_status != JNI_OK || !(*env)->ExceptionCheck(env)) {\n");
            source.append("            ")
                    .append(failureLeaves.aggregateExceptionRestore().symbol())
                    .append("(env);\n")
                    .append("            return JNI_ERR;\n")
                    .append("        }\n")
                    .append("    }\n")
                    .append("    return JNI_ERR;\n");
        }
        source.append("}\n\n")
                .append("JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {\n")
                .append("    (void)reserved;\n")
                .append("    return ")
                .append(aggregateSymbol)
                .append("(vm);\n")
                .append("}\n");
    }
}
