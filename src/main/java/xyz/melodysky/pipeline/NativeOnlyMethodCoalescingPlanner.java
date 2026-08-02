package xyz.melodysky.pipeline;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import xyz.melodysky.analysis.method.NativeMethodInternalizationDecision;
import xyz.melodysky.analysis.method.NativeMethodInternalizationPlan;
import xyz.melodysky.analysis.method.NativeOnlyMethodCoalescingDecision;
import xyz.melodysky.analysis.method.NativeOnlyMethodCoalescingReason;
import xyz.melodysky.ir.model.IrMethod;
import xyz.melodysky.ir.model.IrOpcode;
import xyz.melodysky.ir.pass.protection.MethodInliningAccess;
import xyz.melodysky.ir.pass.protection.MethodInliningCandidate;
import xyz.melodysky.ir.pass.protection.MethodInliningPlan;
import xyz.melodysky.packaging.MethodRewriteStrategy;
import xyz.melodysky.toolchain.NativeImplementationPath;
import xyz.melodysky.toolchain.NativeImplementationPlan;
import xyz.melodysky.toolchain.NativeMethodImplementation;

/** Derives immutable, analysis-backed candidates for physical body merging. */
final class NativeOnlyMethodCoalescingPlanner {
    Draft plan(
            Map<String, IrMethod> methods,
            NativeMethodInternalizationPlan internalizationPlan,
            NativeImplementationPlan implementationPlan) {
        Map<String, NativeMethodImplementation> implementations =
                implementationPlan.implementations().stream()
                        .collect(java.util.stream.Collectors.toUnmodifiableMap(
                                NativeMethodImplementation::methodKey,
                                implementation -> implementation));
        ArrayList<NativeOnlyMethodCoalescingDecision> kept = new ArrayList<>();
        LinkedHashMap<String, MethodInliningCandidate> candidates =
                new LinkedHashMap<>();
        Set<String> singleCallerInternalizedMethods = internalizationPlan
                .decisions()
                .stream()
                .filter(NativeMethodInternalizationDecision::internalized)
                .filter(decision -> decision.callerMethodKeys().size() == 1)
                .map(decision -> decision.method().methodKey())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        for (NativeMethodInternalizationDecision decision
                : internalizationPlan.decisions()) {
            if (!decision.internalized()) {
                continue;
            }
            String calleeKey = decision.method().methodKey();
            Optional<String> onlyCaller = decision.callerMethodKeys().size() == 1
                    ? Optional.of(decision.callerMethodKeys().get(0))
                    : Optional.empty();
            if (onlyCaller.isEmpty()) {
                kept.add(kept(
                        calleeKey,
                        Optional.empty(),
                        NativeOnlyMethodCoalescingReason.NOT_SINGLE_CALLER));
                continue;
            }
            String callerKey = onlyCaller.orElseThrow();
            // Keep the physical owner stable: V1 never creates a
            // callee -> coalesced caller -> caller chain.
            if (singleCallerInternalizedMethods.contains(callerKey)) {
                kept.add(kept(
                        calleeKey,
                        onlyCaller,
                        NativeOnlyMethodCoalescingReason
                                .CALLER_IS_COALESCING_CANDIDATE));
                continue;
            }
            NativeMethodImplementation callee = implementations.get(calleeKey);
            if (callee == null
                    || callee.path() != NativeImplementationPath.LLVM_NATIVE_PATH
                    || callee.decision().strategy()
                            != MethodRewriteStrategy.INTERNAL_NATIVE_ONLY
                    || callee.coalescedIntoMethodKey().isPresent()) {
                kept.add(kept(
                        calleeKey,
                        onlyCaller,
                        NativeOnlyMethodCoalescingReason
                                .IMPLEMENTATION_NOT_LLVM_INTERNAL_ONLY));
                continue;
            }
            NativeMethodImplementation caller = implementations.get(callerKey);
            if (caller == null
                    || caller.path() != NativeImplementationPath.LLVM_NATIVE_PATH
                    || !caller.emitsStandaloneLlvmBody()) {
                kept.add(kept(
                        calleeKey,
                        onlyCaller,
                        NativeOnlyMethodCoalescingReason.CALLER_NOT_LLVM));
                continue;
            }
            if (caller.initializerPlan().isPresent()) {
                // Initializer compilation consumes initializerPlan.nativeBody()
                // instead of the ordinary method map. Until coalescing can
                // replace both artifacts atomically, retaining the standalone
                // callee is the only sound physical plan.
                kept.add(kept(
                        calleeKey,
                        onlyCaller,
                        NativeOnlyMethodCoalescingReason
                                .CALLER_INITIALIZER_PLAN_UNSUPPORTED));
                continue;
            }
            IrMethod callerBody = methods.get(callerKey);
            IrMethod calleeBody = methods.get(calleeKey);
            if (callerBody == null || calleeBody == null) {
                kept.add(kept(
                        calleeKey,
                        onlyCaller,
                        NativeOnlyMethodCoalescingReason.IR_BODY_MISSING));
                continue;
            }
            if (implementationPlan.localReferencePlanFor(callerKey).isPresent()
                    || implementationPlan.localReferencePlanFor(calleeKey).isPresent()) {
                kept.add(kept(
                        calleeKey,
                        onlyCaller,
                        NativeOnlyMethodCoalescingReason.LOCAL_REFERENCE_SENSITIVE));
                continue;
            }
            List<IrOpcode> callOpcodes = directCallOpcodes(
                    methods,
                    callerKey,
                    calleeKey);
            if (callOpcodes.size() != 1) {
                kept.add(kept(
                        calleeKey,
                        onlyCaller,
                        NativeOnlyMethodCoalescingReason.CALL_SITE_NOT_UNIQUE));
                continue;
            }
            IrOpcode opcode = callOpcodes.get(0);
            MethodInliningAccess access;
            if (decision.staticMethod() && opcode == IrOpcode.CALL_STATIC) {
                access = MethodInliningAccess.STATIC;
            } else if (!decision.staticMethod()
                    && opcode == IrOpcode.CALL_SPECIAL
                    && callerBody.owner().equals(calleeBody.owner())) {
                access = MethodInliningAccess.PRIVATE_INSTANCE_SELF;
            } else {
                kept.add(kept(
                        calleeKey,
                        onlyCaller,
                        NativeOnlyMethodCoalescingReason.INVOKE_KIND_UNSUPPORTED));
                continue;
            }
            candidates.put(calleeKey, new MethodInliningCandidate(
                    callerKey,
                    calleeKey,
                    opcode,
                    access,
                    true,
                    true,
                    true,
                    false));
        }
        return new Draft(
                new MethodInliningPlan(List.copyOf(candidates.values())),
                Map.copyOf(candidates),
                List.copyOf(kept));
    }

    private List<IrOpcode> directCallOpcodes(
            Map<String, IrMethod> methods,
            String expectedCaller,
            String calleeKey) {
        ArrayList<IrOpcode> result = new ArrayList<>();
        for (IrMethod method : methods.values()) {
            method.blocks().stream()
                    .flatMap(block -> block.instructions().stream())
                    .filter(instruction -> instruction.symbol()
                            .filter(calleeKey::equals)
                            .isPresent())
                    .forEach(instruction -> {
                        if (method.methodKey().equals(expectedCaller)
                                && (instruction.opcode() == IrOpcode.CALL_STATIC
                                        || instruction.opcode()
                                                == IrOpcode.CALL_SPECIAL)) {
                            result.add(instruction.opcode());
                        } else {
                            // A non-direct or unexpected caller reference makes the
                            // count non-unique and therefore fail closed.
                            result.add(IrOpcode.CALL_DYNAMIC);
                        }
                    });
        }
        return List.copyOf(result);
    }

    private NativeOnlyMethodCoalescingDecision kept(
            String calleeKey,
            Optional<String> callerKey,
            String reason) {
        return NativeOnlyMethodCoalescingDecision.kept(
                calleeKey,
                callerKey,
                reason);
    }

    record Draft(
            MethodInliningPlan inliningPlan,
            Map<String, MethodInliningCandidate> candidatesByCallee,
            List<NativeOnlyMethodCoalescingDecision> keptDecisions) {
        Draft {
            keptDecisions = List.copyOf(keptDecisions);
            candidatesByCallee = Map.copyOf(candidatesByCallee);
        }
    }
}
