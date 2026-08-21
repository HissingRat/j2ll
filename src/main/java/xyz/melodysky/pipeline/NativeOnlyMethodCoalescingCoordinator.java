package xyz.melodysky.pipeline;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import xyz.melodysky.analysis.method.NativeMethodInternalizationPlan;
import xyz.melodysky.analysis.method.NativeOnlyMethodCoalescingDecision;
import xyz.melodysky.analysis.method.NativeOnlyMethodCoalescingPlan;
import xyz.melodysky.analysis.method.NativeOnlyMethodCoalescingReason;
import xyz.melodysky.backend.llvm.LlvmModuleLowerer;
import xyz.melodysky.ir.model.IrClass;
import xyz.melodysky.ir.model.IrMethod;
import xyz.melodysky.ir.model.IrProgram;
import xyz.melodysky.ir.pass.protection.MethodInliningDecision;
import xyz.melodysky.ir.pass.protection.MethodInliningOptions;
import xyz.melodysky.ir.pass.protection.MethodInliningPass;
import xyz.melodysky.ir.pass.protection.MethodInliningReason;
import xyz.melodysky.protection.audit.ProtectionApplicability;
import xyz.melodysky.report.ProtectionPassReport;
import xyz.melodysky.toolchain.NativeImplementationPlan;
import xyz.melodysky.toolchain.NativeMethodImplementation;

/**
 * Physically merges the conservative single-call-site subset of approved
 * internal-native-only methods and removes their standalone IR bodies.
 */
public final class NativeOnlyMethodCoalescingCoordinator {
    private static final String PASS =
            "NATIVE_ONLY_SINGLE_CALLER_COALESCING";
    private static final int MAX_COALESCED_CALLEE_INSTRUCTIONS = 96;

    public NativeOnlyMethodCoalescingResult run(
            Map<String, IrMethod> inputMethods,
            NativeMethodInternalizationPlan internalizationPlan,
            NativeImplementationPlan implementationPlan,
            long seed,
            LlvmModuleLowerer llvmLowerer) {
        java.util.Objects.requireNonNull(llvmLowerer, "llvmLowerer");
        LinkedHashMap<String, IrMethod> methods =
                new LinkedHashMap<>(inputMethods);
        LinkedHashMap<String, NativeMethodImplementation> implementations =
                implementationMap(implementationPlan);
        LinkedHashMap<String, NativeOnlyMethodCoalescingDecision>
                decisionsByCallee = new LinkedHashMap<>();
        NativeImplementationPlan currentImplementationPlan = implementationPlan;
        int roundLimit = Math.max(1, internalizationPlan.decisions().size());
        for (int round = 0; round < roundLimit; round++) {
            NativeOnlyMethodCoalescingPlanner.Draft draft =
                    new NativeOnlyMethodCoalescingPlanner().plan(
                            methods,
                            internalizationPlan,
                            currentImplementationPlan);
            draft.keptDecisions().forEach(decision ->
                    decisionsByCallee.put(
                            decision.calleeMethodKey(),
                            decision));
            if (draft.candidatesByCallee().isEmpty()) {
                break;
            }
            var inlining = new MethodInliningPass().run(
                    program(methods),
                    draft.inliningPlan(),
                    new MethodInliningOptions(
                            true,
                            seed,
                            MAX_COALESCED_CALLEE_INSTRUCTIONS,
                            64));
            LinkedHashMap<String, IrMethod> rewrittenMethods =
                    methodMap(inlining.program());
            int coalescedBefore = (int) decisionsByCallee.values().stream()
                    .filter(NativeOnlyMethodCoalescingDecision::coalesced)
                    .count();
            draft.candidatesByCallee().entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> finalizeCandidate(
                            entry.getKey(),
                            entry.getValue().callerMethodKey(),
                            inlining.decisions(),
                            rewrittenMethods,
                            implementations,
                            decisionsByCallee,
                            llvmLowerer));
            methods = rewrittenMethods;
            currentImplementationPlan = new NativeImplementationPlan(
                    List.copyOf(implementations.values()),
                    implementationPlan.unavailableReasonCodes(),
                    implementationPlan.localReferencePlans());
            int coalescedAfter = (int) decisionsByCallee.values().stream()
                    .filter(NativeOnlyMethodCoalescingDecision::coalesced)
                    .count();
            if (coalescedAfter == coalescedBefore) {
                break;
            }
        }

        NativeOnlyMethodCoalescingPlan plan =
                new NativeOnlyMethodCoalescingPlan(
                        List.copyOf(decisionsByCallee.values()));
        NativeImplementationPlan finalImplementationPlan =
                new NativeImplementationPlan(
                        List.copyOf(implementations.values()),
                        implementationPlan.unavailableReasonCodes(),
                        implementationPlan.localReferencePlans());
        return new NativeOnlyMethodCoalescingResult(
                methods,
                finalImplementationPlan,
                plan,
                report(plan, seed));
    }

    private void finalizeCandidate(
            String calleeKey,
            String callerKey,
            List<MethodInliningDecision> inliningDecisions,
            LinkedHashMap<String, IrMethod> methods,
            Map<String, NativeMethodImplementation> implementations,
            Map<String, NativeOnlyMethodCoalescingDecision>
                    decisionsByCallee,
            LlvmModuleLowerer llvmLowerer) {
        List<MethodInliningDecision> edgeDecisions = inliningDecisions.stream()
                .filter(decision -> decision.callerMethodKey().equals(callerKey)
                        && decision.calleeMethodKey().equals(calleeKey))
                .toList();
        Optional<MethodInliningDecision> failure = edgeDecisions.stream()
                .filter(decision -> decision.status()
                        != MethodInliningDecision.Status.INLINED)
                .findFirst();
        if (edgeDecisions.size() != 1 || failure.isPresent()) {
            String reason = failure.map(MethodInliningDecision::reasonCode)
                    .orElse(NativeOnlyMethodCoalescingReason.CALL_SITE_NOT_UNIQUE);
            decisionsByCallee.put(calleeKey,
                    NativeOnlyMethodCoalescingDecision.kept(
                            calleeKey,
                            Optional.of(callerKey),
                            reason));
            return;
        }
        if (hasResidualReference(methods, calleeKey, calleeKey)) {
            decisionsByCallee.put(calleeKey,
                    NativeOnlyMethodCoalescingDecision.kept(
                            calleeKey,
                            Optional.of(callerKey),
                            NativeOnlyMethodCoalescingReason.RESIDUAL_REFERENCE));
            return;
        }
        IrMethod callerBody = methods.get(callerKey);
        NativeMethodImplementation caller = implementations.get(callerKey);
        NativeMethodImplementation callee = implementations.get(calleeKey);
        if (callerBody == null || caller == null || callee == null) {
            decisionsByCallee.put(calleeKey,
                    NativeOnlyMethodCoalescingDecision.kept(
                            calleeKey,
                            Optional.of(callerKey),
                            NativeOnlyMethodCoalescingReason.IR_BODY_MISSING));
            return;
        }
        methods.remove(calleeKey);
        List<String> remainingDirectTargets = caller.directCallTargets().stream()
                .filter(target -> !target.equals(calleeKey))
                .toList();
        List<String> remainingStaticCalls = caller.staticCallKeys().stream()
                .filter(target -> !target.equals(calleeKey))
                .toList();
        var updatedAbi = llvmLowerer.inferFunctionAbi(
                callerBody,
                Set.copyOf(remainingDirectTargets),
                Set.copyOf(remainingStaticCalls));
        implementations.put(
                callerKey,
                caller.withEffectiveIrMethod(
                        callerBody,
                        calleeKey,
                        updatedAbi));
        implementations.replaceAll((methodKey, implementation) ->
                !methodKey.equals(calleeKey)
                                && implementation.coalescedIntoMethodKey()
                                        .filter(calleeKey::equals)
                                        .isPresent()
                        ? implementation.coalescedInto(callerKey)
                        : implementation);
        implementations.put(calleeKey, callee.coalescedInto(callerKey));
        decisionsByCallee.replaceAll((methodKey, decision) ->
                decision.coalesced()
                                && decision.callerMethodKey()
                                        .filter(calleeKey::equals)
                                        .isPresent()
                        ? NativeOnlyMethodCoalescingDecision.coalesced(
                                methodKey,
                                callerKey)
                        : decision);
        decisionsByCallee.put(calleeKey,
                NativeOnlyMethodCoalescingDecision.coalesced(
                        calleeKey,
                        callerKey));
    }

    private boolean hasResidualReference(
            Map<String, IrMethod> methods,
            String calleeKey,
            String ignoredCalleeBody) {
        return methods.values().stream()
                .filter(method -> !method.methodKey().equals(ignoredCalleeBody))
                .flatMap(method -> method.blocks().stream())
                .flatMap(block -> block.instructions().stream())
                .anyMatch(instruction -> instruction.symbol()
                        .filter(calleeKey::equals)
                        .isPresent());
    }

    private ProtectionPassReport report(
            NativeOnlyMethodCoalescingPlan plan,
            long seed) {
        List<String> affected = plan.decisions().stream()
                .filter(NativeOnlyMethodCoalescingDecision::coalesced)
                .map(NativeOnlyMethodCoalescingDecision::calleeMethodKey)
                .toList();
        boolean validationFailed = plan.decisions().stream()
                .anyMatch(this::validationFailed);
        String status;
        String reason;
        if (validationFailed) {
            status = "FAILED";
            reason = MethodInliningReason.VALIDATION_FAILED;
        } else if (affected.isEmpty()) {
            status = "SKIPPED";
            reason = plan.decisions().stream()
                        .map(NativeOnlyMethodCoalescingDecision::reasonCode)
                        .sorted()
                        .findFirst()
                        .orElse(NativeOnlyMethodCoalescingReason.NO_CANDIDATE);
        } else {
            status = "RAN";
            reason = NativeOnlyMethodCoalescingReason.COALESCED;
        }
        return new ProtectionPassReport(
                PASS,
                "IR",
                status,
                reason,
                affected,
                List.of(),
                Long.toString(seed),
                List.of(),
                plan.decisions().stream()
                        .map(decision -> ProtectionCoverageFacts.method(
                                "IR",
                                PASS,
                                decision.calleeMethodKey(),
                                true,
                                decision.coalesced()
                                        ? ProtectionApplicability.APPLICABLE
                                        : validationFailed(decision)
                                                ? ProtectionApplicability.UNKNOWN
                                                : ProtectionApplicability.NOT_APPLICABLE,
                                decision.coalesced(),
                                decision.coalesced()
                                        ? "RAN"
                                        : validationFailed(decision)
                                                ? "FAILED"
                                                : "SKIPPED",
                                decision.reasonCode()))
                        .toList());
    }

    private boolean validationFailed(
            NativeOnlyMethodCoalescingDecision decision) {
        return decision.reasonCode().equals(
                MethodInliningReason.VALIDATION_FAILED);
    }

    private IrProgram program(Map<String, IrMethod> methods) {
        Map<String, List<IrMethod>> byOwner = new LinkedHashMap<>();
        methods.values().stream()
                .sorted(Comparator.comparing(IrMethod::methodKey))
                .forEach(method -> byOwner
                        .computeIfAbsent(method.owner(), ignored -> new ArrayList<>())
                        .add(method));
        return new IrProgram(byOwner.entrySet().stream()
                .map(entry -> new IrClass(entry.getKey(), entry.getValue()))
                .toList());
    }

    private LinkedHashMap<String, IrMethod> methodMap(IrProgram program) {
        LinkedHashMap<String, IrMethod> result = new LinkedHashMap<>();
        program.classes().stream()
                .flatMap(irClass -> irClass.methods().stream())
                .sorted(Comparator.comparing(IrMethod::methodKey))
                .forEach(method -> result.put(method.methodKey(), method));
        return result;
    }

    private LinkedHashMap<String, NativeMethodImplementation>
            implementationMap(NativeImplementationPlan plan) {
        LinkedHashMap<String, NativeMethodImplementation> result =
                new LinkedHashMap<>();
        plan.implementations().forEach(
                implementation -> result.put(
                        implementation.methodKey(),
                        implementation));
        return result;
    }
}
