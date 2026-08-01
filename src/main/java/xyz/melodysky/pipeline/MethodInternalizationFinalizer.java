package xyz.melodysky.pipeline;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import xyz.melodysky.analysis.method.NativeMethodInternalizationPlan;
import xyz.melodysky.packaging.MethodRewriteDecision;
import xyz.melodysky.packaging.MethodRewriteStrategy;
import xyz.melodysky.toolchain.NativeImplementationPlan;
import xyz.melodysky.toolchain.NativeMethodImplementation;

public final class MethodInternalizationFinalizer {
    public Result apply(
            NativeMethodInternalizationPlan internalizationPlan,
            List<MethodRewriteDecision> rewriteDecisions,
            NativeImplementationPlan implementationPlan) {
        Objects.requireNonNull(
                internalizationPlan,
                "internalizationPlan");
        Objects.requireNonNull(rewriteDecisions, "rewriteDecisions");
        Objects.requireNonNull(implementationPlan, "implementationPlan");
        Map<String, MethodRewriteDecision> replacements =
                new LinkedHashMap<>();
        ArrayList<MethodRewriteDecision> finalDecisions =
                new ArrayList<>();
        for (MethodRewriteDecision decision : rewriteDecisions) {
            MethodRewriteDecision replacement =
                    internalizationPlan.isInternalized(
                                    decision.method().methodKey())
                            ? new MethodRewriteDecision(
                                    decision.method(),
                                    MethodRewriteStrategy
                                            .INTERNAL_NATIVE_ONLY,
                                    decision.registrationOwner(),
                                    decision.generatedHelperName(),
                                    "METHOD_INTERNALIZATION_ELIGIBLE")
                            : decision;
            replacements.put(
                    decision.method().methodKey(),
                    replacement);
            finalDecisions.add(replacement);
        }
        List<NativeMethodImplementation> finalImplementations =
                implementationPlan.implementations().stream()
                        .map(implementation -> {
                            MethodRewriteDecision replacement =
                                    replacements.get(
                                            implementation.methodKey());
                            return replacement == null
                                    ? implementation
                                    : implementation.withDecision(
                                            replacement);
                        })
                        .toList();
        return new Result(
                List.copyOf(finalDecisions),
                new NativeImplementationPlan(
                        finalImplementations,
                        implementationPlan.unavailableReasonCodes(),
                        implementationPlan.localReferencePlans()));
    }

    public record Result(
            List<MethodRewriteDecision> rewriteDecisions,
            NativeImplementationPlan implementationPlan) {
        public Result {
            rewriteDecisions = List.copyOf(
                    Objects.requireNonNull(
                            rewriteDecisions,
                            "rewriteDecisions"));
            Objects.requireNonNull(
                    implementationPlan,
                    "implementationPlan");
        }
    }
}
