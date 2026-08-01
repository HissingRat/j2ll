package xyz.melodysky.pipeline;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import xyz.melodysky.analysis.method.NativeMethodInternalizationDecision;
import xyz.melodysky.analysis.method.NativeMethodInternalizationPlan;
import xyz.melodysky.diagnostic.Diagnostic;
import xyz.melodysky.diagnostic.DiagnosticCode;
import xyz.melodysky.diagnostic.DiagnosticLocation;
import xyz.melodysky.diagnostic.DiagnosticStage;
import xyz.melodysky.packaging.MethodRewriteStrategy;
import xyz.melodysky.toolchain.NativeImplementationPath;
import xyz.melodysky.toolchain.NativeImplementationPlan;
import xyz.melodysky.toolchain.NativeMethodImplementation;

public final class MethodInternalizationFinalPlanValidator {
    private static final DiagnosticCode FINAL_PLAN_MISMATCH =
            DiagnosticCode.of(
                    "METHOD_INTERNALIZATION_FINAL_PLAN_MISMATCH");

    public List<Diagnostic> validate(
            NativeMethodInternalizationPlan plan,
            NativeImplementationPlan implementationPlan) {
        Map<String, NativeMethodImplementation> implementations =
                implementationPlan.implementations().stream()
                        .collect(java.util.stream.Collectors.toMap(
                                NativeMethodImplementation::methodKey,
                                value -> value));
        var registrations = implementationPlan.registrationPlan()
                .entries()
                .stream()
                .map(entry -> entry.registrationOwner()
                        + "#"
                        + entry.methodName()
                        + "!"
                        + entry.descriptor())
                .collect(java.util.stream.Collectors.toSet());
        ArrayList<Diagnostic> diagnostics = new ArrayList<>();
        for (NativeMethodInternalizationDecision decision :
                plan.decisions()) {
            if (!decision.internalized()) {
                continue;
            }
            String methodKey = decision.method().methodKey();
            NativeMethodImplementation implementation =
                    implementations.get(methodKey);
            if (implementation == null
                    || implementation.path()
                            != NativeImplementationPath
                                    .LLVM_NATIVE_PATH
                    || implementation.decision().strategy()
                            != MethodRewriteStrategy
                                    .INTERNAL_NATIVE_ONLY
                    || registrations.contains(methodKey)) {
                diagnostics.add(error(
                        decision,
                        "approved method is missing its LLVM internal-only "
                                + "implementation or remains registered"));
                continue;
            }
            for (String callerKey :
                    decision.callerMethodKeys()) {
                NativeMethodImplementation caller =
                        implementations.get(callerKey);
                if (caller == null
                        || caller.path()
                                != NativeImplementationPath
                                        .LLVM_NATIVE_PATH) {
                    diagnostics.add(error(
                            decision,
                            "approved method has a caller without a final "
                                    + "LLVM implementation: "
                                    + callerKey));
                }
            }
        }
        return List.copyOf(diagnostics);
    }

    private Diagnostic error(
            NativeMethodInternalizationDecision decision,
            String message) {
        return Diagnostic.error(
                        DiagnosticStage.PROTECTION,
                        FINAL_PLAN_MISMATCH,
                        message)
                .at(DiagnosticLocation.methodLocation(
                        decision.method().owner(),
                        decision.method().name(),
                        decision.method().descriptor()))
                .withDecision("failed");
    }
}
