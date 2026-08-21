package xyz.melodysky.pipeline;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import xyz.melodysky.analysis.callgraph.CallGraph;
import xyz.melodysky.analysis.callgraph.CallResolution;
import xyz.melodysky.analysis.callgraph.DevirtualizationPlan;
import xyz.melodysky.analysis.callgraph.InvokeKind;
import xyz.melodysky.analysis.reflection.ReflectionPlan;
import xyz.melodysky.config.IrProtectionConfig;
import xyz.melodysky.backend.llvm.LlvmNameMangler;
import xyz.melodysky.frontend.classfile.ParsedMethod;
import xyz.melodysky.frontend.classfile.ParsedProgram;
import xyz.melodysky.ir.model.IrClass;
import xyz.melodysky.ir.model.IrCallIndirectionMode;
import xyz.melodysky.ir.model.IrCallInvokeKind;
import xyz.melodysky.ir.model.IrMethod;
import xyz.melodysky.ir.model.IrOpcode;
import xyz.melodysky.ir.model.IrProgram;
import xyz.melodysky.ir.pass.protection.MethodInliningAccess;
import xyz.melodysky.ir.pass.protection.MethodInliningCandidate;
import xyz.melodysky.ir.pass.protection.MethodInliningDecision;
import xyz.melodysky.ir.pass.protection.MethodInliningOptions;
import xyz.melodysky.ir.pass.protection.MethodInliningPass;
import xyz.melodysky.ir.pass.protection.MethodInliningPlan;
import xyz.melodysky.ir.pass.protection.MethodInliningReason;
import xyz.melodysky.ir.pass.protection.IrCallIndirectionPass;
import xyz.melodysky.ir.pass.protection.IrCallIndirectionPlan;
import xyz.melodysky.ir.pass.protection.IrCallIndirectionReasons;
import xyz.melodysky.ir.pass.protection.IrCallIndirectionResult;
import xyz.melodysky.ir.pass.protection.IrCallSiteId;
import xyz.melodysky.ir.pass.protection.IrDirectCallFact;
import xyz.melodysky.ir.pass.protection.IrDirectCallFacts;
import xyz.melodysky.ir.pass.protection.IrNativeDirectTargets;
import xyz.melodysky.ir.pass.protection.MethodSplittingPass;
import xyz.melodysky.ir.pass.protection.MethodSplittingResult;
import xyz.melodysky.ir.pass.protection.MethodSplittingStatus;
import xyz.melodysky.ir.pass.protection.OutlinedMethodHelper;
import xyz.melodysky.protection.audit.ProtectionApplicability;
import xyz.melodysky.protection.audit.ProtectionPassCoverageFact;
import xyz.melodysky.report.ProtectionPassReport;
import xyz.melodysky.toolchain.NativeImplementationPath;
import xyz.melodysky.toolchain.NativeImplementationPlan;

/**
 * Coordinates cross-method protection without adding program concerns to the
 * per-method {@code ProtectionPipeline}.
 */
public final class ProgramIrProtectionCoordinator {
    private final Function<String, String> llvmFunctionName;

    public ProgramIrProtectionCoordinator() {
        this(new LlvmNameMangler());
    }

    public ProgramIrProtectionCoordinator(LlvmNameMangler llvmNameMangler) {
        java.util.Objects.requireNonNull(llvmNameMangler, "llvmNameMangler");
        llvmFunctionName = llvmNameMangler::functionName;
    }

    public ProgramIrProtectionResult run(
            Map<String, IrMethod> inputMethods,
            NativeImplementationPlan preliminaryImplementationPlan,
            ParsedProgram parsedProgram,
            ReflectionPlan reflectionPlan,
            CallGraph callGraph,
            DevirtualizationPlan devirtualizationPlan,
            IrProtectionConfig config,
            long seed) {
        Map<String, IrMethod> methods = orderedMethods(inputMethods);
        ArrayList<ProtectionPassReport> reports = new ArrayList<>();

        MethodInliningPlan inliningPlan = inliningPlan(
                methods,
                preliminaryImplementationPlan,
                parsedProgram,
                reflectionPlan);
        var inliningResult = new MethodInliningPass().run(
                program(methods),
                inliningPlan,
                config.enabled() && config.methodInlining()
                        ? MethodInliningOptions.enabled(seed)
                        : MethodInliningOptions.disabled(seed));
        methods = methodMap(inliningResult.program());
        reports.add(inliningReport(
                config.enabled() && config.methodInlining(),
                inliningResult.decisions(),
                inliningPlan,
                methods.keySet(),
                seed));

        IrProgram callInput = program(methods);
        Map<String, IrNativeDirectTargets.FunctionAbi> nativeFunctionAbis =
                preliminaryImplementationPlan.llvmImplementations().stream()
                        .collect(java.util.stream.Collectors.toUnmodifiableMap(
                                implementation -> implementation.methodKey(),
                                implementation -> new IrNativeDirectTargets.FunctionAbi(
                                        implementation.passesJniEnv(),
                                        implementation.passesOwnerClass())));
        boolean callIndirectionEnabled = config.enabled() && config.callIndirection();
        IrCallIndirectionResult callIndirection = new IrCallIndirectionPass().run(
                callInput,
                directCallFacts(
                        callInput,
                        callGraph,
                        devirtualizationPlan),
                new IrNativeDirectTargets(nativeFunctionAbis),
                IrCallIndirectionMode.TABLE,
                seed,
                callIndirectionEnabled);
        methods = methodMap(callIndirection.program());
        reports.add(callIndirectionReport(
                callIndirectionEnabled,
                callIndirection,
                callInput.classes().stream()
                        .flatMap(irClass -> irClass.methods().stream())
                        .map(IrMethod::methodKey)
                        .toList(),
                seed));

        SplitRun split = split(
                methods,
                preliminaryImplementationPlan,
                config.enabled() && config.methodSplitting(),
                seed);
        reports.add(split.report());
        return new ProgramIrProtectionResult(
                split.javaMethods(),
                split.compilerInternalMethods(),
                reports,
                callIndirection.diagnostics());
    }

    private IrDirectCallFacts directCallFacts(
            IrProgram program,
            CallGraph callGraph,
            DevirtualizationPlan devirtualizationPlan) {
        ArrayList<IrDirectCallFact> facts = new ArrayList<>();
        for (IrClass irClass : program.classes()) {
            for (IrMethod method : irClass.methods()) {
                for (var block : method.blocks()) {
                    for (int index = 0; index < block.instructions().size(); index++) {
                        var instruction = block.instructions().get(index);
                        if (instruction.opcode() != IrOpcode.CALL_VIRTUAL
                                && instruction.opcode() != IrOpcode.CALL_INTERFACE) {
                            continue;
                        }
                        String declaredTarget = instruction.symbol().orElse("");
                        IrCallInvokeKind instructionKind =
                                IrCallInvokeKind.fromOpcode(instruction.opcode());
                        List<CallResolution> resolutions = callGraph.resolutions().stream()
                                .filter(resolution -> callerKey(resolution).equals(method.methodKey()))
                                .filter(resolution -> callGraphKind(resolution.callSite().kind())
                                        .filter(instructionKind::equals)
                                        .isPresent())
                                .filter(resolution -> declaredTarget(resolution).equals(declaredTarget))
                                .toList();
                        List<xyz.melodysky.analysis.callgraph.DevirtualizationDecision>
                                decisions = resolutions.stream()
                                        .map(resolution -> devirtualizationPlan
                                                .decisionFor(
                                                        resolution.callSite().id()))
                                        .flatMap(Optional::stream)
                                        .toList();
                        Set<String> knownTargets = decisions.stream()
                                .flatMap(decision -> decision.directTarget().stream())
                                .map(target -> target.owner().orElseThrow()
                                        + "#"
                                        + target.signature().orElseThrow().name()
                                        + "!"
                                        + target.signature().orElseThrow().descriptor())
                                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
                        boolean unavailable = decisions.size() != resolutions.size()
                                || decisions.stream().anyMatch(decision ->
                                        decision.directNativeTargetUnavailable()
                                                || decision.jvmDispatchRequired());
                        if (resolutions.isEmpty()
                                || unavailable
                                || knownTargets.size() != 1) {
                            continue;
                        }
                        facts.add(IrDirectCallFact.devirtualized(
                                new IrCallSiteId(method.methodKey(), block.name(), index),
                                instructionKind,
                                knownTargets.iterator().next()));
                    }
                }
            }
        }
        return new IrDirectCallFacts(facts);
    }

    private String callerKey(CallResolution resolution) {
        var site = resolution.callSite();
        return site.callerOwner() + "#" + site.caller().name() + "!" + site.caller().descriptor();
    }

    private String declaredTarget(CallResolution resolution) {
        var site = resolution.callSite();
        return site.declaredOwner() + "#" + site.declaredTarget().name()
                + "!" + site.declaredTarget().descriptor();
    }

    private Optional<IrCallInvokeKind> callGraphKind(InvokeKind kind) {
        return switch (kind) {
            case STATIC -> Optional.of(IrCallInvokeKind.STATIC);
            case SPECIAL -> Optional.of(IrCallInvokeKind.SPECIAL);
            case VIRTUAL -> Optional.of(IrCallInvokeKind.VIRTUAL);
            case INTERFACE -> Optional.of(IrCallInvokeKind.INTERFACE);
            case DYNAMIC -> Optional.empty();
        };
    }

    private ProtectionPassReport callIndirectionReport(
            boolean enabled,
            IrCallIndirectionResult result,
            List<String> subjectMethods,
            long seed) {
        List<ProtectionPassCoverageFact> facts =
                callIndirectionFacts(enabled, result, subjectMethods);
        if (!enabled) {
            return report(
                    "IR_CALL_INDIRECTION",
                    "SKIPPED",
                    "PROTECTION_PASS_DISABLED",
                    List.of(),
                    List.of(),
                    seed,
                    facts);
        }
        if (!result.diagnostics().isEmpty()) {
            return report(
                    "IR_CALL_INDIRECTION",
                    "FAILED",
                    IrCallIndirectionReasons.VALIDATION_FAILED,
                    result.plan().stream()
                            .flatMap(plan -> plan.sites().stream())
                            .map(site -> site.siteId().callerMethodKey())
                            .toList(),
                    List.of(),
                    seed,
                    facts);
        }
        if (!result.changed()) {
            String reason = result.skippedSites().stream()
                    .map(skip -> skip.reasonCode())
                    .sorted()
                    .findFirst()
                    .orElse(IrCallIndirectionReasons.NO_CANDIDATE);
            return report(
                    "IR_CALL_INDIRECTION",
                    "SKIPPED",
                    reason,
                    result.skippedSites().stream()
                            .map(skip -> skip.siteId().callerMethodKey())
                            .toList(),
                    List.of(),
                    seed,
                    facts);
        }
        IrCallIndirectionPlan plan = result.plan().orElseThrow();
        return report(
                "IR_CALL_INDIRECTION",
                "RAN",
                result.reasonCode(),
                plan.sites().stream().map(site -> site.siteId().callerMethodKey()).toList(),
                java.util.stream.Stream.concat(
                                plan.groups().stream().map(group -> group.groupId()),
                                plan.groups().stream()
                                        .flatMap(group -> group.targets().stream())
                                        .map(target -> target.entryId()))
                        .toList(),
                seed,
                facts);
    }

    private MethodInliningPlan inliningPlan(
            Map<String, IrMethod> methods,
            NativeImplementationPlan implementationPlan,
            ParsedProgram parsedProgram,
            ReflectionPlan reflectionPlan) {
        Map<String, ParsedMethod> parsedMethods = parsedProgram.classes().stream()
                .flatMap(parsedClass -> parsedClass.methods().stream())
                .collect(java.util.stream.Collectors.toMap(
                        ParsedMethod::methodKey,
                        method -> method,
                        (left, right) -> left,
                        LinkedHashMap::new));
        Set<String> llvmMethods = implementationPlan.llvmImplementations().stream()
                .map(implementation -> implementation.methodKey())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        Set<String> reflectionSensitive = reflectionPlan.reachableMethods().stream()
                .map(target -> target.methodKey())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        ArrayList<MethodInliningCandidate> candidates = new ArrayList<>();
        for (IrMethod caller : methods.values()) {
            caller.blocks().stream()
                    .flatMap(block -> block.instructions().stream())
                    .filter(instruction -> instruction.opcode() == IrOpcode.CALL_STATIC
                            || instruction.opcode() == IrOpcode.CALL_SPECIAL)
                    .forEach(instruction -> instruction.symbol().ifPresent(targetKey -> {
                        ParsedMethod target = parsedMethods.get(targetKey);
                        if (target == null || !methods.containsKey(targetKey)) {
                            return;
                        }
                        MethodInliningAccess access;
                        if (instruction.opcode() == IrOpcode.CALL_STATIC && target.accessFlags().isStatic()) {
                            access = MethodInliningAccess.STATIC;
                        } else if (instruction.opcode() == IrOpcode.CALL_SPECIAL
                                && target.accessFlags().isPrivate()
                                && caller.owner().equals(target.owner())) {
                            access = MethodInliningAccess.PRIVATE_INSTANCE_SELF;
                        } else {
                            return;
                        }
                        boolean callerNative = llvmMethods.contains(caller.methodKey());
                        boolean calleeNative = llvmMethods.contains(targetKey);
                        candidates.add(new MethodInliningCandidate(
                                caller.methodKey(),
                                targetKey,
                                instruction.opcode(),
                                access,
                                true,
                                 callerNative,
                                 calleeNative,
                                 reflectionSensitive.contains(targetKey)));
                    }));
        }
        return new MethodInliningPlan(candidates);
    }

    private SplitRun split(
            Map<String, IrMethod> methods,
            NativeImplementationPlan implementationPlan,
            boolean enabled,
            long seed) {
        Set<String> llvmMethods = implementationPlan.llvmImplementations().stream()
                .map(implementation -> implementation.methodKey())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        LinkedHashMap<String, IrMethod> javaMethods = new LinkedHashMap<>(methods);
        LinkedHashMap<String, IrMethod> helpers = new LinkedHashMap<>();
        ArrayList<MethodSplittingResult> decisions = new ArrayList<>();
        MethodSplittingPass pass = new MethodSplittingPass();
        for (IrMethod method : methods.values().stream()
                .sorted(Comparator.comparing(IrMethod::methodKey))
                .toList()) {
            if (!llvmMethods.contains(method.methodKey())) {
                continue;
            }
            MethodSplittingResult result = pass.run(method, seed, enabled);
            decisions.add(result);
            if (result.status() != MethodSplittingStatus.RAN) {
                continue;
            }
            javaMethods.put(method.methodKey(), result.caller());
            for (OutlinedMethodHelper helper : result.helpers()) {
                if (helpers.putIfAbsent(helper.methodKey(), helper.body()) != null
                        || javaMethods.containsKey(helper.methodKey())) {
                    throw new IllegalStateException(
                            "outlined helper identity collision " + helper.methodKey());
                }
            }
        }
        return new SplitRun(
                java.util.Collections.unmodifiableMap(new LinkedHashMap<>(javaMethods)),
                java.util.Collections.unmodifiableMap(new LinkedHashMap<>(helpers)),
                splittingReport(enabled, decisions, seed));
    }

    private ProtectionPassReport inliningReport(
            boolean enabled,
            List<MethodInliningDecision> decisions,
            MethodInliningPlan plan,
            Set<String> subjectMethods,
            long seed) {
        List<ProtectionPassCoverageFact> facts =
                inliningFacts(enabled, decisions, subjectMethods);
        if (!enabled) {
            return report(
                    "METHOD_INLINING",
                    "SKIPPED",
                    "PROTECTION_PASS_DISABLED",
                    plan.candidates().stream().map(MethodInliningCandidate::callerMethodKey).toList(),
                    List.of(),
                    seed,
                    facts);
        }
        List<MethodInliningDecision> inlined = decisions.stream()
                .filter(decision -> decision.status() == MethodInliningDecision.Status.INLINED)
                .toList();
        List<MethodInliningDecision> failed = decisions.stream()
                .filter(decision -> decision.status() == MethodInliningDecision.Status.FAILED)
                .toList();
        if (!failed.isEmpty()) {
            return report(
                    "METHOD_INLINING",
                    "FAILED",
                    MethodInliningReason.VALIDATION_FAILED,
                    failed.stream().map(MethodInliningDecision::callerMethodKey).toList(),
                    List.of(),
                    seed,
                    facts);
        }
        if (!inlined.isEmpty()) {
            return report(
                    "METHOD_INLINING",
                    "RAN",
                    MethodInliningReason.INLINED,
                    inlined.stream().map(MethodInliningDecision::callerMethodKey).toList(),
                    List.of(),
                    seed,
                    facts);
        }
        String reason = decisions.stream()
                .map(MethodInliningDecision::reasonCode)
                .sorted()
                .findFirst()
                .orElse(MethodInliningReason.NO_CANDIDATE);
        return report(
                "METHOD_INLINING",
                "SKIPPED",
                reason,
                plan.candidates().stream().map(MethodInliningCandidate::callerMethodKey).toList(),
                List.of(),
                seed,
                facts);
    }

    private ProtectionPassReport splittingReport(
            boolean enabled,
            List<MethodSplittingResult> decisions,
            long seed) {
        List<ProtectionPassCoverageFact> facts =
                splittingFacts(enabled, decisions);
        if (!enabled) {
            return report(
                    "METHOD_SPLITTING",
                    "SKIPPED",
                    "PROTECTION_PASS_DISABLED",
                    decisions.stream().map(result -> result.caller().methodKey()).toList(),
                    List.of(),
                    seed,
                    facts);
        }
        List<MethodSplittingResult> failed = decisions.stream()
                .filter(result -> result.status() == MethodSplittingStatus.FAILED)
                .toList();
        if (!failed.isEmpty()) {
            return report(
                    "METHOD_SPLITTING",
                    "FAILED",
                    "METHOD_SPLITTING_VALIDATION_FAILED",
                    failed.stream().map(result -> result.caller().methodKey()).toList(),
                    List.of(),
                    seed,
                    facts);
        }
        List<MethodSplittingResult> ran = decisions.stream()
                .filter(result -> result.status() == MethodSplittingStatus.RAN)
                .toList();
        if (!ran.isEmpty()) {
            return report(
                    "METHOD_SPLITTING",
                    "RAN",
                    "METHOD_SPLITTING",
                    ran.stream().map(result -> result.caller().methodKey()).toList(),
                    ran.stream()
                            .flatMap(result -> result.helpers().stream())
                            .map(helper -> helper.emittedFunctionSymbol(llvmFunctionName))
                            .toList(),
                    seed,
                    facts);
        }
        String reason = decisions.stream()
                .map(MethodSplittingResult::reasonCode)
                .sorted()
                .findFirst()
                .orElse("METHOD_SPLITTING_NO_CANDIDATE");
        return report(
                "METHOD_SPLITTING",
                "SKIPPED",
                reason,
                decisions.stream().map(result -> result.caller().methodKey()).toList(),
                List.of(),
                seed,
                facts);
    }

    private List<ProtectionPassCoverageFact> inliningFacts(
            boolean enabled,
            List<MethodInliningDecision> decisions,
            Set<String> subjectMethods) {
        if (!enabled) {
            return ProtectionCoverageFacts.uniformMethods(
                    "IR",
                    "METHOD_INLINING",
                    subjectMethods,
                    false,
                    ProtectionApplicability.UNKNOWN,
                    false,
                    "SKIPPED",
                    "PROTECTION_PASS_DISABLED");
        }
        Map<String, List<MethodInliningDecision>> byCaller =
                decisions.stream().collect(
                        java.util.stream.Collectors.groupingBy(
                                MethodInliningDecision::callerMethodKey,
                                LinkedHashMap::new,
                                java.util.stream.Collectors.toList()));
        return subjectMethods.stream().sorted().map(methodKey -> {
            List<MethodInliningDecision> methodDecisions =
                    byCaller.getOrDefault(methodKey, List.of());
            if (methodDecisions.stream().anyMatch(decision ->
                    decision.status()
                            == MethodInliningDecision.Status.FAILED)) {
                return ProtectionCoverageFacts.method(
                        "IR",
                        "METHOD_INLINING",
                        methodKey,
                        true,
                        ProtectionApplicability.APPLICABLE,
                        false,
                        "FAILED",
                        MethodInliningReason.VALIDATION_FAILED);
            }
            if (methodDecisions.stream().anyMatch(decision ->
                    decision.status()
                            == MethodInliningDecision.Status.INLINED)) {
                return ProtectionCoverageFacts.method(
                        "IR",
                        "METHOD_INLINING",
                        methodKey,
                        true,
                        ProtectionApplicability.APPLICABLE,
                        true,
                        "RAN",
                        MethodInliningReason.INLINED);
            }
            String reason = methodDecisions.stream()
                    .map(MethodInliningDecision::reasonCode)
                    .sorted()
                    .findFirst()
                    .orElse(MethodInliningReason.NO_CANDIDATE);
            return ProtectionCoverageFacts.method(
                    "IR",
                    "METHOD_INLINING",
                    methodKey,
                    true,
                    ProtectionApplicability.NOT_APPLICABLE,
                    false,
                    "SKIPPED",
                    reason);
        }).toList();
    }

    private List<ProtectionPassCoverageFact> splittingFacts(
            boolean enabled,
            List<MethodSplittingResult> decisions) {
        return decisions.stream()
                .sorted(Comparator.comparing(
                        result -> result.caller().methodKey()))
                .map(result -> {
                    if (!enabled) {
                        return ProtectionCoverageFacts.method(
                                "IR",
                                "METHOD_SPLITTING",
                                result.caller().methodKey(),
                                false,
                                ProtectionApplicability.UNKNOWN,
                                false,
                                "SKIPPED",
                                "PROTECTION_PASS_DISABLED");
                    }
                    boolean affected =
                            result.status() == MethodSplittingStatus.RAN;
                    return ProtectionCoverageFacts.method(
                            "IR",
                            "METHOD_SPLITTING",
                            result.caller().methodKey(),
                            true,
                            affected || result.status()
                                            == MethodSplittingStatus.FAILED
                                    ? ProtectionApplicability.APPLICABLE
                                    : ProtectionApplicability.NOT_APPLICABLE,
                            affected,
                            result.status().name(),
                            result.reasonCode());
                })
                .toList();
    }

    private List<ProtectionPassCoverageFact> callIndirectionFacts(
            boolean enabled,
            IrCallIndirectionResult result,
            List<String> subjectMethods) {
        if (!enabled) {
            return ProtectionCoverageFacts.uniformMethods(
                    "IR",
                    "IR_CALL_INDIRECTION",
                    subjectMethods,
                    false,
                    ProtectionApplicability.UNKNOWN,
                    false,
                    "SKIPPED",
                    "PROTECTION_PASS_DISABLED");
        }
        if (!result.diagnostics().isEmpty()) {
            return ProtectionCoverageFacts.uniformMethods(
                    "IR",
                    "IR_CALL_INDIRECTION",
                    subjectMethods,
                    true,
                    ProtectionApplicability.UNKNOWN,
                    false,
                    "FAILED",
                    IrCallIndirectionReasons.VALIDATION_FAILED);
        }
        Set<String> affectedCallers = result.plan().stream()
                .flatMap(plan -> plan.sites().stream())
                .map(site -> site.siteId().callerMethodKey())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        Map<String, String> skippedReasons = new LinkedHashMap<>();
        result.skippedSites().forEach(skip ->
                skippedReasons.merge(
                        skip.siteId().callerMethodKey(),
                        skip.reasonCode(),
                        (left, right) -> left.compareTo(right) <= 0
                                ? left
                                : right));
        return subjectMethods.stream().distinct().sorted().map(methodKey -> {
            boolean affected = affectedCallers.contains(methodKey);
            return ProtectionCoverageFacts.method(
                    "IR",
                    "IR_CALL_INDIRECTION",
                    methodKey,
                    true,
                    affected
                            ? ProtectionApplicability.APPLICABLE
                            : ProtectionApplicability.NOT_APPLICABLE,
                    affected,
                    affected ? "RAN" : "SKIPPED",
                    affected
                            ? result.reasonCode()
                            : skippedReasons.getOrDefault(
                                    methodKey,
                                    IrCallIndirectionReasons.NO_CANDIDATE));
        }).toList();
    }

    private ProtectionPassReport report(
            String pass,
            String status,
            String reason,
            List<String> methods,
            List<String> symbols,
            long seed,
            List<ProtectionPassCoverageFact> coverageFacts) {
        return new ProtectionPassReport(
                pass,
                "IR",
                status,
                reason,
                methods,
                symbols,
                Long.toString(seed),
                List.of(),
                coverageFacts);
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

    private Map<String, IrMethod> methodMap(IrProgram program) {
        LinkedHashMap<String, IrMethod> result = new LinkedHashMap<>();
        program.classes().stream()
                .flatMap(irClass -> irClass.methods().stream())
                .sorted(Comparator.comparing(IrMethod::methodKey))
                .forEach(method -> {
                    if (result.putIfAbsent(method.methodKey(), method) != null) {
                        throw new IllegalStateException("duplicate protected IR method " + method.methodKey());
                    }
                });
        return java.util.Collections.unmodifiableMap(result);
    }

    private Map<String, IrMethod> orderedMethods(Map<String, IrMethod> methods) {
        LinkedHashMap<String, IrMethod> result = new LinkedHashMap<>();
        methods.values().stream()
                .sorted(Comparator.comparing(IrMethod::methodKey))
                .forEach(method -> result.put(method.methodKey(), method));
        return java.util.Collections.unmodifiableMap(result);
    }

    private record SplitRun(
            Map<String, IrMethod> javaMethods,
            Map<String, IrMethod> compilerInternalMethods,
            ProtectionPassReport report) {
    }
}
