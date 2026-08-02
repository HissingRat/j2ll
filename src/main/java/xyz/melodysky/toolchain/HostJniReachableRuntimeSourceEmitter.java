package xyz.melodysky.toolchain;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import xyz.melodysky.runtime.RuntimeTokenMapper;

/** Emits only runtime source families required by the final LLVM model. */
final class HostJniReachableRuntimeSourceEmitter {
    void append(
            HostJniGeneratedCFragmentEmitter fragments,
            List<HostJniCSourceGenerator.Binding> bindings,
            RuntimeTokenMapper runtimeTokens,
            RuntimeHelperReachabilityPlan reachability) {
        Set<HostJniRuntimeSourceFamily> emissionFamilies =
                emissionFamilies(bindings, reachability);
        append(
                fragments,
                emissionFamilies,
                HostJniRuntimeSourceFamily.ALLOCATION,
                "allocation",
                fragment -> HostJniAllocationRuntimeSource.append(
                        fragment,
                        bindings,
                        runtimeTokens));
        append(
                fragments,
                emissionFamilies,
                HostJniRuntimeSourceFamily.CLASS_INIT,
                "jvm-class-init",
                fragment -> fragment.append(
                        HostJniJvmSemanticsSources
                                .classInitHelperSource()));
        append(
                fragments,
                emissionFamilies,
                HostJniRuntimeSourceFamily.ARITHMETIC,
                "jvm-arithmetic",
                fragment -> fragment.append(
                        HostJniJvmSemanticsSources
                                .arithmeticExceptionHelperSource()));
        append(
                fragments,
                emissionFamilies,
                HostJniRuntimeSourceFamily.NUMERIC,
                "jvm-numeric",
                fragment -> fragment.append(
                        HostJniJvmSemanticsSources
                                .jvmNumericHelperSource()));
        append(
                fragments,
                emissionFamilies,
                HostJniRuntimeSourceFamily.EXCEPTION,
                "jvm-exception",
                fragment -> fragment.append(
                        HostJniJvmSemanticsSources
                                .exceptionHelperSource()));
        append(
                fragments,
                emissionFamilies,
                HostJniRuntimeSourceFamily.MATH,
                "jvm-math",
                fragment -> fragment.append(
                        HostJniJvmSemanticsSources.mathHelperSource()));
        append(
                fragments,
                emissionFamilies,
                HostJniRuntimeSourceFamily.JDK_OBJECT,
                "jdk-object",
                fragment -> fragment.append(
                        HostJniJdkObjectRuntimeSource
                                .jdkObjectHelperSource()));
        append(
                fragments,
                emissionFamilies,
                HostJniRuntimeSourceFamily.PURE_NATIVE_JDK,
                "pure-native-jdk",
                fragment -> fragment.append(
                        HostJniPureNativeJdkRuntimeSource
                                .helperSource()));
        append(
                fragments,
                emissionFamilies,
                HostJniRuntimeSourceFamily.THREAD,
                "jvm-thread",
                fragment -> fragment.append(
                        HostJniThreadRuntimeSource.threadHelperSource()));
        append(
                fragments,
                emissionFamilies,
                HostJniRuntimeSourceFamily.MONITOR,
                "jvm-monitor",
                fragment -> fragment.append(
                        HostJniJvmSemanticsSources.monitorHelperSource()));
        append(
                fragments,
                emissionFamilies,
                HostJniRuntimeSourceFamily.ARRAY,
                "jvm-array",
                fragment -> fragment.append(
                        HostJniArrayRuntimeSource.arrayHelperSource()));
        append(
                fragments,
                emissionFamilies,
                HostJniRuntimeSourceFamily.TYPE,
                "jvm-type",
                fragment -> fragment.append(
                        HostJniTypeAndStringRuntimeSources
                                .typeHelperSource()));
        append(
                fragments,
                emissionFamilies,
                HostJniRuntimeSourceFamily.STRING,
                "jvm-string",
                fragment -> fragment.append(
                        HostJniTypeAndStringRuntimeSources
                                .stringHelperSource()));
        append(
                fragments,
                emissionFamilies,
                HostJniRuntimeSourceFamily.LAMBDA,
                "lambda",
                fragment -> HostJniLambdaRuntimeSource.append(
                        fragment,
                        bindings,
                        runtimeTokens));
        append(
                fragments,
                emissionFamilies,
                HostJniRuntimeSourceFamily.VAR_HANDLE,
                "varhandle",
                fragment -> fragment.append(
                        HostJniVarHandleRuntimeSource
                                .varHandleHelperSource()));
        append(
                fragments,
                emissionFamilies,
                HostJniRuntimeSourceFamily.REFLECTION,
                "reflection",
                fragment -> HostJniReflectionRuntimeSource.append(
                        fragment,
                        bindings,
                        runtimeTokens));
        append(
                fragments,
                emissionFamilies,
                HostJniRuntimeSourceFamily.DISPATCH,
                "dispatch",
                fragment -> HostJniDispatchRuntimeSource.append(
                        fragment,
                        bindings,
                        runtimeTokens));
    }

    Set<HostJniRuntimeSourceFamily> emissionFamilies(
            List<HostJniCSourceGenerator.Binding> bindings,
            RuntimeHelperReachabilityPlan reachability) {
        EnumSet<HostJniRuntimeSourceFamily> families =
                EnumSet.noneOf(HostJniRuntimeSourceFamily.class);
        families.addAll(reachability.families());
        /*
         * Binding-driven emitters may still contain entries removed from the
         * final LLVM root set. Close over what the selected emitter will
         * physically write so pruning can never create an unresolved helper.
         */
        if (families.contains(HostJniRuntimeSourceFamily.ALLOCATION)
                && HostJniAllocationRuntimeSource
                        .emitsClassForNameSupport(bindings)) {
            families.add(HostJniRuntimeSourceFamily.REFLECTION);
        }
        if (families.contains(HostJniRuntimeSourceFamily.REFLECTION)
                && HostJniReflectionRuntimeSource
                        .emitsVarHandleDependentSupport(bindings)) {
            families.add(HostJniRuntimeSourceFamily.VAR_HANDLE);
        }
        return Set.copyOf(families);
    }

    private void append(
            HostJniGeneratedCFragmentEmitter fragments,
            Set<HostJniRuntimeSourceFamily> emissionFamilies,
            HostJniRuntimeSourceFamily family,
            String scope,
            java.util.function.Consumer<StringBuilder> emitter) {
        if (!emissionFamilies.contains(family)) {
            return;
        }
        fragments.append(scope, emitter);
    }
}
