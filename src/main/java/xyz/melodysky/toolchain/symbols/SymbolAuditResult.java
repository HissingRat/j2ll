package xyz.melodysky.toolchain.symbols;

import java.util.List;
import java.util.Objects;

public record SymbolAuditResult(
        List<String> allowedExports,
        List<String> actualExports,
        List<String> unexpectedExports,
        List<String> missingExports,
        boolean passed) {
    public SymbolAuditResult {
        allowedExports = sorted(allowedExports);
        actualExports = sorted(actualExports);
        unexpectedExports = sorted(unexpectedExports);
        missingExports = sorted(missingExports);
    }

    private static List<String> sorted(List<String> values) {
        return values.stream().filter(Objects::nonNull).sorted().toList();
    }
}
