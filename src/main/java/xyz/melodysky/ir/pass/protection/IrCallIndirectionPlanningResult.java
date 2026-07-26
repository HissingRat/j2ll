package xyz.melodysky.ir.pass.protection;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record IrCallIndirectionPlanningResult(
        Optional<IrCallIndirectionPlan> plan,
        List<IrCallIndirectionSkip> skippedSites) {
    public IrCallIndirectionPlanningResult {
        Objects.requireNonNull(plan, "plan");
        skippedSites = skippedSites.stream().filter(Objects::nonNull).sorted().toList();
    }
}
