package xyz.melodysky.toolchain;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import xyz.melodysky.ir.model.IrMethod;
import xyz.melodysky.toolchain.nativetext.NativeTextBuildKey;

/**
 * Selects ordinary standalone LLVM methods for a distinct JNI proxy while
 * retaining their semantic LLVM body and bounded local-ABI topology.
 */
public final class NativeJniEntryFusionPlanner {
    private final NativeJniEntryEligibility eligibility =
            new NativeJniEntryEligibility();
    private final NativeJniEntryFusionValidator validator =
            new NativeJniEntryFusionValidator();

    public NativeImplementationPlan plan(
            NativeImplementationPlan implementationPlan,
            Map<String, IrMethod> irMethods,
            NativeTextBuildKey buildKey) {
        Objects.requireNonNull(implementationPlan, "implementationPlan");
        Objects.requireNonNull(irMethods, "irMethods");
        Objects.requireNonNull(buildKey, "buildKey");
        NativeJniProxySymbolMapper symbolMapper =
                new NativeJniProxySymbolMapper();
        LinkedHashMap<String, NativeJniEntryPlan> entries =
                new LinkedHashMap<>();
        for (NativeMethodImplementation implementation
                : implementationPlan.registeredImplementations()) {
            IrMethod method = implementation.implementationIrMethod()
                    .orElse(irMethods.get(implementation.methodKey()));
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
                                                    .get(implementation
                                                            .methodKey())));
            NativeJniEntryPlan entryPlan = decision.approved()
                    ? proxyPlan(
                            implementation,
                            method,
                            decision,
                            buildKey,
                            symbolMapper)
                    : NativeJniEntryPlan.wrapped(
                            implementation.entry().nativeSymbol(),
                            implementation.llvmFunctionAbi(),
                            decision.reasonCode());
            entries.put(implementation.methodKey(), entryPlan);
        }
        NativeImplementationPlan result = new NativeImplementationPlan(
                implementationPlan.implementations(),
                implementationPlan.unavailableReasonCodes(),
                implementationPlan.localReferencePlans(),
                entries);
        List<String> issues = validator.validate(result, irMethods);
        if (!issues.isEmpty()) {
            throw new IllegalStateException(
                    "LLVM JNI proxy final-plan validation failed: "
                            + String.join(",", issues));
        }
        return result;
    }

    private NativeJniEntryPlan proxyPlan(
            NativeMethodImplementation implementation,
            IrMethod method,
            NativeJniEntryEligibility.Decision decision,
            NativeTextBuildKey buildKey,
            NativeJniProxySymbolMapper symbolMapper) {
        NativeLocalAbiPlan localAbi = new NativeLocalAbiPlanner().plan(
                buildKey,
                implementation.methodKey(),
                decision.projection()
                        .orElseThrow()
                        .semanticParameterCount(),
                decision.profile());
        NativeJniEntryTopology topology = NativeJniEntryTopology.from(
                localAbi,
                symbolMapper.bridgeSymbols(
                        buildKey,
                        implementation.methodKey(),
                        localAbi.bridgeSymbols().size()));
        return NativeJniEntryPlan.llvmProxy(
                symbolMapper.proxySymbol(
                        buildKey,
                        implementation.methodKey()),
                decision.physicalAbi(),
                implementation.llvmFunctionSymbol().orElseThrow(),
                implementation.llvmFunctionAbi(),
                topology,
                decision.reasonCode());
    }
}
