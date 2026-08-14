package xyz.melodysky.toolchain;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import xyz.melodysky.backend.llvm.model.LlvmFunction;
import xyz.melodysky.backend.llvm.model.LlvmGlobal;
import xyz.melodysky.backend.llvm.model.LlvmModule;
import xyz.melodysky.backend.llvm.model.LlvmModuleValidator;
import xyz.melodysky.backend.llvm.model.LlvmType;

/** Fail-closed global-address gate for one final LLVM JNI proxy closure. */
final class NativeJniProxyGlobalAddressVerifier {
    private static final String TABLE_REASON = "CALL_INDIRECTION_TABLE";

    List<String> validate(
            String methodKey,
            NativeLlvmModuleCompilation compilation,
            NativeLlvmSymbolIndex symbols,
            String proxy,
            LlvmFunction semanticBody,
            Set<String> bridges) {
        Objects.requireNonNull(methodKey, "methodKey");
        Objects.requireNonNull(compilation, "compilation");
        Objects.requireNonNull(symbols, "symbols");
        Objects.requireNonNull(proxy, "proxy");
        Objects.requireNonNull(semanticBody, "semanticBody");
        Set<String> bridgeSymbols = Set.copyOf(
                Objects.requireNonNull(bridges, "bridges"));

        HashSet<String> sensitive = new HashSet<>(bridgeSymbols);
        sensitive.add(proxy);
        sensitive.add(semanticBody.name());
        List<AuthorizedTable> authorized = authorizedTables(compilation);
        ArrayList<String> issues = new ArrayList<>();
        for (NativeLlvmSymbolIndex.GlobalAddressReference reference
                : symbols.globalAddressReferences(compilation, sensitive)) {
            if (reference.targetSymbols().stream()
                    .anyMatch(target -> target.equals(proxy)
                            || bridgeSymbols.contains(target))) {
                add(issues, methodKey);
                continue;
            }
            if (reference.targetSymbols().contains(semanticBody.name())
                    && authorized.stream().noneMatch(table ->
                            table.accepts(
                                    compilation.module(),
                                    reference.global(),
                                    semanticBody))) {
                add(issues, methodKey);
            }
        }
        rejectRetentionRoots(
                methodKey,
                compilation.module(),
                authorized,
                semanticBody.name(),
                issues);
        return issues.stream().distinct().sorted().toList();
    }

    private List<AuthorizedTable> authorizedTables(
            NativeLlvmModuleCompilation compilation) {
        ArrayList<AuthorizedTable> result = new ArrayList<>();
        var ir = compilation.irCallIndirection();
        if (ir.changed() && ir.validationIssues().isEmpty()) {
            ir.tableSymbols().forEach(symbol -> addEvidence(
                    result,
                    ir.module(),
                    symbol));
        }
        var llvm = compilation.llvmCallIndirection();
        if (llvm.changed() && TABLE_REASON.equals(llvm.reasonCode())) {
            llvm.dispatcherSymbols().forEach(symbol -> addEvidence(
                    result,
                    llvm.module(),
                    symbol));
        }
        return List.copyOf(result);
    }

    private void addEvidence(
            List<AuthorizedTable> result,
            LlvmModule evidenceModule,
            String symbol) {
        if (!new LlvmModuleValidator().validate(evidenceModule).isEmpty()) {
            return;
        }
        List<LlvmGlobal> matching = evidenceModule.globals().stream()
                .filter(global -> global.name().equals(symbol))
                .toList();
        if (matching.size() != 1) {
            return;
        }
        LlvmGlobal table = matching.get(0);
        List<String> targets = NativeLlvmSymbolIndex.symbolReferences(
                table.definition());
        if (!table.hasModuleLocalLinkage()
                || targets.isEmpty()
                || new HashSet<>(targets).size() != targets.size()) {
            return;
        }
        List<LlvmFunction> functions = targets.stream()
                .map(target -> uniqueFunction(evidenceModule, target))
                .toList();
        if (functions.stream().anyMatch(Objects::isNull)
                || functions.stream()
                        .map(Signature::of)
                        .distinct()
                        .count() != 1) {
            return;
        }
        result.add(new AuthorizedTable(
                table,
                List.copyOf(targets),
                Signature.of(functions.get(0))));
    }

    private LlvmFunction uniqueFunction(
            LlvmModule module,
            String symbol) {
        List<LlvmFunction> functions = module.functions().stream()
                .filter(function -> function.name().equals(symbol))
                .toList();
        return functions.size() == 1 ? functions.get(0) : null;
    }

    private void rejectRetentionRoots(
            String methodKey,
            LlvmModule finalModule,
            List<AuthorizedTable> authorized,
            String semanticBody,
            List<String> issues) {
        Set<String> bodyTables = authorized.stream()
                .filter(table -> table.targets().contains(semanticBody))
                .map(table -> table.global().name())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (bodyTables.isEmpty()) {
            return;
        }
        for (LlvmGlobal global : finalModule.globals()) {
            if (bodyTables.contains(global.name())) {
                continue;
            }
            if (NativeLlvmSymbolIndex.symbolReferences(global.definition())
                    .stream()
                    .anyMatch(bodyTables::contains)) {
                add(issues, methodKey);
            }
        }
    }

    private void add(List<String> issues, String methodKey) {
        issues.add(methodKey + ":LLVM_JNI_PROXY_GLOBAL_ADDRESS_SURFACE");
    }

    private record AuthorizedTable(
            LlvmGlobal global,
            List<String> targets,
            Signature signature) {
        private boolean accepts(
                LlvmModule finalModule,
                LlvmGlobal finalGlobal,
                LlvmFunction semanticBody) {
            return global.equals(finalGlobal)
                    && targets.contains(semanticBody.name())
                    && signature.equals(Signature.of(semanticBody))
                    && finalModule.globals().stream()
                            .filter(candidate -> candidate.name()
                                    .equals(global.name()))
                            .count() == 1;
        }
    }

    private record Signature(
            LlvmType returnType,
            List<LlvmType> parameterTypes) {
        private static Signature of(LlvmFunction function) {
            return new Signature(
                    function.returnType(),
                    function.parameters().stream()
                            .map(parameter -> parameter.type())
                            .toList());
        }
    }
}
