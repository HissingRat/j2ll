package xyz.melodysky.backend.llvm.protection;

import java.util.List;
import java.util.Objects;
import xyz.melodysky.backend.llvm.model.LlvmModule;

public record LlvmIrCallIndirectionResult(
        LlvmModule module,
        List<String> affectedFunctions,
        List<String> tableSymbols,
        List<String> validationIssues) {
    public LlvmIrCallIndirectionResult {
        Objects.requireNonNull(module, "module");
        affectedFunctions = affectedFunctions.stream().sorted().distinct().toList();
        tableSymbols = tableSymbols.stream().sorted().distinct().toList();
        validationIssues = List.copyOf(validationIssues);
    }

    public boolean changed() {
        return !affectedFunctions.isEmpty();
    }
}
