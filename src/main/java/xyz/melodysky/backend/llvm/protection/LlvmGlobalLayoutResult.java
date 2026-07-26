package xyz.melodysky.backend.llvm.protection;

import java.util.List;
import java.util.Objects;
import xyz.melodysky.backend.llvm.model.LlvmModule;

public record LlvmGlobalLayoutResult(
        LlvmModule module,
        List<String> affectedGlobals,
        List<String> validationIssues) {
    public LlvmGlobalLayoutResult {
        Objects.requireNonNull(module, "module");
        affectedGlobals = List.copyOf(Objects.requireNonNull(affectedGlobals, "affectedGlobals"));
        validationIssues = List.copyOf(Objects.requireNonNull(validationIssues, "validationIssues"));
    }

    public boolean changed() {
        return !affectedGlobals.isEmpty();
    }

    public boolean valid() {
        return validationIssues.isEmpty();
    }
}
