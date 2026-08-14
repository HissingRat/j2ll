package xyz.melodysky.toolchain;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import xyz.melodysky.backend.llvm.model.LlvmDirectCallRef;
import xyz.melodysky.backend.llvm.model.LlvmFunction;
import xyz.melodysky.backend.llvm.model.LlvmGlobal;
import xyz.melodysky.backend.llvm.model.LlvmModule;

/** Read-only symbol/call index over the authoritative final LLVM modules. */
final class NativeLlvmSymbolIndex {
    private static final NativeLlvmGlobalReferenceLexer GLOBAL_REFERENCE_LEXER =
            new NativeLlvmGlobalReferenceLexer();
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
                referencedSymbols(function).forEach(target -> mutableCallers
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

    List<GlobalAddressReference> globalAddressReferences(
            NativeLlvmModuleCompilation module,
            Set<String> symbols) {
        ArrayList<GlobalAddressReference> references = new ArrayList<>();
        for (LlvmGlobal global : module.module().globals()) {
            List<String> matched = GLOBAL_REFERENCE_LEXER
                    .symbolReferences(global.definition())
                    .stream()
                    .filter(symbols::contains)
                    .toList();
            if (!matched.isEmpty()) {
                references.add(new GlobalAddressReference(global, matched));
            }
        }
        return List.copyOf(references);
    }

    static List<String> functionReferences(LlvmModule module, String target) {
        ArrayList<String> result = new ArrayList<>();
        for (LlvmFunction function : module.functions()) {
            referencedSymbols(function).stream()
                    .filter(target::equals)
                    .forEach(ignored -> result.add(function.name()));
        }
        return List.copyOf(result);
    }

    private static List<String> referencedSymbols(LlvmFunction function) {
        ArrayList<String> result = new ArrayList<>();
        function.blocks().stream()
                .flatMap(block -> block.instructions().stream())
                .forEach(instruction -> {
                    instruction.directCall()
                            .map(LlvmDirectCallRef::target)
                            .ifPresent(result::add);
                    instruction.directCall().stream()
                            .flatMap(call -> call.arguments().stream())
                            .flatMap(argument -> GLOBAL_REFERENCE_LEXER
                                    .symbolReferences(argument.value())
                                    .stream())
                            .forEach(result::add);
                    instruction.rawText().stream()
                            .flatMap(raw -> GLOBAL_REFERENCE_LEXER
                                    .symbolReferences(raw)
                                    .stream())
                            .forEach(result::add);
                    instruction.operands().stream()
                            .flatMap(operand -> GLOBAL_REFERENCE_LEXER
                                    .symbolReferences(operand)
                                    .stream())
                            .forEach(result::add);
                });
        return List.copyOf(result);
    }

    static List<String> symbolReferences(String llvmDefinition) {
        return GLOBAL_REFERENCE_LEXER.symbolReferences(llvmDefinition);
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

    record GlobalAddressReference(
            LlvmGlobal global,
            List<String> targetSymbols) {
        GlobalAddressReference {
            targetSymbols = List.copyOf(targetSymbols);
        }
    }
}
