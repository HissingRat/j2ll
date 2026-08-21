package xyz.melodysky.toolchain;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import xyz.melodysky.backend.llvm.LlvmNameMangler;
import xyz.melodysky.ir.model.BusinessStringSymbolMapper;
import xyz.melodysky.ir.model.IrInstruction;
import xyz.melodysky.ir.model.IrMethod;
import xyz.melodysky.ir.model.IrValue;
import xyz.melodysky.ir.ssa.JvmExceptionInstructionSemantics;
import xyz.melodysky.packaging.MethodRewriteDecision;
import xyz.melodysky.packaging.MethodRewriteStrategy;
import xyz.melodysky.packaging.NativeHelperDescriptor;
import xyz.melodysky.packaging.NativeRegistrationEntry;
import xyz.melodysky.packaging.NativeRegistrationPlan;
import xyz.melodysky.runtime.RuntimeTokenMapper;
import xyz.melodysky.toolchain.initializer.InitializerImplementationPlan;
import xyz.melodysky.toolchain.localref.NativeLocalReferencePlan;
import xyz.melodysky.toolchain.localref.NativeLocalReferencePlanner;
import xyz.melodysky.toolchain.localref.NativeLocalReferencePlanningResult;

public final class NativeImplementationPlanner {
    private final LlvmNameMangler llvmNameMangler;
    private final NativeIrTypeSupport typeSupport = new NativeIrTypeSupport();
    private final NativeLlvmInstructionSupport instructionSupport =
            new NativeLlvmInstructionSupport(typeSupport);
    private final NativeExceptionFlowSupport exceptionFlowSupport = new NativeExceptionFlowSupport();
    private final NativeLocalReferenceSafety localReferenceSafety =
            new NativeLocalReferenceSafety();
    private final NativeLocalReferencePlanner localReferencePlanner =
            new NativeLocalReferencePlanner();
    private final NativeImplementationUnavailableReasonClassifier
            unavailableReasonClassifier =
                    new NativeImplementationUnavailableReasonClassifier();
    private final JvmExceptionInstructionSemantics exceptionSemantics =
            new JvmExceptionInstructionSemantics();
    private final NativeImplementationBodyPlanner bodyPlanner;
    private final NativeDirectCallTargetResolver directCallTargetResolver =
            new NativeDirectCallTargetResolver();
    private final NativeImplementationReasonClassifier reasonClassifier =
            new NativeImplementationReasonClassifier();
    private final NativeImplementationEvidenceCollector evidenceCollector;

    public NativeImplementationPlanner(
            LlvmNameMangler llvmNameMangler,
            BusinessStringSymbolMapper businessStringSymbols,
            RuntimeTokenMapper runtimeTokens) {
        this.llvmNameMangler = java.util.Objects.requireNonNull(
                llvmNameMangler,
                "llvmNameMangler");
        BusinessStringSymbolMapper checkedBusinessStringSymbols = java.util.Objects.requireNonNull(
                businessStringSymbols,
                "businessStringSymbols");
        this.bodyPlanner = new NativeImplementationBodyPlanner(
                java.util.Objects.requireNonNull(runtimeTokens, "runtimeTokens"));
        this.evidenceCollector = new NativeImplementationEvidenceCollector(
                checkedBusinessStringSymbols,
                instructionSupport::matchesEvidenceKind);
    }

    public NativeImplementationPlan plan(
            NativeRegistrationPlan registrationPlan,
            List<MethodRewriteDecision> decisions,
            Map<String, IrMethod> irMethods) {
        return plan(
                registrationPlan,
                decisions,
                irMethods,
                irMethods.keySet(),
                Set.of());
    }

    public NativeImplementationPlan plan(
            NativeRegistrationPlan registrationPlan,
            List<MethodRewriteDecision> decisions,
            Map<String, IrMethod> irMethods,
            Set<String> availableProgramMethodKeys) {
        return plan(
                registrationPlan,
                decisions,
                irMethods,
                availableProgramMethodKeys,
                Set.of());
    }

    public NativeImplementationPlan plan(
            NativeRegistrationPlan registrationPlan,
            List<MethodRewriteDecision> decisions,
            Map<String, IrMethod> irMethods,
            Set<String> availableProgramMethodKeys,
            Set<String> compilerInternalMethodKeys) {
        return plan(
                registrationPlan,
                decisions,
                irMethods,
                availableProgramMethodKeys,
                compilerInternalMethodKeys,
                Map.of());
    }

    public NativeImplementationPlan plan(
            NativeRegistrationPlan registrationPlan,
            List<MethodRewriteDecision> decisions,
            Map<String, IrMethod> irMethods,
            Set<String> availableProgramMethodKeys,
            Set<String> compilerInternalMethodKeys,
            Map<String, InitializerImplementationPlan> preparedInitializerPlans) {
        ArrayList<NativeMethodImplementation> implementations = new ArrayList<>();
        Map<String, NativeRegistrationEntry> entriesByMethod = new LinkedHashMap<>();
        Map<String, MethodRewriteDecision> decisionsByMethod = new LinkedHashMap<>();
        for (NativeRegistrationEntry entry : registrationPlan.entries()) {
            Optional<MethodRewriteDecision> maybeDecision = decisionFor(entry, decisions);
            if (maybeDecision.isEmpty()) {
                continue;
            }
            MethodRewriteDecision decision = maybeDecision.orElseThrow();
            if (decision.strategy() == MethodRewriteStrategy.NOT_APPLICABLE) {
                continue;
            }
            entriesByMethod.put(decision.method().methodKey(), entry);
            decisionsByMethod.put(decision.method().methodKey(), decision);
        }
        Map<String, InitializerImplementationPlan> initializerPlans = new LinkedHashMap<>();
        Map<String, IrMethod> nativeBodies = bodyPlanner.nativeBodies(
                decisionsByMethod,
                irMethods,
                initializerPlans,
                preparedInitializerPlans);
        LinkedHashMap<String, IrMethod> analysisBodies =
                new LinkedHashMap<>(nativeBodies);
        compilerInternalMethodKeys.stream()
                .sorted()
                .forEach(methodKey -> {
                    IrMethod method = irMethods.get(methodKey);
                    if (method != null) {
                        analysisBodies.put(methodKey, method);
                    }
                });
        LinkedHashMap<String, NativeLocalReferencePlanningResult>
                localReferenceResults =
                        localReferenceResults(analysisBodies);
        LinkedHashSet<String> unsafeMethodKeys = localReferenceResults
                .entrySet()
                .stream()
                .filter(entry -> entry.getValue().plan().isEmpty())
                .map(Map.Entry::getKey)
                .collect(java.util.stream.Collectors.toCollection(
                        LinkedHashSet::new));
        LinkedHashSet<String> jvmBridgeMethodKeys =
                new LinkedHashSet<>(availableProgramMethodKeys);
        jvmBridgeMethodKeys.removeAll(compilerInternalMethodKeys);
        LinkedHashSet<String> supportedLlvmMethods =
                supportedLlvmMethods(
                        decisionsByMethod,
                        analysisBodies,
                        jvmBridgeMethodKeys,
                        compilerInternalMethodKeys,
                        unsafeMethodKeys);
        LinkedHashMap<String, String> unavailableReasonCodes =
                new LinkedHashMap<>();
        decisionsByMethod.keySet().stream()
                .filter(unsafeMethodKeys::contains)
                .forEach(methodKey -> unavailableReasonCodes.put(
                        methodKey,
                        NativeLocalReferenceSafety.UNBOUNDED_REASON_CODE));
        decisionsByMethod.keySet().stream()
                .filter(methodKey ->
                        !supportedLlvmMethods.contains(methodKey))
                .filter(methodKey ->
                        !unavailableReasonCodes.containsKey(methodKey))
                .forEach(methodKey -> Optional.ofNullable(
                                analysisBodies.get(methodKey))
                        .flatMap(unavailableReasonClassifier::classify)
                        .ifPresent(reasonCode -> unavailableReasonCodes.put(
                                methodKey,
                                reasonCode)));
        LinkedHashMap<String, NativeLocalReferencePlan>
                localReferencePlans = new LinkedHashMap<>();
        localReferenceResults.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> entry.getValue().plan().ifPresent(
                        plan -> localReferencePlans.put(
                                entry.getKey(),
                                plan)));
        for (Map.Entry<String, MethodRewriteDecision> planned : decisionsByMethod.entrySet()) {
            NativeRegistrationEntry entry = entriesByMethod.get(planned.getKey());
            MethodRewriteDecision decision = planned.getValue();
            Optional<IrMethod> maybeIr = Optional.ofNullable(nativeBodies.get(decision.method().methodKey()));
            if (maybeIr.isPresent() && supportedLlvmMethods.contains(decision.method().methodKey())) {
                IrMethod irMethod = maybeIr.orElseThrow();
                List<String> directCallTargets = directCallTargetResolver.directTargets(
                        irMethod,
                        supportedLlvmMethods,
                        analysisBodies,
                        compilerInternalMethodKeys);
                NativeMethodImplementationEvidence evidence = evidenceCollector.collect(
                        irMethod,
                        directCallTargets,
                        jvmBridgeMethodKeys);
                implementations.add(new NativeMethodImplementation(
                        entry,
                        decision,
                        NativeImplementationPath.LLVM_NATIVE_PATH,
                        Optional.of(llvmNameMangler.functionName(irMethod)),
                        initializerReasonCode(decision).orElseGet(() ->
                                reasonClassifier.classify(
                                        evidence.reasonFacts(
                                                decision.method().accessFlags()
                                                        .isSynchronized()))),
                        evidence.passesJniEnv(),
                        evidence.passesOwnerClass(),
                        evidence.fieldKeys(),
                        evidence.directCallTargets(),
                        evidence.allocationKeys(),
                        evidence.typeCheckKeys(),
                        evidence.classObjectKeys(),
                        evidence.runtimeMetadataKeys(),
                        evidence.constructorCallKeys(),
                        evidence.staticCallKeys(),
                        evidence.dispatchKeys(),
                        evidence.stringHelperSymbols(),
                        Optional.of(irMethod),
                        Optional.ofNullable(initializerPlans.get(decision.method().methodKey()))));
            }
        }
        return new NativeImplementationPlan(
                implementations,
                unavailableReasonCodes,
                localReferencePlans);
    }

    private LinkedHashMap<String, NativeLocalReferencePlanningResult>
            localReferenceResults(Map<String, IrMethod> nativeBodies) {
        LinkedHashMap<String, NativeLocalReferencePlanningResult> results =
                new LinkedHashMap<>();
        nativeBodies.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .filter(entry -> localReferenceSafety
                        .hasUnboundedLocalReferenceRisk(entry.getValue()))
                .forEach(entry -> results.put(
                        entry.getKey(),
                        localReferencePlanner.plan(entry.getValue())));
        return results;
    }

    private LinkedHashSet<String> supportedLlvmMethods(
            Map<String, MethodRewriteDecision> decisionsByMethod,
            Map<String, IrMethod> nativeBodies,
            Set<String> availableProgramMethodKeys,
            Set<String> compilerInternalMethodKeys,
            Set<String> excludedMethodKeys) {
        LinkedHashSet<String> supportedLlvmMethods =
                new LinkedHashSet<>();
        compilerInternalMethodKeys.stream()
                .filter(methodKey ->
                        !excludedMethodKeys.contains(methodKey))
                .forEach(supportedLlvmMethods::add);
        boolean changed;
        do {
            changed = false;
            for (Map.Entry<String, MethodRewriteDecision> entry :
                    decisionsByMethod.entrySet()) {
                if (supportedLlvmMethods.contains(entry.getKey())
                        || excludedMethodKeys.contains(entry.getKey())) {
                    continue;
                }
                IrMethod irMethod = nativeBodies.get(entry.getKey());
                if (irMethod != null && supportsLlvmNativeBody(
                        entry.getValue(),
                        irMethod,
                        directCallTargetResolver.sameOwnerTargets(
                                irMethod,
                                supportedLlvmMethods,
                                nativeBodies,
                                compilerInternalMethodKeys),
                        availableProgramMethodKeys)) {
                    supportedLlvmMethods.add(entry.getKey());
                    changed = true;
                }
            }
        } while (changed);
        return supportedLlvmMethods;
    }

    private Optional<String> initializerReasonCode(MethodRewriteDecision decision) {
        if (decision.strategy() == MethodRewriteStrategy.CONSTRUCTOR_STUB) {
            return Optional.of("LLVM_CONSTRUCTOR_SPLIT_BODY_IR");
        }
        if (decision.strategy() == MethodRewriteStrategy.CLASS_INITIALIZER_STUB) {
            return Optional.of("LLVM_CLASS_INITIALIZER_BODY_IR");
        }
        return Optional.empty();
    }

    public boolean supportsLlvmNativePath(MethodRewriteDecision decision, IrMethod method) {
        if (decision.strategy() == MethodRewriteStrategy.CONSTRUCTOR_STUB
                || decision.strategy() == MethodRewriteStrategy.CLASS_INITIALIZER_STUB) {
            return bodyPlanner.initializerPlan(decision, method)
                    .map(InitializerImplementationPlan::nativeBody)
                    .map(body -> supportsLlvmNativeBody(decision, body, Set.of(), Set.of()))
                    .orElse(false);
        }
        return supportsLlvmNativeBody(decision, method, Set.of(), Set.of());
    }

    private boolean supportsLlvmNativeBody(
            MethodRewriteDecision decision,
            IrMethod method,
            Set<String> directCallTargets,
            Set<String> availableProgramMethods) {
        if (decision.method().accessFlags().isSynchronized()
                && !instructionSupport.hasMonitorHelper(method)) {
            return false;
        }
        if (exceptionFlowSupport.hasUnsupportedJvmFlow(method)) {
            return false;
        }
        if (!typeSupport.supportsJvmHostedDescriptor(
                decision.method().descriptor())) {
            return false;
        }
        if (!typeSupport.isSupportedReturnType(method.returnType())) {
            return false;
        }
        if (!typeSupport.supportsParameters(decision, method)) {
            return false;
        }
        if (method.blocks().isEmpty()) {
            return false;
        }
        if (localReferenceSafety.hasUnboundedLocalReferenceRisk(method)
                && localReferencePlanner.plan(method).plan().isEmpty()) {
            return false;
        }
        for (var block : method.blocks()) {
            if (block.parameters().stream().map(IrValue::type).anyMatch(
                    type -> !typeSupport.isSupportedValueType(type))) {
                return false;
            }
            for (IrInstruction instruction : block.instructions()) {
                if (!instruction.exceptionSites().isEmpty()
                        && !exceptionSemantics.canRaiseJvmException(instruction)) {
                    return false;
                }
                if (!instructionSupport.supports(
                        instruction,
                        directCallTargets,
                        availableProgramMethods)) {
                    return false;
                }
            }
            if (!typeSupport.supportsTerminator(block.terminator())) {
                return false;
            }
        }
        return true;
    }

    private Optional<MethodRewriteDecision> decisionFor(
            NativeRegistrationEntry entry,
            List<MethodRewriteDecision> decisions) {
        return decisions.stream()
                .filter(decision -> decision.registrationOwner().equals(entry.registrationOwner()))
                .filter(decision -> decision.generatedHelperName().orElse(decision.method().name()).equals(entry.methodName()))
                .filter(decision -> NativeHelperDescriptor.forDecision(decision).equals(entry.descriptor()))
                .findFirst();
    }

}
