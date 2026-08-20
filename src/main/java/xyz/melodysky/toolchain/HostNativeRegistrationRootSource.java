package xyz.melodysky.toolchain;

import java.util.List;
import java.util.Objects;

/** Emits the sole aggregate activation and its bounded JNI entry routes. */
final class HostNativeRegistrationRootSource {
    String emit(
            NativeRegistrationControlTopologyPlan topologyPlan,
            HostNativeRegistrationFailureLeafSource.Plan failureLeaves,
            NativeRegistrationResolverPlan resolverPlan,
            HostNativeRegistrationResolverSource resolverEmitter) {
        Objects.requireNonNull(topologyPlan, "topologyPlan");
        Objects.requireNonNull(failureLeaves, "failureLeaves");
        Objects.requireNonNull(resolverEmitter, "resolverEmitter");
        StringBuilder source = new StringBuilder();
        appendAggregate(
                source,
                topologyPlan,
                failureLeaves,
                resolverPlan,
                resolverEmitter);
        source.append(new HostNativeRegistrationRouteSource().emit(
                topologyPlan.routePlan(),
                topologyPlan.aggregateSymbol()));
        appendJniOnLoad(source, topologyPlan);
        return source.toString();
    }

    private void appendAggregate(
            StringBuilder source,
            NativeRegistrationControlTopologyPlan topologyPlan,
            HostNativeRegistrationFailureLeafSource.Plan failureLeaves,
            NativeRegistrationResolverPlan resolverPlan,
            HostNativeRegistrationResolverSource resolverEmitter) {
        List<NativeRegistrationControlTopologyPlan.Owner> owners =
                topologyPlan.owners();
        String declaration = "static jint "
                + topologyPlan.aggregateSymbol()
                + "(JavaVM* vm)";
        source.append(NativeRegistrationControlCFunctionPolicy.prototype(
                        declaration))
                .append('\n')
                .append(NativeRegistrationControlCFunctionPolicy
                        .definitionHeader(declaration))
                .append('\n')
                .append("    JNIEnv* env = NULL;\n")
                .append("    if ((*vm)->GetEnv(vm, (void**)&env, JNI_VERSION_1_8) != JNI_OK) {\n")
                .append("        return JNI_ERR;\n")
                .append("    }\n");
        if (owners.isEmpty()) {
            source.append("    return JNI_VERSION_1_8;\n");
        } else {
            resolverEmitter.appendOpen(source, resolverPlan);
            appendRegistrationState(source, owners.size());
            source.append("    if (")
                    .append(topologyPlan.chunks().get(0).symbol())
                    .append("(env, &resolver, registered_owners, &registered_count) != JNI_OK) {\n")
                    .append("        goto rollback;\n")
                    .append("    }\n")
                    .append("    while (registered_count != 0u) {\n")
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
            appendRollbackTail(source, failureLeaves);
        }
        source.append("}\n\n");
    }

    private void appendRegistrationState(
            StringBuilder source,
            int ownerCount) {
        source.append("    jthrowable failure_exception = NULL;\n")
                .append("    jthrowable rollback_exception = NULL;\n")
                .append("    jthrowable observed_exception = NULL;\n")
                .append("    jclass rollback_owner = NULL;\n")
                .append("    jboolean rollback_failed = JNI_FALSE;\n")
                .append("    jint unregister_status = JNI_ERR;\n")
                .append("    jint throw_status = JNI_ERR;\n")
                .append("    size_t registered_count = 0u;\n")
                .append("    jclass registered_owners[")
                .append(ownerCount)
                .append("] = {NULL};\n");
    }

    private void appendRollbackTail(
            StringBuilder source,
            HostNativeRegistrationFailureLeafSource.Plan failureLeaves) {
        source.append("    if (rollback_failed) {\n")
                .append("        if (failure_exception != NULL) {\n")
                .append("            (*env)->DeleteLocalRef(env, failure_exception);\n")
                .append("            failure_exception = NULL;\n")
                .append("        }\n")
                .append("        if (rollback_exception != NULL) {\n")
                .append("            (*env)->DeleteLocalRef(env, rollback_exception);\n")
                .append("            rollback_exception = NULL;\n")
                .append("        }\n")
                .append("        ")
                .append(failureLeaves.aggregateRollback().symbol())
                .append("(env);\n")
                .append("        return JNI_ERR;\n")
                .append("    }\n")
                .append("    if (failure_exception != NULL) {\n")
                .append("        throw_status = (*env)->Throw(env, failure_exception);\n")
                .append("        (*env)->DeleteLocalRef(env, failure_exception);\n")
                .append("        failure_exception = NULL;\n")
                .append("        if (throw_status != JNI_OK || !(*env)->ExceptionCheck(env)) {\n")
                .append("            ")
                .append(failureLeaves.aggregateExceptionRestore().symbol())
                .append("(env);\n")
                .append("            return JNI_ERR;\n")
                .append("        }\n")
                .append("    }\n")
                .append("    return JNI_ERR;\n");
    }

    private void appendJniOnLoad(
            StringBuilder source,
            NativeRegistrationControlTopologyPlan topologyPlan) {
        String declaration =
                "JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved)";
        source.append(NativeRegistrationControlCFunctionPolicy
                        .prototype(declaration))
                .append('\n')
                .append(NativeRegistrationControlCFunctionPolicy
                        .definitionHeader(declaration))
                .append('\n');
        NativeRegistrationControlRoutePlan routePlan =
                topologyPlan.routePlan();
        if (!routePlan.enabled()) {
            source.append("    (void)reserved;\n")
                    .append("    volatile jint result = ")
                    .append(topologyPlan.aggregateSymbol())
                    .append("(vm);\n")
                    .append("    return result;\n")
                    .append("}\n");
            return;
        }
        source.append("    volatile uintptr_t guard = (uintptr_t)(void*)&guard\n")
                .append("            ^ (uintptr_t)(void*)vm\n")
                .append("            ^ (uintptr_t)reserved\n")
                .append("            ^ ")
                .append(NativeRegistrationPostCallCSource.unsignedLong(
                        routePlan.rootGuardSalt()))
                .append(";\n")
                .append("    volatile uintptr_t witness = guard ^ ")
                .append(NativeRegistrationPostCallCSource.unsignedLong(
                        routePlan.rootSelectorSalt()))
                .append(";\n")
                .append("    volatile jint result = JNI_ERR;\n")
                .append("    if ((((witness >> ")
                .append(routePlan.rootSelectorShift())
                .append("u) ^ guard) & (uintptr_t)1u) == (uintptr_t)0u) {\n")
                .append("        result = ")
                .append(HostNativeRegistrationRouteSource.routeCall(
                        routePlan.route(0)))
                .append(";\n")
                .append("    } else {\n")
                .append("        result = ")
                .append(HostNativeRegistrationRouteSource.routeCall(
                        routePlan.route(1)))
                .append(";\n")
                .append("    }\n")
                .append("    guard += (uintptr_t)(uint32_t)result ^ ")
                .append(NativeRegistrationPostCallCSource.unsignedLong(
                        routePlan.rootPostCallSalt()))
                .append(";\n")
                .append("    witness ^= guard + (witness >> 11u);\n")
                .append("    (void)guard;\n")
                .append("    (void)witness;\n")
                .append("    return result;\n")
                .append("}\n");
    }
}
