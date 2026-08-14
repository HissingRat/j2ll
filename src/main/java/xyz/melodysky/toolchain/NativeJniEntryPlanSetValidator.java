package xyz.melodysky.toolchain;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import xyz.melodysky.packaging.MethodRewriteStrategy;
import xyz.melodysky.toolchain.localref.NativeLocalReferencePlan;

/** Normalizes and fail-closed validates the final registered-entry plan set. */
final class NativeJniEntryPlanSetValidator {
    Map<String, NativeJniEntryPlan> validate(
            List<NativeMethodImplementation> implementations,
            Map<String, NativeLocalReferencePlan> localReferences,
            Map<String, NativeJniEntryPlan> entryPlans) {
        Map<String, NativeMethodImplementation> byMethod = implementations.stream()
                .collect(java.util.stream.Collectors.toUnmodifiableMap(
                        NativeMethodImplementation::methodKey,
                        implementation -> implementation));
        LinkedHashMap<String, NativeJniEntryPlan> stable = new LinkedHashMap<>();
        entryPlans.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    String methodKey = java.util.Objects.requireNonNull(
                            entry.getKey(),
                            "JNI entry method key");
                    NativeJniEntryPlan plan = java.util.Objects.requireNonNull(
                            entry.getValue(),
                            "JNI entry plan");
                    NativeMethodImplementation implementation = byMethod.get(methodKey);
                    if (implementation == null
                            || implementation.decision().strategy()
                                    == MethodRewriteStrategy.INTERNAL_NATIVE_ONLY) {
                        throw new IllegalArgumentException(
                                "JNI entry plan belongs to a non-registered method: "
                                        + methodKey);
                    }
                    validateEntry(implementation, plan);
                    stable.put(methodKey, plan);
                });
        List<String> registered = implementations.stream()
                .filter(implementation -> implementation.decision().strategy()
                        != MethodRewriteStrategy.INTERNAL_NATIVE_ONLY)
                .map(NativeMethodImplementation::methodKey)
                .toList();
        if (!stable.keySet().containsAll(registered)
                || stable.size() != registered.size()) {
            throw new IllegalArgumentException(
                    "JNI entry plan must cover every registered implementation exactly once");
        }
        validateProxyFacts(implementations, localReferences, stable);
        validateSymbols(implementations, stable);
        return Collections.unmodifiableMap(stable);
    }

    private void validateProxyFacts(
            List<NativeMethodImplementation> implementations,
            Map<String, NativeLocalReferencePlan> localReferences,
            Map<String, NativeJniEntryPlan> entries) {
        Set<String> proxies = entries.entrySet().stream()
                .filter(entry -> entry.getValue().llvmJniProxy())
                .map(Map.Entry::getKey)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        NativeJniEntryCallFacts callFacts = NativeJniEntryCallFacts.analyze(
                implementations,
                Map.of());
        if (proxies.stream().anyMatch(callFacts::targets)) {
            throw new IllegalArgumentException(
                    "LLVM JNI proxy semantic body is referenced by a native caller");
        }
        if (proxies.stream()
                .map(localReferences::get)
                .anyMatch(NativeJniEntryLocalReferenceFacts
                        ::requiresSemanticHandling)) {
            throw new IllegalArgumentException(
                    "LLVM JNI proxy requires local-reference semantic handling");
        }
    }

    private void validateEntry(
            NativeMethodImplementation implementation,
            NativeJniEntryPlan entryPlan) {
        if (!entryPlan.llvmJniProxy()) {
            if (!entryPlan.functionSymbol().equals(
                            implementation.entry().nativeSymbol())
                    || !entryPlan.physicalLlvmAbi().equals(
                            implementation.llvmFunctionAbi())) {
                throw new IllegalArgumentException(
                        "generated-C JNI entry must retain its wrapper symbol and semantic LLVM ABI: "
                                + implementation.methodKey());
            }
            return;
        }
        if (implementation.path() != NativeImplementationPath.LLVM_NATIVE_PATH
                || !implementation.emitsStandaloneLlvmBody()
                || implementation.decision().strategy()
                        != MethodRewriteStrategy.NATIVE_ORIGINAL
                || implementation.llvmFunctionSymbol().isEmpty()
                || !entryPlan.semanticBodySymbol().orElseThrow().equals(
                        implementation.llvmFunctionSymbol().orElseThrow())
                || !entryPlan.semanticLlvmAbi().equals(
                        implementation.llvmFunctionAbi())) {
            throw new IllegalArgumentException(
                    "LLVM JNI proxy must bind an ordinary standalone semantic body: "
                            + implementation.methodKey());
        }
        if (implementation.passesJniEnv()
                || implementation.passesOwnerClass()
                || implementation.decision().method().accessFlags().isSynchronized()
                || !NativeJniEntryDescriptorPolicy.supports(
                        implementation.decision().method().descriptor())
                || NativeJniEntryImplementationFacts.hasRuntimeMetadata(
                        implementation)) {
            throw new IllegalArgumentException(
                    "LLVM JNI proxy is outside the pure-scalar closed set: "
                            + implementation.methodKey());
        }
        boolean staticMethod = implementation.decision()
                .method()
                .accessFlags()
                .isStatic();
        if (!entryPlan.physicalLlvmAbi().passesJniEnv()
                || !entryPlan.physicalLlvmAbi().isPhysicalJniEntry()
                || entryPlan.physicalLlvmAbi().passesOwnerClass()
                        != staticMethod) {
            throw new IllegalArgumentException(
                    "LLVM JNI proxy ABI does not match the JVM invocation ABI: "
                            + implementation.methodKey());
        }
        int arity = new xyz.melodysky.runtime.jni.JniTypeMapper()
                        .parameterDescriptors(
                                implementation.decision().method().descriptor())
                        .size()
                + (staticMethod ? 0 : 1);
        if (entryPlan.topology().orElseThrow().parameterCount() != arity) {
            throw new IllegalArgumentException(
                    "LLVM JNI proxy topology arity does not match its semantic body: "
                            + implementation.methodKey());
        }
        if (!hashOnly(entryPlan.functionSymbol())
                || entryPlan.topology().orElseThrow().bridgeSymbols().stream()
                        .anyMatch(symbol -> !hashOnly(symbol))) {
            throw new IllegalArgumentException(
                    "LLVM JNI proxy and bridge symbols must be hash-only: "
                            + implementation.methodKey());
        }
    }

    private void validateSymbols(
            List<NativeMethodImplementation> implementations,
            Map<String, NativeJniEntryPlan> entries) {
        LinkedHashMap<String, String> occupied = new LinkedHashMap<>();
        implementations.stream()
                .filter(NativeMethodImplementation::emitsStandaloneLlvmBody)
                .forEach(implementation -> implementation.llvmFunctionSymbol()
                        .ifPresent(symbol -> reserve(
                                occupied,
                                symbol,
                                "semantic:" + implementation.methodKey())));
        for (Map.Entry<String, NativeJniEntryPlan> entry : entries.entrySet()) {
            String methodKey = entry.getKey();
            NativeJniEntryPlan plan = entry.getValue();
            if (plan.llvmJniProxy()) {
                reserve(occupied, plan.functionSymbol(), "proxy:" + methodKey);
                plan.topology().orElseThrow().bridgeSymbols().forEach(symbol ->
                        reserve(occupied, symbol, "bridge:" + methodKey));
                NativeMethodImplementation implementation = implementations.stream()
                        .filter(candidate -> candidate.methodKey().equals(methodKey))
                        .findFirst()
                        .orElseThrow();
                if (plan.functionSymbol().equals(
                        implementation.entry().nativeSymbol())) {
                    throw new IllegalArgumentException(
                            "LLVM JNI proxy must replace its logical C wrapper: "
                                    + methodKey);
                }
            } else {
                reserve(occupied, plan.functionSymbol(), "wrapper:" + methodKey);
            }
        }
    }

    private void reserve(
            Map<String, String> occupied,
            String symbol,
            String owner) {
        String previous = occupied.putIfAbsent(symbol, owner);
        if (previous != null) {
            throw new IllegalArgumentException(
                    "final native entry symbol collision: "
                            + symbol
                            + " ("
                            + previous
                            + ","
                            + owner
                            + ")");
        }
    }

    private boolean hashOnly(String symbol) {
        return symbol.matches("[a-p]{32}");
    }
}
