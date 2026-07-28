package xyz.melodysky.toolchain;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import xyz.melodysky.runtime.RuntimeTokenMapper;
import xyz.melodysky.toolchain.nativetext.GeneratedCFragmentTextObfuscator;
import xyz.melodysky.toolchain.nativetext.NativeTextBuildKey;

/** Emits only runtime source families required by the final LLVM model. */
final class HostJniReachableRuntimeSourceEmitter {
    void append(
            StringBuilder builder,
            GeneratedCFragmentTextObfuscator textObfuscator,
            NativeTextBuildKey buildKey,
            List<HostJniCSourceGenerator.Binding> bindings,
            RuntimeTokenMapper runtimeTokens,
            RuntimeHelperReachabilityPlan reachability) {
        Set<HostJniRuntimeSourceFamily> emissionFamilies =
                emissionFamilies(bindings, reachability);
        append(
                builder,
                textObfuscator,
                buildKey,
                emissionFamilies,
                HostJniRuntimeSourceFamily.ALLOCATION,
                "allocation",
                fragment -> HostJniAllocationRuntimeSource.append(
                        fragment,
                        bindings,
                        runtimeTokens));
        append(
                builder,
                textObfuscator,
                buildKey,
                emissionFamilies,
                HostJniRuntimeSourceFamily.CLASS_INIT,
                "jvm-class-init",
                fragment -> fragment.append(
                        HostJniJvmSemanticsSources
                                .classInitHelperSource()));
        append(
                builder,
                textObfuscator,
                buildKey,
                emissionFamilies,
                HostJniRuntimeSourceFamily.ARITHMETIC,
                "jvm-arithmetic",
                fragment -> fragment.append(
                        HostJniJvmSemanticsSources
                                .arithmeticExceptionHelperSource()));
        append(
                builder,
                textObfuscator,
                buildKey,
                emissionFamilies,
                HostJniRuntimeSourceFamily.NUMERIC,
                "jvm-numeric",
                fragment -> fragment.append(
                        HostJniJvmSemanticsSources
                                .jvmNumericHelperSource()));
        append(
                builder,
                textObfuscator,
                buildKey,
                emissionFamilies,
                HostJniRuntimeSourceFamily.EXCEPTION,
                "jvm-exception",
                fragment -> fragment.append(
                        HostJniJvmSemanticsSources
                                .exceptionHelperSource()));
        append(
                builder,
                textObfuscator,
                buildKey,
                emissionFamilies,
                HostJniRuntimeSourceFamily.MATH,
                "jvm-math",
                fragment -> fragment.append(
                        HostJniJvmSemanticsSources.mathHelperSource()));
        append(
                builder,
                textObfuscator,
                buildKey,
                emissionFamilies,
                HostJniRuntimeSourceFamily.JDK_OBJECT,
                "jdk-object",
                fragment -> fragment.append(
                        HostJniJdkObjectRuntimeSource
                                .jdkObjectHelperSource()));
        append(
                builder,
                textObfuscator,
                buildKey,
                emissionFamilies,
                HostJniRuntimeSourceFamily.THREAD,
                "jvm-thread",
                fragment -> fragment.append(
                        HostJniThreadRuntimeSource.threadHelperSource()));
        append(
                builder,
                textObfuscator,
                buildKey,
                emissionFamilies,
                HostJniRuntimeSourceFamily.MONITOR,
                "jvm-monitor",
                fragment -> fragment.append(
                        HostJniJvmSemanticsSources.monitorHelperSource()));
        append(
                builder,
                textObfuscator,
                buildKey,
                emissionFamilies,
                HostJniRuntimeSourceFamily.ARRAY,
                "jvm-array",
                fragment -> fragment.append(
                        HostJniArrayRuntimeSource.arrayHelperSource()));
        append(
                builder,
                textObfuscator,
                buildKey,
                emissionFamilies,
                HostJniRuntimeSourceFamily.TYPE,
                "jvm-type",
                fragment -> fragment.append(
                        HostJniTypeAndStringRuntimeSources
                                .typeHelperSource()));
        append(
                builder,
                textObfuscator,
                buildKey,
                emissionFamilies,
                HostJniRuntimeSourceFamily.STRING,
                "jvm-string",
                fragment -> fragment.append(
                        HostJniTypeAndStringRuntimeSources
                                .stringHelperSource()));
        append(
                builder,
                textObfuscator,
                buildKey,
                emissionFamilies,
                HostJniRuntimeSourceFamily.LAMBDA,
                "lambda",
                fragment -> HostJniLambdaRuntimeSource.append(
                        fragment,
                        bindings,
                        runtimeTokens));
        append(
                builder,
                textObfuscator,
                buildKey,
                emissionFamilies,
                HostJniRuntimeSourceFamily.VAR_HANDLE,
                "varhandle",
                fragment -> fragment.append(
                        HostJniVarHandleRuntimeSource
                                .varHandleHelperSource()));
        append(
                builder,
                textObfuscator,
                buildKey,
                emissionFamilies,
                HostJniRuntimeSourceFamily.REFLECTION,
                "reflection",
                fragment -> HostJniReflectionRuntimeSource.append(
                        fragment,
                        bindings,
                        runtimeTokens));
        append(
                builder,
                textObfuscator,
                buildKey,
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
            StringBuilder builder,
            GeneratedCFragmentTextObfuscator textObfuscator,
            NativeTextBuildKey buildKey,
            Set<HostJniRuntimeSourceFamily> emissionFamilies,
            HostJniRuntimeSourceFamily family,
            String scope,
            Consumer<StringBuilder> emitter) {
        if (!emissionFamilies.contains(family)) {
            return;
        }
        StringBuilder fragment = new StringBuilder();
        emitter.accept(fragment);
        builder.append(textObfuscator.obfuscate(
                buildKey,
                scope,
                fragment.toString()));
    }
}
