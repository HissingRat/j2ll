package xyz.melodysky.toolchain.symbols;

import java.util.List;

public final class SymbolAudit {
    public SymbolAuditResult audit(ExportList allowlist, List<String> actualExports) {
        List<String> allowed = allowlist.symbols().stream().map(ExportedSymbol::name).toList();
        List<String> unexpected = actualExports.stream()
                .filter(symbol -> !allowlist.contains(symbol))
                .sorted()
                .toList();
        List<String> missing = allowed.stream()
                .filter(symbol -> !actualExports.contains(symbol))
                .sorted()
                .toList();
        return new SymbolAuditResult(allowed, actualExports, unexpected, missing, unexpected.isEmpty() && missing.isEmpty());
    }
}
