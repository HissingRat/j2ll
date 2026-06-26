package xyz.melodysky.analysis.runtime;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import xyz.melodysky.analysis.callgraph.CallResolution;
import xyz.melodysky.analysis.callgraph.CallSite;
import xyz.melodysky.analysis.callgraph.CallTarget;
import xyz.melodysky.analysis.callgraph.InvokeKind;
import xyz.melodysky.analysis.hierarchy.ClassHierarchy;

public final class RtaCallResolver {
    private final ClassHierarchy hierarchy;
    private final RuntimeTypeResult runtimeTypes;

    public RtaCallResolver(ClassHierarchy hierarchy, RuntimeTypeResult runtimeTypes) {
        this.hierarchy = hierarchy;
        this.runtimeTypes = runtimeTypes;
    }

    public CallResolution refine(CallResolution chaResolution) {
        CallSite callSite = chaResolution.callSite();
        if (!callSite.kind().dispatchesDynamically()) {
            return chaResolution;
        }
        if (runtimeTypes.conservative()) {
            ArrayList<CallTarget> targets = new ArrayList<>(chaResolution.targets());
            targets.add(CallTarget.unknownExternal("RTA_UNKNOWN_ALLOCATION"));
            return new CallResolution(callSite, targets, true, "RTA_CONSERVATIVE");
        }

        LinkedHashSet<CallTarget> targets = new LinkedHashSet<>();
        for (String runtimeType : runtimeTypes.instantiatedClasses()) {
            if (callSite.kind() == InvokeKind.INTERFACE) {
                if (!hierarchy.implementorsOf(callSite.declaredOwner()).contains(runtimeType)) {
                    continue;
                }
            } else if (!hierarchy.subtypesOf(callSite.declaredOwner()).contains(runtimeType)
                    && !callSite.declaredOwner().equals(runtimeType)) {
                continue;
            }
            hierarchy.resolveVirtualMethod(runtimeType, callSite.declaredTarget())
                    .ifPresent(method -> targets.add(CallTarget.known(method.owner(), method.signature())));
        }

        if (targets.isEmpty()) {
            return new CallResolution(
                    callSite,
                    List.of(CallTarget.unknownExternal("RTA_NO_INSTANTIATED_TARGET")),
                    true,
                    "RTA_NO_TARGET");
        }
        return new CallResolution(callSite, List.copyOf(targets), false, "RTA_REFINED");
    }
}
