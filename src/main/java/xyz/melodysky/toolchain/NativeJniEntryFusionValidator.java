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
                                            NativeJniEntrySemanticSurface
                                                    .requiresBranchedTopology(
                                                            implementation,
                                                            method,
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
                            } else if (decision.projection()
                                            .orElseThrow()
                                            .semanticParameterCount()
                                    != entry.getValue()
                                            .topology()
                                            .orElseThrow()
                                            .parameterCount()) {
                                issues.add(entry.getKey()
                                        + ":LLVM_JNI_PROXY_PROJECTION_DRIFT");
                            } else if (decision.profile()
                                            == NativeLocalAbiProfile
                                                    .JVM_SEMANTIC_SURFACE
                                    && !entry.getValue()
                                            .topology()
                                            .orElseThrow()
                                            .shape()
                                            .branched()) {
                                issues.add(entry.getKey()
                                        + ":LLVM_JNI_PROXY_SEMANTIC_TOPOLOGY_DRIFT");
                            } else if (!decision.reasonCode().equals(
                                    entry.getValue().reasonCode())) {
                                issues.add(entry.getKey()
                                        + ":LLVM_JNI_PROXY_REASON_DRIFT");
                            }
                        }, () -> issues.add(entry.getKey()
                                + ":LLVM_JNI_PROXY_IMPLEMENTATION_MISSING")));
        return issues.stream().sorted().toList();
    }

}
