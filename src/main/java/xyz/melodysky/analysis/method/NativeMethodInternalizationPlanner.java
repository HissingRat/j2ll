package xyz.melodysky.analysis.method;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.objectweb.asm.Opcodes;
import xyz.melodysky.analysis.callgraph.CallGraph;
import xyz.melodysky.analysis.callgraph.InvokeKind;
import xyz.melodysky.analysis.hierarchy.ClassHierarchy;
import xyz.melodysky.analysis.reflection.ReflectionPlan;
import xyz.melodysky.analysis.world.WholeProgramAnalysisScope;
import xyz.melodysky.frontend.classfile.ParsedMethod;
import xyz.melodysky.frontend.classfile.ParsedProgram;
import xyz.melodysky.packaging.MethodRewriteStrategy;
import xyz.melodysky.toolchain.NativeImplementationPath;
import xyz.melodysky.toolchain.NativeImplementationPlan;
import xyz.melodysky.toolchain.NativeMethodImplementation;

public final class NativeMethodInternalizationPlanner {
    private final KnownJvmCallbackObserver callbackObserver =
            new KnownJvmCallbackObserver();

    public NativeMethodInternalizationPlan plan(
            boolean enabled,
            WholeProgramAnalysisScope analysisScope,
            ParsedProgram program,
            ClassHierarchy hierarchy,
            CallGraph callGraph,
            ReflectionPlan reflectionPlan,
            Set<String> versionedClassNames,
            NativeImplementationPlan implementationPlan) {
        return plan(
                enabled,
                analysisScope,
                program,
                List.of(),
                hierarchy,
                callGraph,
                reflectionPlan,
                versionedClassNames,
                implementationPlan,
                Set.of());
    }

    public NativeMethodInternalizationPlan plan(
            boolean enabled,
            WholeProgramAnalysisScope analysisScope,
            ParsedProgram program,
            ClassHierarchy hierarchy,
            CallGraph callGraph,
            ReflectionPlan reflectionPlan,
            Set<String> versionedClassNames,
            NativeImplementationPlan implementationPlan,
            Set<NativeMethodId> publicMethodAllowlist) {
        return plan(
                enabled,
                analysisScope,
                program,
                List.of(),
                hierarchy,
                callGraph,
                reflectionPlan,
                versionedClassNames,
                implementationPlan,
                publicMethodAllowlist);
    }

    public NativeMethodInternalizationPlan plan(
            boolean enabled,
            WholeProgramAnalysisScope analysisScope,
            ParsedProgram program,
            List<ParsedProgram> externalObservers,
            ClassHierarchy hierarchy,
            CallGraph callGraph,
            ReflectionPlan reflectionPlan,
            Set<String> versionedClassNames,
            NativeImplementationPlan implementationPlan) {
        return plan(
                enabled,
                analysisScope,
                program,
                externalObservers,
                hierarchy,
                callGraph,
                reflectionPlan,
                versionedClassNames,
                implementationPlan,
                Set.of());
    }

    public NativeMethodInternalizationPlan plan(
            boolean enabled,
            WholeProgramAnalysisScope analysisScope,
            ParsedProgram program,
            List<ParsedProgram> externalObservers,
            ClassHierarchy hierarchy,
            CallGraph callGraph,
            ReflectionPlan reflectionPlan,
            Set<String> versionedClassNames,
            NativeImplementationPlan implementationPlan,
            Set<NativeMethodId> publicMethodAllowlist) {
        Objects.requireNonNull(analysisScope, "analysisScope");
        Objects.requireNonNull(program, "program");
        Objects.requireNonNull(hierarchy, "hierarchy");
        Objects.requireNonNull(callGraph, "callGraph");
        Objects.requireNonNull(reflectionPlan, "reflectionPlan");
        Objects.requireNonNull(versionedClassNames, "versionedClassNames");
        Objects.requireNonNull(implementationPlan, "implementationPlan");
        Set<NativeMethodId> stablePublicMethodAllowlist = Set.copyOf(
                Objects.requireNonNull(
                publicMethodAllowlist,
                "publicMethodAllowlist"));
        if (!enabled) {
            return NativeMethodInternalizationPlan.disabled();
        }

        Map<String, NativeMethodImplementation> implementations =
                implementationPlan.implementations().stream()
                        .collect(java.util.stream.Collectors.toMap(
                                NativeMethodImplementation::methodKey,
                                value -> value,
                                (left, right) -> left,
                                LinkedHashMap::new));
        Set<NativeMethodId> candidates = implementations.values().stream()
                .map(implementation ->
                        NativeMethodId.fromMethodKey(
                                implementation.methodKey()))
                .collect(java.util.stream.Collectors.toCollection(
                        LinkedHashSet::new));
        NativeMethodUseIndex uses = new NativeMethodUseAnalyzer().analyze(
                program,
                externalObservers,
                callGraph,
                reflectionPlan,
                candidates);
        ArrayList<NativeMethodInternalizationDecision> decisions =
                new ArrayList<>();
        implementations.values().stream()
                .sorted()
                .forEach(implementation -> decisions.add(decide(
                        implementation,
                        implementations,
                        hierarchy,
                        uses,
                        analysisScope,
                        versionedClassNames,
                        stablePublicMethodAllowlist)));
        return new NativeMethodInternalizationPlan(
                true,
                analysisScope,
                decisions);
    }

    private NativeMethodInternalizationDecision decide(
            NativeMethodImplementation implementation,
            Map<String, NativeMethodImplementation> implementations,
            ClassHierarchy hierarchy,
            NativeMethodUseIndex uses,
            WholeProgramAnalysisScope analysisScope,
            Set<String> versionedClassNames,
            Set<NativeMethodId> publicMethodAllowlist) {
        ParsedMethod method = implementation.decision().method();
        NativeMethodId id = new NativeMethodId(
                method.owner(),
                method.name(),
                method.descriptor());
        LinkedHashSet<NativeMethodInternalizationReason> reasons =
                new LinkedHashSet<>();
        if (!analysisScope.permitsWholeProgramTransform()) {
            reasons.add(NativeMethodInternalizationReason
                    .METHOD_INTERNALIZATION_WORLD_NOT_AUTHORIZED);
        }
        if (implementation.path()
                != NativeImplementationPath.LLVM_NATIVE_PATH) {
            reasons.add(NativeMethodInternalizationReason
                    .METHOD_INTERNALIZATION_FINAL_PATH_NOT_LLVM);
        }
        if (implementation.decision().strategy()
                != MethodRewriteStrategy.NATIVE_ORIGINAL
                || !method.hasCode()
                || method.name().equals("<init>")
                || method.name().equals("<clinit>")
                || method.accessFlags().isNative()
                || method.accessFlags().isAbstract()
                || method.accessFlags().isSynchronized()
                || method.accessFlags().has(Opcodes.ACC_BRIDGE)
                || method.accessFlags().isSynthetic()) {
            reasons.add(NativeMethodInternalizationReason
                    .METHOD_INTERNALIZATION_METHOD_KIND_UNSUPPORTED);
        }
        boolean publicMethod = method.accessFlags().isPublic();
        if (!method.accessFlags().isPrivate()
                && !method.accessFlags().isProtected()
                && !publicMethod) {
            reasons.add(NativeMethodInternalizationReason
                    .METHOD_INTERNALIZATION_METHOD_ACCESS_UNSUPPORTED);
        }
        if (publicMethod) {
            evaluatePublicMethod(
                    method,
                    id,
                    analysisScope,
                    hierarchy,
                    publicMethodAllowlist,
                    reasons);
        }
        if (versionedClassNames.contains(method.owner())) {
            reasons.add(NativeMethodInternalizationReason
                    .METHOD_INTERNALIZATION_MULTI_RELEASE_OWNER);
        }
        if (uses.methodHandleReferences().contains(id)) {
            reasons.add(NativeMethodInternalizationReason
                    .METHOD_INTERNALIZATION_METHOD_HANDLE_REFERENCE);
        }
        if (uses.reflectionObservers().contains(id)) {
            reasons.add(NativeMethodInternalizationReason
                    .METHOD_INTERNALIZATION_REFLECTION_OBSERVER);
        }
        if (uses.enclosingMethodReferences().contains(id)) {
            reasons.add(NativeMethodInternalizationReason
                    .METHOD_INTERNALIZATION_ENCLOSING_METHOD_REFERENCE);
        }
        if (callbackObserver.observedContract(method, hierarchy).isPresent()) {
            reasons.add(NativeMethodInternalizationReason
                    .METHOD_INTERNALIZATION_KNOWN_JVM_CALLBACK_ENTRY);
        }

        List<NativeMethodCallUse> incoming = uses.incomingCalls(id);
        if (incoming.isEmpty()) {
            reasons.add(NativeMethodInternalizationReason
                    .METHOD_INTERNALIZATION_NO_NATIVE_CALLER);
        }
        for (NativeMethodCallUse use : incoming) {
            evaluateCall(
                    method,
                    use,
                    implementations,
                    hierarchy,
                    analysisScope,
                    reasons);
        }
        boolean approved = reasons.isEmpty();
        List<NativeMethodInternalizationReason> finalReasons = approved
                ? List.of(NativeMethodInternalizationReason
                        .METHOD_INTERNALIZATION_ELIGIBLE)
                : reasons.stream().sorted().toList();
        return new NativeMethodInternalizationDecision(
                id,
                approved
                        ? NativeMethodInternalizationStatus.INTERNALIZED
                        : NativeMethodInternalizationStatus.KEPT,
                method.accessFlags().isStatic(),
                method.accessFlags().isPrivate()
                        ? "private"
                        : method.accessFlags().isProtected()
                                ? "protected"
                                : method.accessFlags().isPublic()
                                        ? "public"
                                : "other",
                incoming.stream()
                        .map(NativeMethodCallUse::callerMethodKey)
                        .distinct()
                        .sorted()
                        .toList(),
                finalReasons);
    }

    private void evaluatePublicMethod(
            ParsedMethod method,
            NativeMethodId id,
            WholeProgramAnalysisScope analysisScope,
            ClassHierarchy hierarchy,
            Set<NativeMethodId> publicMethodAllowlist,
            Set<NativeMethodInternalizationReason> reasons) {
        if (!publicMethodAllowlist.contains(id)) {
            reasons.add(NativeMethodInternalizationReason
                    .METHOD_INTERNALIZATION_PUBLIC_NOT_ALLOWLISTED);
        }
        if (!method.accessFlags().isStatic()
                && analysisScope
                        != WholeProgramAnalysisScope.DECLARED_CLOSED_WORLD) {
            reasons.add(NativeMethodInternalizationReason
                    .METHOD_INTERNALIZATION_PUBLIC_INSTANCE_REQUIRES_DECLARED_CLOSED_WORLD);
        }
        if (!method.accessFlags().isStatic()
                && analysisScope
                        == WholeProgramAnalysisScope.DECLARED_CLOSED_WORLD
                && !hierarchy.isComplete()) {
            reasons.add(NativeMethodInternalizationReason
                    .METHOD_INTERNALIZATION_PUBLIC_INSTANCE_ANALYSIS_WORLD_INCOMPLETE);
        }
        if (isExternalEntryPoint(method)) {
            reasons.add(NativeMethodInternalizationReason
                    .METHOD_INTERNALIZATION_PUBLIC_EXTERNAL_ENTRY_POINT);
        }
        if (hierarchy.lookupClass(method.owner())
                .map(type -> type.isInterface())
                .orElse(true)) {
            reasons.add(NativeMethodInternalizationReason
                    .METHOD_INTERNALIZATION_PUBLIC_INTERFACE_METHOD);
        }
    }

    private boolean isExternalEntryPoint(ParsedMethod method) {
        return method.accessFlags().isStatic()
                && (method.name().equals("main")
                        || method.name().equals("premain")
                        || method.name().equals("agentmain"));
    }

    private void evaluateCall(
            ParsedMethod method,
            NativeMethodCallUse use,
            Map<String, NativeMethodImplementation> implementations,
            ClassHierarchy hierarchy,
            WholeProgramAnalysisScope analysisScope,
            Set<NativeMethodInternalizationReason> reasons) {
        NativeMethodImplementation caller =
                implementations.get(use.callerMethodKey());
        if (caller == null) {
            reasons.add(NativeMethodInternalizationReason
                    .METHOD_INTERNALIZATION_CALLER_NOT_NATIVE_LOWERED);
            return;
        }
        if (caller.path() != NativeImplementationPath.LLVM_NATIVE_PATH) {
            reasons.add(NativeMethodInternalizationReason
                    .METHOD_INTERNALIZATION_CALLER_PATH_NOT_LLVM);
        }
        String methodKey = method.methodKey();
        if (method.accessFlags().isStatic()) {
            if (use.invokeKind() != InvokeKind.STATIC) {
                reasons.add(NativeMethodInternalizationReason
                        .METHOD_INTERNALIZATION_INVOKE_KIND_UNSUPPORTED);
            }
            if (!caller.staticCallKeys().contains(methodKey)
                    && !caller.directCallTargets().contains(methodKey)) {
                reasons.add(NativeMethodInternalizationReason
                        .METHOD_INTERNALIZATION_INTERNAL_CALL_PATH_MISSING);
            }
            return;
        }

        String callerOwner = NativeMethodId
                .fromMethodKey(use.callerMethodKey())
                .owner();
        if (!callerOwner.equals(method.owner())) {
            reasons.add(NativeMethodInternalizationReason
                    .METHOD_INTERNALIZATION_CROSS_OWNER_INSTANCE_CALL);
        }
        if (use.invokeKind() == InvokeKind.SPECIAL) {
            if (method.accessFlags().isPublic()
                    && (!use.exactInScopeTarget()
                            || use.hasUnknownExternalTarget())) {
                reasons.add(NativeMethodInternalizationReason
                        .METHOD_INTERNALIZATION_PUBLIC_INSTANCE_TARGET_NOT_EXACT);
            }
            if (!caller.directCallTargets().contains(methodKey)) {
                reasons.add(NativeMethodInternalizationReason
                        .METHOD_INTERNALIZATION_INTERNAL_CALL_PATH_MISSING);
            }
            return;
        }
        if (use.invokeKind() != InvokeKind.VIRTUAL) {
            reasons.add(NativeMethodInternalizationReason
                    .METHOD_INTERNALIZATION_INVOKE_KIND_UNSUPPORTED);
            return;
        }
        boolean exact = use.exactInScopeTarget()
                && (!use.hasUnknownExternalTarget()
                        || analysisScope
                                == WholeProgramAnalysisScope
                                        .CURRENT_JAR_ONLY_USER_APPROVED
                        || hierarchy.isFinalClass(method.owner())
                        || method.accessFlags().isFinal());
        if (!exact) {
            reasons.add(NativeMethodInternalizationReason
                    .METHOD_INTERNALIZATION_VIRTUAL_DISPATCH_NOT_EXACT);
            if (method.accessFlags().isPublic()) {
                reasons.add(NativeMethodInternalizationReason
                        .METHOD_INTERNALIZATION_PUBLIC_INSTANCE_TARGET_NOT_EXACT);
            }
        }
        if (!caller.dispatchKeys().contains(methodKey)) {
            reasons.add(NativeMethodInternalizationReason
                    .METHOD_INTERNALIZATION_INTERNAL_CALL_PATH_MISSING);
        }
    }
}
