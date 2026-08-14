package xyz.melodysky.toolchain;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import xyz.melodysky.backend.llvm.model.LlvmDirectCallRef;
import xyz.melodysky.backend.llvm.model.LlvmFunction;

/** Read-only symbol/call index over the authoritative final LLVM modules. */
final class NativeLlvmSymbolIndex {
    private final Map<String, List<FunctionLocation>> functions;
    private final Map<String, List<String>> declarations;
    private final Map<String, List<String>> callers;

    NativeLlvmSymbolIndex(NativeLlvmCompilation compilation) {
        LinkedHashMap<String, ArrayList<FunctionLocation>> mutableFunctions =
                new LinkedHashMap<>();
        LinkedHashMap<String, ArrayList<String>> mutableDeclarations =
                new LinkedHashMap<>();
        LinkedHashMap<String, ArrayList<String>> mutableCallers =
                new LinkedHashMap<>();
        for (NativeLlvmModuleCompilation module : compilation.modules()) {
            for (LlvmFunction function : module.module().functions()) {
                mutableFunctions
                        .computeIfAbsent(function.name(), ignored -> new ArrayList<>())
                        .add(new FunctionLocation(module.owner(), module, function));
                function.blocks().stream()
                        .flatMap(block -> block.instructions().stream())
                        .flatMap(instruction -> instruction.directCall().stream())
                        .map(LlvmDirectCallRef::target)
                        .forEach(target -> mutableCallers
                                .computeIfAbsent(target, ignored -> new ArrayList<>())
                                .add(function.name()));
            }
            module.module().declarations().forEach(declaration ->
                    mutableDeclarations
                            .computeIfAbsent(declaration.name(), ignored -> new ArrayList<>())
                            .add(module.owner()));
        }
        functions = immutableLists(mutableFunctions);
        declarations = immutableLists(mutableDeclarations);
        callers = immutableLists(mutableCallers);
    }

    List<FunctionLocation> functions(String symbol) {
        return functions.getOrDefault(symbol, List.of());
    }

    List<String> declarations(String symbol) {
        return declarations.getOrDefault(symbol, List.of());
    }

    List<String> callers(String symbol) {
        return callers.getOrDefault(symbol, List.of());
    }

    boolean hasGlobalAddressReference(
            NativeLlvmModuleCompilation module,
            Set<String> symbols) {
        return module.module().globals().stream().anyMatch(global ->
                symbols.stream().anyMatch(symbol -> Pattern.compile(
                                "@" + Pattern.quote(symbol) + "(?![A-Za-z0-9_])")
                        .matcher(global.definition())
                        .find()));
    }

    private <T> Map<String, List<T>> immutableLists(
            Map<String, ? extends List<T>> mutable) {
        LinkedHashMap<String, List<T>> result = new LinkedHashMap<>();
        mutable.forEach((symbol, values) ->
                result.put(symbol, List.copyOf(values)));
        return Map.copyOf(result);
    }

    record FunctionLocation(
            String owner,
            NativeLlvmModuleCompilation module,
            LlvmFunction function) {}
}
