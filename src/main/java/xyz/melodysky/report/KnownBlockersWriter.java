package xyz.melodysky.report;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.Comparator;
import java.util.List;

public final class KnownBlockersWriter {
    private static final Gson GSON = new GsonBuilder()
            .disableHtmlEscaping()
            .setPrettyPrinting()
            .create();

    public String json() {
        return json(defaultEntries());
    }

    public String json(List<KnownBlockerEntry> entries) {
        JsonObject root = new JsonObject();
        root.addProperty("schemaVersion", 1);
        root.addProperty("reportVersion", 1);
        JsonArray array = new JsonArray();
        entries.stream()
                .sorted(Comparator.comparing(KnownBlockerEntry::id))
                .forEach(entry -> {
                    JsonObject object = new JsonObject();
                    object.addProperty("id", entry.id());
                    object.addProperty("reasonCode", entry.reasonCode());
                    object.addProperty("severity", entry.severity());
                    object.addProperty("targetMilestone", entry.targetMilestone());
                    object.addProperty("currentBehavior", entry.currentBehavior());
                    object.addProperty("reportLocation", entry.reportLocation());
                    object.addProperty("suggestedFuturePath", entry.suggestedFuturePath());
                    array.add(object);
                });
        root.add("blockers", array);
        return GSON.toJson(root) + "\n";
    }

    public List<KnownBlockerEntry> defaultEntries() {
        return List.of(
                future("exception-handler-frame-merge", "UNSUPPORTED_EXCEPTION_STATE_MERGE", "only callers whose throw-site locals or handler frame cannot form a valid SSA transfer are skipped; supported typed, catch-all and finally shapes continue through native exception dispatch", "reports/skipped-method-report.json", "extend the frame lattice and exceptional block-argument merge for the remaining shapes"),
                future("legacy-finally-subroutine", "UNSUPPORTED_FINALLY_SUBROUTINE", "legacy jsr/ret finally subroutines make the selected caller skipped", "reports/skipped-method-report.json", "desugar legacy subroutines before SSA lowering"),
                future("monitor-finally-interaction", "UNSUPPORTED_MONITOR_FINALLY_INTERACTION", "complex monitor/finally shapes whose monitor-exit ownership cannot be proven are skipped; the validated cleanup-and-rethrow subset remains native lowered", "reports/skipped-method-report.json", "prove monitor exit ownership through the remaining exceptional control-flow shapes"),
                future("constructor-preinit-boundary", "NATIVE_IMPLEMENTATION_UNAVAILABLE", "constructors without a unique linear verifier prefix, including the current exception-table boundary, are skipped; validated post-init bodies use a native helper", "reports/skipped-method-report.json", "extend verifier-safe constructor splitting without moving uninitializedThis into native code"),
                future("full-altmetafactory-runtime", "ALT_METAFACTORY_UNSUPPORTED", "unsupported capture/runtime class shapes make the selected caller skipped", "reports/skipped-method-report.json", "implement JVM LambdaMetafactory alt flags helper beyond metadata skeleton"),
                future("generic-methodhandle-interpreter", "METHOD_HANDLE_CHAIN_UNSUPPORTED", "unsupported complex adapter chains make the selected caller skipped", "reports/skipped-method-report.json", "add bounded MethodHandle helper path for selected adapters without native object model"),
                future("methodhandle-permute-adapter", "METHOD_HANDLE_PERMUTE_UNSUPPORTED", "unsupported permuteArguments shapes make the selected caller skipped", "reports/skipped-method-report.json", "add tokenized JVM MethodHandle helper for bounded permuteArguments shapes"),
                future("methodhandle-filter-adapter", "METHOD_HANDLE_FILTER_UNSUPPORTED", "unsupported filterArguments shapes make the selected caller skipped", "reports/skipped-method-report.json", "add tokenized JVM MethodHandle helper for bounded filterArguments shapes"),
                future("methodhandle-fold-adapter", "METHOD_HANDLE_FOLD_UNSUPPORTED", "unsupported foldArguments shapes make the selected caller skipped", "reports/skipped-method-report.json", "add tokenized JVM MethodHandle helper for bounded foldArguments shapes"),
                future("methodhandle-collector-adapter", "METHOD_HANDLE_COLLECTOR_UNSUPPORTED", "unsupported collector/spreader shapes make the selected caller skipped", "reports/skipped-method-report.json", "add explicit collector/spreader policy without native MethodHandle interpreter"),
                future("cross-target-runtime-validation", "CROSS_TARGET_RUNTIME_E2E_PENDING", "managed Zig builds and structurally audits all six targets; non-host JVM loading remains CI evidence", "reports/packaging-report.json", "run child-JVM differential tests on native Windows, Linux and macOS x64/arm64 runners"),
                future("raw-memory-unsafe-varhandle", "UNSAFE_RAW_MEMORY_UNSUPPORTED", "raw/off-heap memory APIs make the selected caller skipped and never map to Java object memory", "reports/skipped-method-report.json", "add explicit off-heap helper policy if product scope requires it"),
                future("dynamic-varhandle-boundary", "VAR_HANDLE_DYNAMIC_UNSUPPORTED", "unsupported dynamic VarHandle shapes make the selected caller skipped", "reports/skipped-method-report.json", "add bounded VarHandle helper support without treating offsets as native layout"),
                future("wait-notify-native-helper", "WAIT_NOTIFY_UNSUPPORTED", "Object.wait/notify monitor-queue shapes make the selected caller skipped", "reports/skipped-method-report.json", "add JVM helper policy without implementing a native thread scheduler"),
                nonGoal("standalone-native-image", "EXPLICIT_NONGOAL_STANDALONE_NATIVE_IMAGE", "j2ll output is always JVM-hosted JAR, not standalone/native-image", "AGENTS.md", "remain explicit non-goal unless product scope changes"),
                nonGoal("native-object-model", "EXPLICIT_NONGOAL_NATIVE_OBJECT_MODEL", "Java object identity, layout and lifetime belong to the JVM/JNI boundary", "AGENTS.md", "continue using JNI handles and runtime helpers"),
                nonGoal("native-gc-thread-scheduler", "EXPLICIT_NONGOAL_NATIVE_GC_THREAD_SCHEDULER", "GC, Thread scheduling and monitor ownership are not reimplemented natively", "AGENTS.md", "continue delegating Java-visible semantics to the JVM"));
    }

    private KnownBlockerEntry future(
            String id,
            String reasonCode,
            String currentBehavior,
            String reportLocation,
            String suggestedFuturePath) {
        return new KnownBlockerEntry(id, reasonCode, "future-blocker", "post-rc", currentBehavior, reportLocation, suggestedFuturePath);
    }

    private KnownBlockerEntry rc(
            String id,
            String reasonCode,
            String currentBehavior,
            String reportLocation,
            String suggestedFuturePath) {
        return new KnownBlockerEntry(id, reasonCode, "rc-blocker", "rc", currentBehavior, reportLocation, suggestedFuturePath);
    }

    private KnownBlockerEntry nonGoal(
            String id,
            String reasonCode,
            String currentBehavior,
            String reportLocation,
            String suggestedFuturePath) {
        return new KnownBlockerEntry(id, reasonCode, "non-goal", "explicit-nongoal", currentBehavior, reportLocation, suggestedFuturePath);
    }
}
