package xyz.melodysky.toolchain;

import java.util.List;
import java.util.Objects;
import xyz.melodysky.packaging.MethodTableHidingEntry;
import xyz.melodysky.packaging.MethodTableHidingPlan;
import xyz.melodysky.packaging.NativeRegistrationEntry;
import xyz.melodysky.packaging.NativeRegistrationPlan;
import xyz.melodysky.packaging.RuntimeLoaderPlan;
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
        return emit(
                registrationPlan,
                hidingPlan,
                RuntimeLoaderPlan.create("native0"),
                COMPATIBILITY_BUILD_KEY);
    }

    public String emit(
            NativeRegistrationPlan registrationPlan,
            MethodTableHidingPlan hidingPlan,
            NativeTextBuildKey buildKey) {
        return emit(
                registrationPlan,
                hidingPlan,
                RuntimeLoaderPlan.create("native0"),
                buildKey);
    }

    public String emit(
            NativeRegistrationPlan registrationPlan,
            MethodTableHidingPlan hidingPlan,
            RuntimeLoaderPlan runtimeLoaderPlan) {
        return emit(
                registrationPlan,
                hidingPlan,
                runtimeLoaderPlan,
                COMPATIBILITY_BUILD_KEY);
    }

    public String emit(
            NativeRegistrationPlan registrationPlan,
            MethodTableHidingPlan hidingPlan,
            RuntimeLoaderPlan runtimeLoaderPlan,
            NativeTextBuildKey buildKey) {
        return emitWithPlan(
                registrationPlan,
                hidingPlan,
                runtimeLoaderPlan,
                buildKey).source();
    }

    Emission emitWithPlan(
            NativeRegistrationPlan registrationPlan,
            MethodTableHidingPlan hidingPlan,
            RuntimeLoaderPlan runtimeLoaderPlan,
            NativeTextBuildKey buildKey) {
        validatePlan(registrationPlan, hidingPlan);
        Objects.requireNonNull(runtimeLoaderPlan, "runtimeLoaderPlan");
        Objects.requireNonNull(buildKey, "buildKey");
        NativeTextCEmitter textEmitter = new NativeTextCEmitter();
        StringBuilder source = new StringBuilder(textEmitter.runtimeSource());
        HostNativeOwnerRegistrationSource ownerEmitter =
                new HostNativeOwnerRegistrationSource();
        HostNativeRegistrationResolverSource resolverEmitter =
                new HostNativeRegistrationResolverSource();
        List<NativeRegistrationTextPlan.Owner> owners;
        if (hidingPlan.changed()) {
            owners = physicalOwnerOrder(
                    NativeRegistrationTextPlan.hidden(hidingPlan, buildKey));
        } else {
            owners = physicalOwnerOrder(
                    NativeRegistrationTextPlan.ordinary(registrationPlan, buildKey));
        }
        NativeRegistrationControlTopologyPlan topologyPlan =
                new NativeRegistrationControlTopologyPlanner().plan(
                        owners,
                        buildKey);
        HostNativeRegistrationFailureLeafSource failureLeafSource =
                new HostNativeRegistrationFailureLeafSource();
        HostNativeRegistrationFailureLeafSource.Plan failureLeaves =
                failureLeafSource.plan(
                        buildKey,
                        topologyPlan.failureSymbols());
        source.append(failureLeafSource.emit(failureLeaves));
        for (NativeRegistrationControlTopologyPlan.Owner owner
                : topologyPlan.owners()) {
            source.append(ownerEmitter.emit(owner, failureLeaves));
        }
        NativeRegistrationResolverPlan resolverPlan = owners.isEmpty()
                ? null
                : NativeRegistrationResolverPlan.create(
                        runtimeLoaderPlan,
                        buildKey,
                        owners.size());
        if (resolverPlan != null) {
            source.append(resolverEmitter.ciphertextDeclaration(resolverPlan));
        }
        source.append(new HostNativeRegistrationChunkSource().emit(
                topologyPlan));
        appendRootRegistration(
                source,
                topologyPlan,
                failureLeaves,
                resolverPlan,
                resolverEmitter);
        Emission emission = new Emission(
                source.toString(),
                topologyPlan);
        new NativeRegistrationControlSourceVerifier().verify(
                emission.source(),
                topologyPlan);
        return emission;
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
            NativeRegistrationControlTopologyPlan topologyPlan,
            HostNativeRegistrationFailureLeafSource.Plan failureLeaves,
            NativeRegistrationResolverPlan resolverPlan,
            HostNativeRegistrationResolverSource resolverEmitter) {
        List<NativeRegistrationControlTopologyPlan.Owner> owners =
                topologyPlan.owners();
        String aggregateSymbol = topologyPlan.aggregateSymbol();
        source.append("static jint ")
                .append(aggregateSymbol)
                .append("(JavaVM* vm) __attribute__((noinline));\n");
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
            resolverEmitter.appendOpen(source, resolverPlan);
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
        if (!topologyPlan.chunks().isEmpty()) {
            source.append("    if (")
                    .append(topologyPlan.chunks().get(0).symbol())
                    .append("(env, &resolver, registered_owners, &registered_count) != JNI_OK) {\n")
                    .append("        goto rollback;\n")
                    .append("    }\n");
        }
        if (!owners.isEmpty()) {
            source.append("    while (registered_count != 0u) {\n")
                    .append("        registered_count--;\n")
                    .append("        (*env)->DeleteLocalRef(env, registered_owners[registered_count]);\n")
                    .append("        registered_owners[registered_count] = NULL;\n")
                    .append("    }\n");
            resolverEmitter.appendClose(source, "    ");
            source.append("    return JNI_VERSION_1_8;\n")
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
            resolverEmitter.appendClose(source, "    ");
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

    record Emission(
            String source,
            NativeRegistrationControlTopologyPlan topologyPlan) {
        Emission {
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(topologyPlan, "topologyPlan");
        }
    }
}
