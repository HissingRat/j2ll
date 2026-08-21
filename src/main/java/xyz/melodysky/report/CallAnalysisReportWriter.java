package xyz.melodysky.report;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import xyz.melodysky.analysis.callgraph.CallResolution;
import xyz.melodysky.analysis.hierarchy.AnalysisWorld;
import xyz.melodysky.pipeline.ProgramCallGraphAnalysis;

/** Serializes authoritative entry-rooted call-target decisions by exact call-site id. */
final class CallAnalysisReportWriter {
    JsonObject json(
            ProgramCallGraphAnalysis analysis,
            AnalysisWorld world) {
        Objects.requireNonNull(analysis, "analysis");
        Objects.requireNonNull(world, "world");
        JsonObject object = new JsonObject();
        object.addProperty("status", "completed");
        object.addProperty("worldModel", world.name());
        object.addProperty("rtaApplied", analysis.rtaApplied());
        object.addProperty(
                "fixedPointIterations",
                analysis.reachability().fixedPointIterations());
        object.addProperty(
                "entryMethodCount",
                analysis.reachability().entryMethodKeys().size());
        object.addProperty(
                "reachableMethodCount",
                analysis.reachability().reachableMethodKeys().size());
        object.addProperty(
                "unreachableMethodCount",
                analysis.reachability().unreachableMethodKeys().size());
        object.addProperty(
                "instantiatedClassCount",
                analysis.runtimeTypes().instantiatedClasses().size());
        object.addProperty(
                "runtimeTypesConservative",
                analysis.runtimeTypes().conservative());
        object.addProperty("callSiteCount", analysis.callGraph().resolutions().size());
        object.addProperty(
                "directCallSiteCount",
                analysis.devirtualizationPlan().decisions().stream()
                        .filter(decision -> decision.directTarget().isPresent())
                        .count());
        object.addProperty(
                "jvmDispatchCallSiteCount",
                analysis.devirtualizationPlan().decisions().stream()
                        .filter(decision -> decision.jvmDispatchRequired())
                        .count());
        object.addProperty(
                "unknownTargetCallSiteCount",
                analysis.callGraph().resolutions().stream()
                        .filter(CallResolution::hasUnknownTarget)
                        .count());
        object.add("entryMethods", strings(analysis.reachability().entryMethodKeys()));
        object.add("reachableMethods", strings(analysis.reachability().reachableMethodKeys()));

        Map<String, CallResolution> resolutions = new LinkedHashMap<>();
        analysis.callGraph().resolutions().forEach(resolution ->
                resolutions.put(resolution.callSite().id(), resolution));
        JsonArray decisions = new JsonArray();
        analysis.devirtualizationPlan().decisions().forEach(decision -> {
            CallResolution resolution = Objects.requireNonNull(
                    resolutions.get(decision.callSiteId()),
                    "missing call resolution for " + decision.callSiteId());
            var site = resolution.callSite();
            String caller = site.callerOwner() + "#" + site.caller().name()
                    + "!" + site.caller().descriptor();
            JsonObject entry = new JsonObject();
            entry.addProperty("callSiteId", site.id());
            entry.addProperty("caller", caller);
            entry.addProperty("instructionIndex", site.instructionIndex());
            entry.addProperty("invokeKind", site.kind().name());
            entry.addProperty(
                    "declaredTarget",
                    site.declaredOwner() + "#" + site.declaredTarget().name()
                            + "!" + site.declaredTarget().descriptor());
            entry.addProperty(
                    "callerReachable",
                    analysis.reachability().reachableMethodKeys().contains(caller));
            entry.add("resolvedTargets", strings(resolution.targets().stream()
                    .map(target -> target.displayName())
                    .toList()));
            if (decision.directTarget().isPresent()) {
                entry.addProperty(
                        "directTarget",
                        decision.directTarget().orElseThrow().displayName());
            } else {
                entry.add("directTarget", JsonNull.INSTANCE);
            }
            entry.addProperty(
                    "directNativeTargetUnavailable",
                    decision.directNativeTargetUnavailable());
            entry.addProperty("jvmDispatchRequired", decision.jvmDispatchRequired());
            entry.addProperty("resolutionReason", resolution.reason());
            entry.addProperty("decisionReason", decision.reason());
            decisions.add(entry);
        });
        object.add("decisions", decisions);
        return object;
    }

    private JsonArray strings(java.util.Collection<String> values) {
        JsonArray array = new JsonArray();
        values.stream().sorted().forEach(array::add);
        return array;
    }
}
