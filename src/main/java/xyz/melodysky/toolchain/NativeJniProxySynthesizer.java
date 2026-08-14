package xyz.melodysky.toolchain;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import xyz.melodysky.backend.llvm.model.LlvmFunction;
import xyz.melodysky.backend.llvm.model.LlvmFunctionAttribute;
import xyz.melodysky.backend.llvm.model.LlvmModule;
import xyz.melodysky.backend.llvm.model.LlvmModuleValidator;

/** Injects approved LLVM JNI proxy functions into their owning final module. */
final class NativeJniProxySynthesizer {
    LlvmModule synthesize(
            String owner,
            LlvmModule module,
            NativeImplementationPlan implementationPlan) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(module, "module");
        Objects.requireNonNull(implementationPlan, "implementationPlan");
        List<Map.Entry<String, NativeJniEntryPlan>> entries =
                implementationPlan.jniEntryPlans().entrySet().stream()
                        .filter(entry -> entry.getValue().llvmJniProxy())
                        .filter(entry -> implementationPlan
                                .implementationFor(entry.getKey())
                                .map(implementation -> implementation
                                        .decision()
                                        .method()
                                        .owner()
                                        .equals(owner))
                                .orElse(false))
                        .sorted(Map.Entry.comparingByKey())
                        .toList();
        if (entries.isEmpty()) {
            return module;
        }

        LinkedHashSet<String> occupied = occupiedSymbols(module);
        Set<String> semanticSymbols = entries.stream()
                .map(entry -> entry.getValue().semanticBodySymbol().orElseThrow())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        ArrayList<LlvmFunction> functions = new ArrayList<>();
        module.functions().forEach(function -> functions.add(
                semanticSymbols.contains(function.name())
                        ? withNoInline(function)
                        : function));
        NativeJniProxyFunctionFactory factory =
                new NativeJniProxyFunctionFactory();
        for (Map.Entry<String, NativeJniEntryPlan> entry : entries) {
            String methodKey = entry.getKey();
            NativeJniEntryPlan entryPlan = entry.getValue();
            LlvmFunction body = functions.stream()
                    .filter(function -> function.name().equals(
                            entryPlan.semanticBodySymbol().orElseThrow()))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException(
                            "LLVM JNI proxy semantic body is missing: " + methodKey));
            NativeMethodImplementation implementation = implementationPlan
                    .implementationFor(methodKey)
                    .orElseThrow(() -> new IllegalStateException(
                            "LLVM JNI proxy implementation is missing: " + methodKey));
            NativeJniProxyAbiProjection projection =
                    NativeJniProxyAbiProjection.derive(implementation)
                            .orElseThrow(() -> new IllegalStateException(
                                    "LLVM JNI proxy ABI is not projectable: "
                                            + methodKey));
            NativeJniEntryTopology topology = entryPlan.topology().orElseThrow();
            if (topology.parameterCount()
                            != projection.semanticParameterCount()
                    || body.parameters().size()
                            != projection.semanticParameterCount()) {
                throw new IllegalStateException(
                        "LLVM JNI proxy topology/body arity mismatch: " + methodKey);
            }
            reserve(entryPlan.functionSymbol(), occupied, methodKey);
            topology.bridgeSymbols().forEach(symbol ->
                    reserve(symbol, occupied, methodKey));
            functions.addAll(factory.create(
                    entryPlan,
                    projection,
                    body));
        }
        LlvmModule result = new LlvmModule(
                module.identifier(),
                module.declarations(),
                module.globals(),
                functions);
        List<String> issues = new LlvmModuleValidator().validate(result);
        if (!issues.isEmpty()) {
            throw new IllegalStateException(
                    "LLVM JNI proxy synthesis produced an invalid module: "
                            + String.join(",", issues));
        }
        return result;
    }

    private LinkedHashSet<String> occupiedSymbols(LlvmModule module) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        module.declarations().forEach(value -> result.add(value.name()));
        module.globals().forEach(value -> result.add(value.name()));
        module.functions().forEach(value -> result.add(value.name()));
        return result;
    }

    private LlvmFunction withNoInline(LlvmFunction function) {
        LinkedHashSet<LlvmFunctionAttribute> attributes =
                new LinkedHashSet<>(function.attributes());
        attributes.add(LlvmFunctionAttribute.NOINLINE);
        return function.withAttributes(List.copyOf(attributes));
    }

    private void reserve(String symbol, Set<String> occupied, String methodKey) {
        if (!occupied.add(symbol)) {
            throw new IllegalStateException(
                    "LLVM JNI proxy symbol collision: "
                            + methodKey
                            + " -> "
                            + symbol);
        }
    }
}
