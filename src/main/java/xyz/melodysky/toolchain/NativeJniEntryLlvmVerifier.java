package xyz.melodysky.toolchain;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import xyz.melodysky.backend.llvm.model.LlvmFunction;
import xyz.melodysky.backend.llvm.model.LlvmLinkage;
import xyz.melodysky.backend.llvm.model.LlvmParameter;
import xyz.melodysky.backend.llvm.model.LlvmType;
import xyz.melodysky.backend.llvm.model.LlvmVisibility;

/** Verifies every final LLVM proxy/body/topology binding before Zig. */
public final class NativeJniEntryLlvmVerifier {
    private final NativeJniProxyFunctionVerifier functionVerifier =
            new NativeJniProxyFunctionVerifier();

    public List<String> validate(
            NativeImplementationPlan implementationPlan,
            NativeLlvmCompilation compilation) {
        Objects.requireNonNull(implementationPlan, "implementationPlan");
        Objects.requireNonNull(compilation, "compilation");
        NativeLlvmSymbolIndex symbols = new NativeLlvmSymbolIndex(compilation);
        ArrayList<String> issues = new ArrayList<>();
        implementationPlan.jniEntryPlans().entrySet().stream()
                .filter(entry -> entry.getValue().llvmJniProxy())
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> verifyEntry(
                        implementationPlan,
                        compilation,
                        symbols,
                        entry.getKey(),
                        entry.getValue(),
                        issues));
        return issues.stream().distinct().sorted().toList();
    }

    private void verifyEntry(
            NativeImplementationPlan plan,
            NativeLlvmCompilation compilation,
            NativeLlvmSymbolIndex symbols,
            String methodKey,
            NativeJniEntryPlan entryPlan,
            List<String> issues) {
        NativeMethodImplementation implementation = plan
                .implementationFor(methodKey)
                .orElse(null);
        if (implementation == null) {
            add(issues, methodKey, "LLVM_JNI_PROXY_IMPLEMENTATION_MISSING");
            return;
        }
        if (!entryPlan.physicalLlvmAbi().isPhysicalJniEntry()) {
            add(issues, methodKey, "LLVM_JNI_PROXY_ABI_PURPOSE_MISMATCH");
        }
        String proxySymbol = entryPlan.functionSymbol();
        String bodySymbol = entryPlan.semanticBodySymbol().orElseThrow();
        NativeLlvmSymbolIndex.FunctionLocation proxy = uniqueDefinition(
                symbols,
                proxySymbol,
                methodKey,
                "LLVM_JNI_PROXY_DEFINITION",
                issues);
        NativeLlvmSymbolIndex.FunctionLocation body = uniqueDefinition(
                symbols,
                bodySymbol,
                methodKey,
                "LLVM_JNI_PROXY_SEMANTIC_BODY_DEFINITION",
                issues);
        if (proxy == null || body == null) {
            return;
        }
        rejectDeclaration(
                symbols,
                proxySymbol,
                methodKey,
                "LLVM_JNI_PROXY_DECLARATION_RESIDUAL",
                issues);
        rejectDeclaration(
                symbols,
                bodySymbol,
                methodKey,
                "LLVM_JNI_PROXY_SEMANTIC_BODY_DECLARATION_RESIDUAL",
                issues);
        verifyOwnership(
                implementation,
                compilation,
                methodKey,
                proxy,
                body,
                issues);
        functionVerifier.verifySurface(
                methodKey,
                proxy.function(),
                LlvmLinkage.EXTERNAL,
                LlvmVisibility.HIDDEN,
                "LLVM_JNI_PROXY",
                issues);
        functionVerifier.verifySurface(
                methodKey,
                body.function(),
                LlvmLinkage.EXTERNAL,
                LlvmVisibility.HIDDEN,
                "LLVM_JNI_PROXY_SEMANTIC_BODY",
                issues);

        boolean staticMethod = implementation.decision()
                .method()
                .accessFlags()
                .isStatic();
        Optional<NativeJniProxyFunctionVerifier.Signature> semantic =
                functionVerifier.semanticSignature(
                implementation.decision().method().descriptor(),
                staticMethod);
        if (semantic.isEmpty()) {
            add(issues, methodKey, "LLVM_JNI_PROXY_DESCRIPTOR_UNSUPPORTED");
            return;
        }
        NativeJniProxyFunctionVerifier.Signature bodySignature =
                semantic.orElseThrow();
        ArrayList<LlvmType> physicalParameters = new ArrayList<>();
        physicalParameters.add(LlvmType.PTR);
        if (staticMethod) {
            physicalParameters.add(LlvmType.PTR);
        }
        physicalParameters.addAll(bodySignature.parameterTypes());
        functionVerifier.verifySignature(
                methodKey,
                proxy.function(),
                new NativeJniProxyFunctionVerifier.Signature(
                        bodySignature.returnType(),
                        physicalParameters),
                "LLVM_JNI_PROXY",
                issues);
        functionVerifier.verifySignature(
                methodKey,
                body.function(),
                bodySignature,
                "LLVM_JNI_PROXY_SEMANTIC_BODY",
                issues);

        int semanticOffset = staticMethod ? 2 : 1;
        List<LlvmParameter> canonical = proxy.function().parameters()
                .subList(semanticOffset, proxy.function().parameters().size());
        NativeJniEntryTopology topology = entryPlan.topology().orElseThrow();
        if (canonical.size() != topology.parameterCount()) {
            add(issues, methodKey, "LLVM_JNI_PROXY_TOPOLOGY_ARITY_MISMATCH");
            return;
        }
        Map<String, LlvmFunction> bridges = verifyBridges(
                symbols,
                methodKey,
                topology,
                bodySignature,
                canonical,
                proxy.module(),
                issues);
        if (bridges.size() != topology.bridgeSymbols().size()) {
            return;
        }
        issues.addAll(new NativeJniProxyTopologyVerifier().validate(
                methodKey,
                topology,
                proxy.function(),
                body.function(),
                canonical,
                bridges,
                symbols));
        Set<String> addressSensitiveSymbols = java.util.stream.Stream.concat(
                        java.util.stream.Stream.of(proxySymbol, bodySymbol),
                        topology.bridgeSymbols().stream())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (symbols.hasGlobalAddressReference(
                proxy.module(),
                addressSensitiveSymbols)) {
            add(issues, methodKey, "LLVM_JNI_PROXY_GLOBAL_ADDRESS_SURFACE");
        }
    }

    private Map<String, LlvmFunction> verifyBridges(
            NativeLlvmSymbolIndex symbols,
            String methodKey,
            NativeJniEntryTopology topology,
            NativeJniProxyFunctionVerifier.Signature semantic,
            List<LlvmParameter> canonical,
            NativeLlvmModuleCompilation expectedModule,
            List<String> issues) {
        LinkedHashMap<String, LlvmFunction> result = new LinkedHashMap<>();
        for (int index = 0; index < topology.bridgeSymbols().size(); index++) {
            String symbol = topology.bridgeSymbols().get(index);
            NativeLlvmSymbolIndex.FunctionLocation bridge = uniqueDefinition(
                    symbols,
                    symbol,
                    methodKey,
                    "LLVM_JNI_PROXY_BRIDGE_DEFINITION",
                    issues);
            if (bridge == null) {
                continue;
            }
            rejectDeclaration(
                    symbols,
                    symbol,
                    methodKey,
                    "LLVM_JNI_PROXY_BRIDGE_DECLARATION_RESIDUAL",
                    issues);
            if (bridge.module() != expectedModule) {
                add(issues, methodKey, "LLVM_JNI_PROXY_BRIDGE_MODULE_MISMATCH");
            }
            functionVerifier.verifySurface(
                    methodKey,
                    bridge.function(),
                    LlvmLinkage.INTERNAL,
                    LlvmVisibility.DEFAULT,
                    "LLVM_JNI_PROXY_BRIDGE",
                    issues);
            List<LlvmParameter> expectedParameters = topology.parameterOrders()
                    .get(index)
                    .stream()
                    .map(canonical::get)
                    .toList();
            functionVerifier.verifySignature(
                    methodKey,
                    bridge.function(),
                    new NativeJniProxyFunctionVerifier.Signature(
                            semantic.returnType(),
                            expectedParameters.stream()
                                    .map(LlvmParameter::type)
                                    .toList()),
                    "LLVM_JNI_PROXY_BRIDGE",
                    issues);
            if (!bridge.function().parameters().equals(expectedParameters)) {
                add(issues, methodKey, "LLVM_JNI_PROXY_BRIDGE_PARAMETER_ORDER_MISMATCH");
            }
            result.put(symbol, bridge.function());
        }
        return Map.copyOf(result);
    }

    private void verifyOwnership(
            NativeMethodImplementation implementation,
            NativeLlvmCompilation compilation,
            String methodKey,
            NativeLlvmSymbolIndex.FunctionLocation proxy,
            NativeLlvmSymbolIndex.FunctionLocation body,
            List<String> issues) {
        String expectedOwner = implementation.decision().method().owner();
        if (!proxy.owner().equals(expectedOwner)
                || !body.owner().equals(expectedOwner)
                || proxy.module() != body.module()) {
            add(issues, methodKey, "LLVM_JNI_PROXY_OWNER_OR_MODULE_MISMATCH");
        }
        long count = compilation.modules().stream()
                .flatMap(module -> module.registeredMethods().stream())
                .filter(method -> method.methodKey().equals(methodKey))
                .count();
        if (count != 1 || proxy.module().registeredMethods().stream()
                .noneMatch(method -> method.methodKey().equals(methodKey))) {
            add(issues, methodKey, "LLVM_JNI_PROXY_REGISTRATION_MODEL_MISMATCH");
        }
    }

    private NativeLlvmSymbolIndex.FunctionLocation uniqueDefinition(
            NativeLlvmSymbolIndex symbols,
            String symbol,
            String methodKey,
            String reasonPrefix,
            List<String> issues) {
        List<NativeLlvmSymbolIndex.FunctionLocation> definitions =
                symbols.functions(symbol);
        if (definitions.size() != 1) {
            add(issues, methodKey, reasonPrefix
                    + (definitions.isEmpty() ? "_MISSING" : "_DUPLICATE"));
            return null;
        }
        return definitions.get(0);
    }

    private void rejectDeclaration(
            NativeLlvmSymbolIndex symbols,
            String symbol,
            String methodKey,
            String reasonCode,
            List<String> issues) {
        if (!symbols.declarations(symbol).isEmpty()) {
            add(issues, methodKey, reasonCode);
        }
    }

    private void add(List<String> issues, String methodKey, String reasonCode) {
        issues.add(methodKey + ":" + reasonCode);
    }

}
