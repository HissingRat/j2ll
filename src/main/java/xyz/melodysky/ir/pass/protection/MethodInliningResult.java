package xyz.melodysky.ir.pass.protection;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import xyz.melodysky.ir.model.IrProgram;

public record MethodInliningResult(
        IrProgram program,
        List<MethodInliningDecision> decisions) {
    public MethodInliningResult {
        Objects.requireNonNull(program, "program");
        decisions = decisions.stream()
                .filter(Objects::nonNull)
                .sorted(Comparator.naturalOrder())
                .toList();
    }

    public long inlinedCount() {
        return decisions.stream()
                .filter(decision -> decision.status() == MethodInliningDecision.Status.INLINED)
                .count();
    }
}
