package xyz.melodysky.analysis.callgraph;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import xyz.melodysky.analysis.hierarchy.ClassHierarchy;
import xyz.melodysky.analysis.hierarchy.HierarchyClass;
import xyz.melodysky.analysis.hierarchy.HierarchyMethod;
import xyz.melodysky.jvm.MethodSignature;

public final class ChaCallResolver {
    private final ClassHierarchy hierarchy;

    public ChaCallResolver(ClassHierarchy hierarchy) {
        this.hierarchy = hierarchy;
    }

    public CallResolution resolve(CallSite callSite) {
        return switch (callSite.kind()) {
            case STATIC, SPECIAL -> resolveDirect(callSite);
            case VIRTUAL -> resolveVirtual(callSite);
            case INTERFACE -> resolveInterface(callSite);
            case DYNAMIC -> new CallResolution(
                    callSite,
                    List.of(CallTarget.unknownExternal("INVOKEDYNAMIC_REQUIRES_RUNTIME_BOOTSTRAP")),
                    true,
                    "INVOKEDYNAMIC");
        };
    }

    private CallResolution resolveDirect(CallSite callSite) {
        return hierarchy.lookupClass(callSite.declaredOwner())
                .flatMap(owner -> owner.declaresMethod(callSite.declaredTarget()))
                .map(method -> new CallResolution(
                        callSite,
                        List.of(CallTarget.known(method.owner(), method.signature())),
                        false,
                        "DIRECT"))
                .orElseGet(() -> unknown(callSite, "DIRECT_TARGET_NOT_IN_HIERARCHY"));
    }

    private CallResolution resolveVirtual(CallSite callSite) {
        if (hierarchy.lookupClass(callSite.declaredOwner()).filter(HierarchyClass::external).isPresent()) {
            return unknown(callSite, "DECLARED_OWNER_EXTERNAL");
        }

        LinkedHashSet<CallTarget> targets = new LinkedHashSet<>();
        List<String> receiverTypes = new ArrayList<>();
        receiverTypes.add(callSite.declaredOwner());
        boolean closedByFinalFact = hierarchy.isFinalClass(callSite.declaredOwner())
                || hierarchy.isFinalMethod(callSite.declaredOwner(), callSite.declaredTarget());
        if (!closedByFinalFact) {
            receiverTypes.addAll(hierarchy.subtypesOf(callSite.declaredOwner()));
        }

        for (String receiverType : receiverTypes) {
            hierarchy.lookupClass(receiverType)
                    .filter(hierarchyClass -> !hierarchyClass.isInterface())
                    .flatMap(hierarchyClass -> hierarchy.resolveVirtualMethod(receiverType, callSite.declaredTarget()))
                    .ifPresent(method -> targets.add(CallTarget.known(method.owner(), method.signature())));
        }

        return completeOrConservative(callSite, targets, "CHA_VIRTUAL", !closedByFinalFact);
    }

    private CallResolution resolveInterface(CallSite callSite) {
        LinkedHashSet<CallTarget> targets = new LinkedHashSet<>();
        for (String implementor : hierarchy.implementorsOf(callSite.declaredOwner())) {
            hierarchy.resolveVirtualMethod(implementor, callSite.declaredTarget())
                    .ifPresent(method -> targets.add(CallTarget.known(method.owner(), method.signature())));
        }
        hierarchy.lookupClass(callSite.declaredOwner())
                .flatMap(interfaceClass -> interfaceClass.declaresMethod(callSite.declaredTarget()))
                .filter(method -> method.hasCode() && !method.accessFlags().isAbstract())
                .ifPresent(method -> targets.add(CallTarget.known(method.owner(), method.signature())));

        return completeOrConservative(callSite, targets, "CHA_INTERFACE", true);
    }

    private CallResolution completeOrConservative(
            CallSite callSite,
            LinkedHashSet<CallTarget> knownTargets,
            String reason,
            boolean incompleteHierarchyCanAddTargets) {
        ArrayList<CallTarget> targets = new ArrayList<>(knownTargets);
        boolean conservative = incompleteHierarchyCanAddTargets && !hierarchy.isComplete();
        if (conservative) {
            targets.add(CallTarget.unknownExternal("HIERARCHY_INCOMPLETE"));
        }
        if (targets.isEmpty()) {
            targets.add(CallTarget.unknownExternal("NO_KNOWN_TARGET"));
            conservative = true;
        }
        return new CallResolution(callSite, targets, conservative, reason);
    }

    private CallResolution unknown(CallSite callSite, String reason) {
        return new CallResolution(callSite, List.of(CallTarget.unknownExternal(reason)), true, reason);
    }
}
