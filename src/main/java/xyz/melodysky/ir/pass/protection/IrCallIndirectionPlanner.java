package xyz.melodysky.ir.pass.protection;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import xyz.melodysky.ir.model.IrBlock;
import xyz.melodysky.ir.model.IrCallIndirectionMode;
import xyz.melodysky.ir.model.IrCallIndirectionRef;
import xyz.melodysky.ir.model.IrCallInvokeKind;
import xyz.melodysky.ir.model.IrCallSignature;
import xyz.melodysky.ir.model.IrInstruction;
import xyz.melodysky.ir.model.IrMethod;
import xyz.melodysky.ir.model.IrOpcode;
import xyz.melodysky.ir.model.IrProgram;
import xyz.melodysky.ir.model.IrType;

/**
 * Builds the typed IR-level table/dispatcher plan. It never performs JVM call
 * resolution itself for dynamically dispatched calls: virtual/interface sites
 * require an explicit single-target analysis fact.
 */
public final class IrCallIndirectionPlanner {
    public IrCallIndirectionPlanningResult plan(
            IrProgram program,
            IrDirectCallFacts directCallFacts,
            IrNativeDirectTargets nativeDirectTargets,
            IrCallIndirectionMode mode,
            long seed) {
        Objects.requireNonNull(program, "program");
        Objects.requireNonNull(directCallFacts, "directCallFacts");
        Objects.requireNonNull(nativeDirectTargets, "nativeDirectTargets");
        Objects.requireNonNull(mode, "mode");
        Map<String, IrMethod> methodsByKey = methodsByKey(program);
        ArrayList<Candidate> candidates = new ArrayList<>();
        ArrayList<IrCallIndirectionSkip> skips = new ArrayList<>();

        for (var irClass : program.classes()) {
            for (IrMethod method : irClass.methods()) {
                for (IrBlock block : method.blocks()) {
                    for (int instructionIndex = 0;
                            instructionIndex < block.instructions().size();
                            instructionIndex++) {
                        IrInstruction instruction = block.instructions().get(instructionIndex);
                        if (!isCallOpcode(instruction.opcode())) {
                            continue;
                        }
                        IrCallSiteId siteId =
                                new IrCallSiteId(method.methodKey(), block.name(), instructionIndex);
                        CandidateDecision decision = candidate(
                                method,
                                instruction,
                                siteId,
                                directCallFacts.factFor(siteId),
                                methodsByKey,
                                nativeDirectTargets);
                        decision.candidate().ifPresent(candidates::add);
                        decision.skipReason().ifPresent(reason ->
                                skips.add(new IrCallIndirectionSkip(siteId, reason)));
                    }
                }
            }
        }
        if (candidates.isEmpty()) {
            return new IrCallIndirectionPlanningResult(Optional.empty(), skips);
        }
        return new IrCallIndirectionPlanningResult(
                Optional.of(buildPlan(candidates, mode, seed)),
                skips);
    }

    private CandidateDecision candidate(
            IrMethod caller,
            IrInstruction instruction,
            IrCallSiteId siteId,
            Optional<IrDirectCallFact> explicitFact,
            Map<String, IrMethod> methodsByKey,
            IrNativeDirectTargets nativeDirectTargets) {
        if (instruction.callIndirection().isPresent()) {
            return CandidateDecision.skip(IrCallIndirectionReasons.ALREADY_PLANNED);
        }
        if (instruction.opcode() == IrOpcode.CALL_DYNAMIC
                || instruction.opcode() == IrOpcode.CALL_RUNTIME_HELPER) {
            return CandidateDecision.skip(IrCallIndirectionReasons.DYNAMIC_OR_HELPER_SENSITIVE);
        }
        if (!nativeDirectTargets.contains(caller.methodKey())) {
            return CandidateDecision.skip(IrCallIndirectionReasons.CALLER_NOT_NATIVE_LOWERED);
        }
        if (instruction.symbol().filter(symbol -> !symbol.isBlank()).isEmpty()) {
            return CandidateDecision.skip(IrCallIndirectionReasons.SEMANTIC_TARGET_MISSING);
        }

        IrCallInvokeKind invokeKind = IrCallInvokeKind.fromOpcode(instruction.opcode());
        IrDirectCallFact fact = explicitFact.orElseGet(() ->
                inferredBytecodeDirect(siteId, invokeKind, instruction));
        if (fact.originalInvokeKind() != invokeKind) {
            return CandidateDecision.skip(IrCallIndirectionReasons.FACT_KIND_MISMATCH);
        }
        if (fact.helperSensitive()) {
            return CandidateDecision.skip(IrCallIndirectionReasons.DYNAMIC_OR_HELPER_SENSITIVE);
        }
        if (fact.resolutionKind() == IrDirectCallResolutionKind.UNRESOLVED) {
            return CandidateDecision.skip(IrCallIndirectionReasons.UNRESOLVED_TARGET);
        }
        if (fact.resolutionKind() == IrDirectCallResolutionKind.MULTIPLE_TARGETS) {
            return CandidateDecision.skip(IrCallIndirectionReasons.MULTIPLE_TARGETS);
        }
        if ((invokeKind == IrCallInvokeKind.DIRECT
                        || invokeKind == IrCallInvokeKind.VIRTUAL
                        || invokeKind == IrCallInvokeKind.INTERFACE)
                && fact.resolutionKind() != IrDirectCallResolutionKind.DEVIRTUALIZED_SINGLE_TARGET) {
            return CandidateDecision.skip(IrCallIndirectionReasons.UNRESOLVED_TARGET);
        }
        if ((invokeKind == IrCallInvokeKind.STATIC
                        || invokeKind == IrCallInvokeKind.SPECIAL)
                && fact.resolutionKind() != IrDirectCallResolutionKind.BYTECODE_DIRECT) {
            return CandidateDecision.skip(IrCallIndirectionReasons.FACT_KIND_MISMATCH);
        }
        if (fact.nativeTargetUnavailable()) {
            return CandidateDecision.skip(
                    IrCallIndirectionReasons.NATIVE_TARGET_UNAVAILABLE);
        }

        String targetMethodKey = fact.directTargetMethodKey().orElseThrow();
        if ((invokeKind == IrCallInvokeKind.STATIC
                        || invokeKind == IrCallInvokeKind.SPECIAL
                        || invokeKind == IrCallInvokeKind.DIRECT)
                && instruction.symbol().filter(targetMethodKey::equals).isEmpty()) {
            return CandidateDecision.skip(IrCallIndirectionReasons.FACT_TARGET_MISMATCH);
        }
        IrMethod target = methodsByKey.get(targetMethodKey);
        if (target == null) {
            return CandidateDecision.skip(IrCallIndirectionReasons.TARGET_NOT_IN_PROGRAM);
        }
        if (!nativeDirectTargets.contains(targetMethodKey)) {
            return CandidateDecision.skip(IrCallIndirectionReasons.TARGET_NOT_NATIVE_LOWERED);
        }
        if (target.name().equals("<init>") || target.name().equals("<clinit>")) {
            return CandidateDecision.skip(IrCallIndirectionReasons.CONSTRUCTOR_OR_INITIALIZER);
        }
        IrCallSignature signature = IrCallSignature.fromInstruction(instruction);
        if (!signature.equals(IrCallSignature.fromMethod(target))
                || (invokeKind.hasReceiver()
                        && (instruction.operands().isEmpty()
                                || instruction.operands().get(0).type() != IrType.REFERENCE))) {
            return CandidateDecision.skip(IrCallIndirectionReasons.SIGNATURE_MISMATCH);
        }
        String targetOwner = owner(targetMethodKey);
        // The current LLVM backend can materialize a pointer table only for
        // calls whose implementation function lives in the caller's per-owner
        // module. Virtual/interface dispatch and cross-owner bytecode-direct
        // calls are JNI bridge shapes today; approving them here would report
        // an IR rewrite whose metadata is discarded during LLVM lowering.
        if (invokeKind == IrCallInvokeKind.VIRTUAL
                || invokeKind == IrCallInvokeKind.INTERFACE
                || !caller.owner().equals(targetOwner)) {
            return CandidateDecision.skip(IrCallIndirectionReasons.BACKEND_UNSUPPORTED_SHAPE);
        }
        return CandidateDecision.approved(new Candidate(
                siteId,
                targetMethodKey,
                signature,
                nativeDirectTargets.functionAbi(targetMethodKey),
                invokeKind,
                new IrCallSemantics(
                        invokeKind,
                        invokeKind.hasReceiver(),
                        false,
                        true,
                        false)));
    }

    private IrDirectCallFact inferredBytecodeDirect(
            IrCallSiteId siteId,
            IrCallInvokeKind invokeKind,
            IrInstruction instruction) {
        if (invokeKind == IrCallInvokeKind.DIRECT
                || invokeKind == IrCallInvokeKind.VIRTUAL
                || invokeKind == IrCallInvokeKind.INTERFACE) {
            return IrDirectCallFact.unresolved(
                    siteId,
                    invokeKind,
                    IrDirectCallResolutionKind.UNRESOLVED);
        }
        String target = instruction.symbol().orElse("");
        if (target.isBlank()) {
            return IrDirectCallFact.unresolved(
                    siteId,
                    invokeKind,
                    IrDirectCallResolutionKind.UNRESOLVED);
        }
        return IrDirectCallFact.bytecodeDirect(siteId, invokeKind, target);
    }

    private IrCallIndirectionPlan buildPlan(
            List<Candidate> candidates,
            IrCallIndirectionMode mode,
            long seed) {
        ProtectionRandom random = new ProtectionRandom(seed);
        List<Candidate> orderedCandidates = candidates.stream()
                .sorted(Comparator.comparing(candidate -> candidate.siteId().stableKey()))
                .toList();
        String planInput = mode.name() + ":" + orderedCandidates.stream()
                .map(candidate -> candidate.siteId().stableKey()
                        + "->"
                        + candidate.targetMethodKey()
                        + "@"
                        + candidate.groupKey().stableKey())
                .reduce((left, right) -> left + "|" + right)
                .orElse("");
        String planId = "ircp_" + random.token("IR_CALL_INDIRECTION_PLAN", planInput, 32);

        Map<GroupKey, LinkedHashSet<String>> targetsByGroup = new LinkedHashMap<>();
        for (Candidate candidate : orderedCandidates) {
            targetsByGroup
                    .computeIfAbsent(candidate.groupKey(), ignored -> new LinkedHashSet<>())
                    .add(candidate.targetMethodKey());
        }
        Map<GroupKey, IrCallIndirectionGroup> groupByKey = new HashMap<>();
        Map<TargetKey, IrCallIndirectionTarget> targetByKey = new HashMap<>();
        ArrayList<IrCallIndirectionGroup> groups = new ArrayList<>();
        targetsByGroup.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    GroupKey groupKey = entry.getKey();
                    IrCallSignature signature = groupKey.signature();
                    String groupId = "ircg_" + random.token(
                            "IR_CALL_INDIRECTION_GROUP",
                            planId + ":" + groupKey.stableKey(),
                            24);
                    List<String> orderedTargets = entry.getValue().stream()
                            .sorted(Comparator
                                    .comparing((String target) -> random.token(
                                            "IR_CALL_INDIRECTION_TARGET_ORDER",
                                            groupId + ":" + target,
                                            32))
                                    .thenComparing(target -> target))
                            .toList();
                    Set<Integer> usedSelectors = new LinkedHashSet<>();
                    ArrayList<IrCallIndirectionTarget> targets = new ArrayList<>();
                    for (int index = 0; index < orderedTargets.size(); index++) {
                        String targetMethodKey = orderedTargets.get(index);
                        String entryId = "irce_" + random.token(
                                "IR_CALL_INDIRECTION_ENTRY",
                                groupId + ":" + targetMethodKey,
                                24);
                        int indexOrSelector = mode == IrCallIndirectionMode.TABLE
                                ? index
                                : uniqueSelector(random, entryId, usedSelectors);
                        IrCallIndirectionTarget target =
                                new IrCallIndirectionTarget(entryId, targetMethodKey, indexOrSelector);
                        targets.add(target);
                        targetByKey.put(new TargetKey(groupKey, targetMethodKey), target);
                    }
                    IrCallIndirectionGroup group =
                            new IrCallIndirectionGroup(groupId, signature, targets);
                    groups.add(group);
                    groupByKey.put(groupKey, group);
                });

        ArrayList<IrCallIndirectionSite> sites = new ArrayList<>();
        for (Candidate candidate : orderedCandidates) {
            IrCallIndirectionGroup group = groupByKey.get(candidate.groupKey());
            IrCallIndirectionTarget target =
                    targetByKey.get(new TargetKey(candidate.groupKey(), candidate.targetMethodKey()));
            IrCallIndirectionRef reference = new IrCallIndirectionRef(
                    planId,
                    group.groupId(),
                    target.entryId(),
                    mode,
                    candidate.signature(),
                    candidate.invokeKind());
            sites.add(new IrCallIndirectionSite(candidate.siteId(), reference, candidate.semantics()));
        }
        return new IrCallIndirectionPlan(planId, mode, groups, sites);
    }

    private int uniqueSelector(
            ProtectionRandom random,
            String entryId,
            Set<Integer> usedSelectors) {
        int selector = (int) (Long.parseUnsignedLong(
                        random.token("IR_CALL_INDIRECTION_SELECTOR", entryId, 8),
                        16)
                & 0x7fffffffL);
        while (!usedSelectors.add(selector)) {
            selector = selector == Integer.MAX_VALUE ? 0 : selector + 1;
        }
        return selector;
    }

    private Map<String, IrMethod> methodsByKey(IrProgram program) {
        LinkedHashMap<String, IrMethod> methods = new LinkedHashMap<>();
        program.classes().stream()
                .flatMap(irClass -> irClass.methods().stream())
                .sorted(Comparator.comparing(IrMethod::methodKey))
                .forEach(method -> {
                    if (methods.put(method.methodKey(), method) != null) {
                        throw new IllegalArgumentException("duplicate IR method key " + method.methodKey());
                    }
                });
        return Map.copyOf(methods);
    }

    private String owner(String methodKey) {
        int separator = methodKey.indexOf('#');
        if (separator <= 0) {
            return "";
        }
        return methodKey.substring(0, separator);
    }

    private boolean isCallOpcode(IrOpcode opcode) {
        return opcode == IrOpcode.CALL_STATIC
                || opcode == IrOpcode.CALL_SPECIAL
                || opcode == IrOpcode.CALL_DIRECT
                || opcode == IrOpcode.CALL_VIRTUAL
                || opcode == IrOpcode.CALL_INTERFACE
                || opcode == IrOpcode.CALL_DYNAMIC
                || opcode == IrOpcode.CALL_RUNTIME_HELPER;
    }

    private record Candidate(
            IrCallSiteId siteId,
            String targetMethodKey,
            IrCallSignature signature,
            IrNativeDirectTargets.FunctionAbi functionAbi,
            IrCallInvokeKind invokeKind,
            IrCallSemantics semantics) {
        private GroupKey groupKey() {
            return new GroupKey(signature, functionAbi);
        }
    }

    private record CandidateDecision(Optional<Candidate> candidate, Optional<String> skipReason) {
        private static CandidateDecision approved(Candidate candidate) {
            return new CandidateDecision(Optional.of(candidate), Optional.empty());
        }

        private static CandidateDecision skip(String reason) {
            return new CandidateDecision(Optional.empty(), Optional.of(reason));
        }
    }

    private record GroupKey(
            IrCallSignature signature,
            IrNativeDirectTargets.FunctionAbi functionAbi)
            implements Comparable<GroupKey> {
        private String stableKey() {
            return signature.stableKey() + ":" + functionAbi.stableKey();
        }

        @Override
        public int compareTo(GroupKey other) {
            return stableKey().compareTo(other.stableKey());
        }
    }

    private record TargetKey(GroupKey groupKey, String targetMethodKey) {
    }
}
