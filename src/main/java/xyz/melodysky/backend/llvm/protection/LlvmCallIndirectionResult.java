package xyz.melodysky.backend.llvm.protection;

import java.util.List;
import xyz.melodysky.backend.llvm.model.LlvmModule;

public record LlvmCallIndirectionResult(
        LlvmModule module,
        List<String> affectedFunctions,
        List<String> dispatcherSymbols,
        String reasonCode) {
    public LlvmCallIndirectionResult {
        affectedFunctions = List.copyOf(affectedFunctions);
        dispatcherSymbols = List.copyOf(dispatcherSymbols);
        java.util.Objects.requireNonNull(reasonCode, "reasonCode");
    }

    public boolean changed() {
        return !dispatcherSymbols.isEmpty();
    }
}
