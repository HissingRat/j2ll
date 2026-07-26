package xyz.melodysky.backend.llvm.protection;

import java.util.List;
import java.util.Objects;
import xyz.melodysky.backend.llvm.model.LlvmModule;

public record LlvmBlockLayoutPerturbationResult(
        LlvmModule module,
        List<String> affectedFunctions,
        List<String> validationIssues) {
    public LlvmBlockLayoutPerturbationResult {
        Objects.requireNonNull(module, "module");
        affectedFunctions = List.copyOf(Objects.requireNonNull(affectedFunctions, "affectedFunctions"));
        validationIssues = List.copyOf(Objects.requireNonNull(validationIssues, "validationIssues"));
    }

    public boolean valid() {
        return validationIssues.isEmpty();
    }
}
