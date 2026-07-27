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
                seed));

        IrProgram callInput = program(methods);
        Set<String> nativeMethodKeys = preliminaryImplementationPlan.llvmImplementations().stream()
                .map(implementation -> implementation.methodKey())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        boolean callIndirectionEnabled = config.enabled() && config.callIndirection();
        IrCallIndirectionResult callIndirection = new IrCallIndirectionPass().run(
                callInput,
                directCallFacts(callInput, callGraph),
                new IrNativeDirectTargets(nativeMethodKeys),
                IrCallIndirectionMode.TABLE,
                seed,
                callIndirectionEnabled);
        methods = methodMap(callIndirection.program());
        reports.add(callIndirectionReport(
                callIndirectionEnabled,
                callIndirection,
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

    private IrDirectCallFacts directCallFacts(IrProgram program, CallGraph callGraph) {
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
                        Set<String> knownTargets = resolutions.stream()
                                .flatMap(resolution -> resolution.targets().stream())
                                .filter(target -> !target.unknownExternal())
                                .map(target -> target.owner().orElseThrow()
                                        + "#"
                                        + target.signature().orElseThrow().name()
                                        + "!"
                                        + target.signature().orElseThrow().descriptor())
                                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
                        boolean unknown = resolutions.stream().anyMatch(CallResolution::hasUnknownTarget);
                        if (resolutions.isEmpty() || unknown || knownTargets.size() != 1) {
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
            long seed) {
        if (!enabled) {
            return report(
                    "IR_CALL_INDIRECTION",
                    "SKIPPED",
                    "PROTECTION_PASS_DISABLED",
                    List.of(),
                    List.of(),
                    seed);
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
                    seed);
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
                    seed);
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
                seed);
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
            long seed) {
        if (!enabled) {
            return report(
                    "METHOD_INLINING",
                    "SKIPPED",
                    "PROTECTION_PASS_DISABLED",
                    plan.candidates().stream().map(MethodInliningCandidate::callerMethodKey).toList(),
                    List.of(),
                    seed);
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
                    seed);
        }
        if (!inlined.isEmpty()) {
            return report(
                    "METHOD_INLINING",
                    "RAN",
                    MethodInliningReason.INLINED,
                    inlined.stream().map(MethodInliningDecision::callerMethodKey).toList(),
                    List.of(),
                    seed);
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
                seed);
    }

    private ProtectionPassReport splittingReport(
            boolean enabled,
            List<MethodSplittingResult> decisions,
            long seed) {
        if (!enabled) {
            return report(
                    "METHOD_SPLITTING",
                    "SKIPPED",
                    "PROTECTION_PASS_DISABLED",
                    decisions.stream().map(result -> result.caller().methodKey()).toList(),
                    List.of(),
                    seed);
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
                    seed);
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
                    seed);
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
                seed);
    }

    private ProtectionPassReport report(
            String pass,
            String status,
            String reason,
            List<String> methods,
            List<String> symbols,
            long seed) {
        return new ProtectionPassReport(
                pass,
                "IR",
                status,
                reason,
                methods,
                symbols,
                Long.toString(seed));
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
