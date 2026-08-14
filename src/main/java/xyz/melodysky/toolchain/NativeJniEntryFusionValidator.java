package xyz.melodysky.toolchain;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import xyz.melodysky.ir.model.IrMethod;

/** Revalidates the LLVM JNI proxy proof at the final native-plan boundary. */
public final class NativeJniEntryFusionValidator {
    private final NativeJniEntryEligibility eligibility =
            new NativeJniEntryEligibility();

    public List<String> validate(
            NativeImplementationPlan implementationPlan,
            Map<String, IrMethod> irMethods) {
        Objects.requireNonNull(implementationPlan, "implementationPlan");
        Objects.requireNonNull(irMethods, "irMethods");
        NativeJniEntryCallFacts callFacts = NativeJniEntryCallFacts.analyze(
                implementationPlan.implementations(),
                irMethods);
        ArrayList<String> issues = new ArrayList<>();
        implementationPlan.jniEntryPlans().entrySet().stream()
                .filter(entry -> entry.getValue().llvmJniProxy())
                .forEach(entry -> implementationPlan
                        .implementationFor(entry.getKey())
                        .ifPresentOrElse(implementation -> {
                            IrMethod method = implementation
                                    .implementationIrMethod()
                                    .orElse(irMethods.get(entry.getKey()));
                            NativeJniEntryEligibility.Decision decision =
                                    eligibility.assess(
                                            implementation,
                                            method,
                                            callFacts.targets(entry.getKey()),
                                            NativeJniEntryLocalReferenceFacts
                                                    .requiresSemanticHandling(
                                                            implementationPlan
                                                                    .localReferencePlans()
                                                                    .get(entry
                                                                            .getKey())));
                            if (!decision.approved()) {
                                issues.add(entry.getKey()
                                        + ":"
                                        + decision.reasonCode());
                            } else if (!decision.physicalAbi().equals(
                                    entry.getValue().physicalLlvmAbi())) {
                                issues.add(entry.getKey()
                                        + ":LLVM_JNI_PROXY_ABI_DRIFT");
                            }
                        }, () -> issues.add(entry.getKey()
                                + ":LLVM_JNI_PROXY_IMPLEMENTATION_MISSING")));
        return issues.stream().sorted().toList();
    }

}
