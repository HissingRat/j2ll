package xyz.melodysky.pipeline;

import java.util.List;
import java.util.Objects;
import xyz.melodysky.ir.ssa.SsaMethodResult;

/** Extracts the final, deterministic skipped-method set from lowering results. */
public final class SkippedMethodCollector {
    public List<SkippedMethod> collect(List<SsaMethodResult> results) {
        Objects.requireNonNull(results, "results");
        return results.stream()
                .filter(result -> result.status() == LoweringStatus.SKIPPED)
                .map(result -> new SkippedMethod(
                        result.sourceMethod().owner(),
                        result.sourceMethod().name(),
                        result.sourceMethod().descriptor(),
                        result.outcomeStage(),
                        result.reasonCode(),
                        result.reason()))
                .sorted()
                .toList();
    }
}
