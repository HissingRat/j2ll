package xyz.melodysky.ir.pass.protection;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import xyz.melodysky.diagnostic.Diagnostic;
import xyz.melodysky.ir.model.IrProgram;

public record IrCallIndirectionResult(
        IrProgram program,
        Optional<IrCallIndirectionPlan> plan,
        List<IrCallIndirectionSkip> skippedSites,
        List<Diagnostic> diagnostics,
        String reasonCode) {
    public IrCallIndirectionResult {
        Objects.requireNonNull(program, "program");
        Objects.requireNonNull(plan, "plan");
        skippedSites = skippedSites.stream().filter(Objects::nonNull).sorted().toList();
        diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics"));
        Objects.requireNonNull(reasonCode, "reasonCode");
        if (reasonCode.isBlank()) {
            throw new IllegalArgumentException("reasonCode must not be blank");
        }
    }

    public boolean changed() {
        return plan.map(value -> !value.sites().isEmpty()).orElse(false);
    }
}
