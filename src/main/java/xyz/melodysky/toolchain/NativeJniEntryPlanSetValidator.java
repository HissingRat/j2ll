package xyz.melodysky.toolchain;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
                    validateEntry(
                            implementation,
                            localReferences.get(methodKey),
                            plan);
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
        validateSymbols(implementations, stable);
        return Collections.unmodifiableMap(stable);
    }

    private void validateEntry(
            NativeMethodImplementation implementation,
            NativeLocalReferencePlan localReferences,
            NativeJniEntryPlan entryPlan) {
        if (!entryPlan.llvmJniProxy()) {
            if (!entryPlan.functionSymbol().equals(
                            implementation.entry().nativeSymbol())
                    || !entryPlan.physicalLlvmAbi().equals(
                            implementation.llvmFunctionAbi())
                    || !entryPlan.semanticLlvmAbi().equals(
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
        boolean staticMethod = implementation.decision()
                .method()
                .accessFlags()
                .isStatic();
        if ((!staticMethod && implementation.passesOwnerClass())
                || implementation.decision().method().accessFlags().isSynchronized()
                || !NativeJniEntryDescriptorPolicy.supports(
                        implementation.decision().method().descriptor())
                || implementation.initializerPlan().isPresent()) {
            throw new IllegalArgumentException(
                    "LLVM JNI proxy is outside the projectable ordinary-method set: "
                            + implementation.methodKey());
        }
        NativeJniProxyAbiProjection projection =
                NativeJniProxyAbiProjection.derive(implementation)
                        .orElseThrow(() -> new IllegalArgumentException(
                                "LLVM JNI proxy ABI cannot be projected: "
                                        + implementation.methodKey()));
        if (!entryPlan.physicalLlvmAbi().passesJniEnv()
                || !entryPlan.physicalLlvmAbi().isPhysicalJniEntry()
                || entryPlan.physicalLlvmAbi().passesOwnerClass()
                        != staticMethod) {
            throw new IllegalArgumentException(
                    "LLVM JNI proxy ABI does not match the JVM invocation ABI: "
                            + implementation.methodKey());
        }
        NativeJniEntryTopology topology = entryPlan.topology().orElseThrow();
        if (topology.parameterCount()
                != projection.semanticParameterCount()) {
            throw new IllegalArgumentException(
                    "LLVM JNI proxy topology arity does not match its semantic body: "
                            + implementation.methodKey());
        }
        if (NativeJniEntrySemanticSurface.requiresBranchedTopology(
                        implementation,
                        implementation.implementationIrMethod().orElse(null),
                        localReferences)
                && !topology.shape().branched()) {
            throw new IllegalArgumentException(
                    "LLVM JNI semantic surface requires a branched proxy topology: "
                            + implementation.methodKey());
        }
        if (!hashOnly(entryPlan.functionSymbol())
                || topology.bridgeSymbols().stream()
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
