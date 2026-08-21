package xyz.melodysky.report;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

/** Fail-closed readiness validation for authoritative exact call-site evidence. */
final class CallAnalysisReportGate {
    ReleaseReadinessCheck verify(Path loweringReport) {
        if (!Files.isRegularFile(loweringReport)) {
            return failed("lowering-report.json is missing");
        }
        try {
            JsonObject root = JsonParser.parseString(
                            Files.readString(loweringReport))
                    .getAsJsonObject();
            if (!root.has("callAnalysis")
                    || !root.get("callAnalysis").isJsonObject()) {
                return failed("lowering report has no callAnalysis object");
            }
            JsonObject analysis = root.getAsJsonObject("callAnalysis");
            if (!text(analysis, "status").equals("completed")) {
                return failed("callAnalysis status is not completed");
            }
            for (String field : Set.of(
                    "worldModel",
                    "rtaApplied",
                    "fixedPointIterations",
                    "entryMethodCount",
                    "reachableMethodCount",
                    "unreachableMethodCount",
                    "instantiatedClassCount",
                    "runtimeTypesConservative",
                    "callSiteCount",
                    "directCallSiteCount",
                    "jvmDispatchCallSiteCount",
                    "unknownTargetCallSiteCount")) {
                if (!analysis.has(field) || analysis.get(field).isJsonNull()) {
                    return failed("callAnalysis is missing " + field);
                }
            }
            JsonArray entries = array(analysis, "entryMethods");
            JsonArray reachable = array(analysis, "reachableMethods");
            JsonArray decisions = array(analysis, "decisions");
            if (analysis.get("entryMethodCount").getAsInt() != entries.size()
                    || analysis.get("reachableMethodCount").getAsInt() != reachable.size()
                    || analysis.get("callSiteCount").getAsInt() != decisions.size()) {
                return failed("callAnalysis summary counts do not match their arrays");
            }
            Set<String> ids = new HashSet<>();
            int direct = 0;
            int dispatch = 0;
            for (var element : decisions) {
                JsonObject decision = element.getAsJsonObject();
                String id = text(decision, "callSiteId");
                if (id.isBlank() || !ids.add(id)) {
                    return failed("callAnalysis contains a blank or duplicate callSiteId");
                }
                for (String field : Set.of(
                        "caller",
                        "instructionIndex",
                        "invokeKind",
                        "declaredTarget",
                        "callerReachable",
                        "resolvedTargets",
                        "directNativeTargetUnavailable",
                        "jvmDispatchRequired",
                        "resolutionReason",
                        "decisionReason")) {
                    if (!decision.has(field) || decision.get(field).isJsonNull()) {
                        return failed("call-site " + id + " is missing " + field);
                    }
                }
                if (decision.has("directTarget")
                        && !decision.get("directTarget").isJsonNull()) {
                    direct++;
                }
                if (decision.get("jvmDispatchRequired").getAsBoolean()) {
                    dispatch++;
                }
            }
            if (analysis.get("directCallSiteCount").getAsInt() != direct
                    || analysis.get("jvmDispatchCallSiteCount").getAsInt() != dispatch) {
                return failed("callAnalysis decision counts do not match exact call sites");
            }
            return ReleaseReadinessCheck.passed(
                    "callAnalysis.decisions",
                    "CALL_ANALYSIS_REPORTED",
                    "entry-rooted call analysis and exact call-site decisions are complete");
        } catch (IOException | RuntimeException exception) {
            return failed("callAnalysis is unreadable: " + exception.getMessage());
        }
    }

    private JsonArray array(JsonObject object, String field) {
        if (!object.has(field) || !object.get(field).isJsonArray()) {
            throw new IllegalArgumentException("missing array " + field);
        }
        return object.getAsJsonArray(field);
    }

    private String text(JsonObject object, String field) {
        if (!object.has(field) || object.get(field).isJsonNull()) {
            return "";
        }
        return object.get(field).getAsString();
    }

    private ReleaseReadinessCheck failed(String detail) {
        return ReleaseReadinessCheck.failed(
                "callAnalysis.decisions",
                "CALL_ANALYSIS_MISSING_OR_INVALID",
                detail);
    }
}
