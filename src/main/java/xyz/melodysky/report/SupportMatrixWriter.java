package xyz.melodysky.report;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.Comparator;
import java.util.List;

public final class SupportMatrixWriter {
    private static final Gson GSON = new GsonBuilder()
            .disableHtmlEscaping()
            .setPrettyPrinting()
            .create();

    public String json() {
        return json(defaultEntries());
    }

    public String json(List<SupportMatrixEntry> entries) {
        JsonObject root = new JsonObject();
        root.addProperty("schemaVersion", 1);
        root.addProperty("reportVersion", 1);
        JsonArray array = new JsonArray();
        entries.stream()
                .sorted(Comparator
                        .comparing(SupportMatrixEntry::feature)
                        .thenComparing(SupportMatrixEntry::status)
                        .thenComparing(SupportMatrixEntry::reasonCode))
                .forEach(entry -> {
                    JsonObject object = new JsonObject();
                    object.addProperty("feature", entry.feature());
                    object.addProperty("status", entry.status());
                    object.addProperty("reasonCode", entry.reasonCode());
                    object.addProperty("testCoverage", entry.testCoverage());
                    object.addProperty("coverageLevel", entry.coverageLevel());
                    object.addProperty("evidenceCount", entry.evidenceCount());
                    array.add(object);
                });
        root.add("features", array);
        return GSON.toJson(root) + "\n";
    }

    public List<SupportMatrixEntry> defaultEntries() {
        return List.of(
                entry("arrays.primitive-and-reference", "HELPER_BACKED", "ARRAY_HELPER", "JvmHostedNativeRuntimeE2eTest"),
                entry("dispatch.virtual-interface-common", "HELPER_BACKED", "DISPATCH_HELPER", "JvmHostedNativeRuntimeE2eTest"),
                entry("exception.state-merge-finally", "FRONTEND_SKIPPED", "UNSUPPORTED_EXCEPTION_STATE_MERGE", "KnownBlockersWriterTest"),
                entry("exception.complex-finally", "FRONTEND_SKIPPED", "UNSUPPORTED_MULTI_EXIT_FINALLY", "BytecodeToSsaLowererTest"),
                entry("exception.legacy-finally-subroutine", "FRONTEND_SKIPPED", "UNSUPPORTED_FINALLY_SUBROUTINE", "WeirdBytecodeSeedCorpusTest"),
                entry("exception.monitor-finally", "FRONTEND_SKIPPED", "UNSUPPORTED_MONITOR_FINALLY_INTERACTION", "WeirdBytecodeSeedCorpusTest"),
                entry("exception.nested-finally", "FRONTEND_SKIPPED", "UNSUPPORTED_NESTED_FINALLY", "WeirdBytecodeSeedCorpusTest"),
                entry("fallback.cache-lifetime", "FALLBACK", "FALLBACK_CACHE_REUSE", "FallbackBlobPlannerTest"),
                entry("fallback.nativeEmbeddedClassBlob", "FALLBACK", "JVM_HELPER_FALLBACK", "FallbackBlobPlannerTest"),
                entry("jdk.collections", "FALLBACK", "JDK_COLLECTION_HELPER_FALLBACK", "JvmHostedNativeRuntimeE2eTest"),
                entry("llvm.primitive-scalar-straight-line", "LLVM_NATIVE_PATH", "LLVM_NATIVE_PATH", "JvmHostedNativeRuntimeE2eTest"),
                entry("method.interface-no-code", "NOT_APPLICABLE", "NOT_APPLICABLE", "MainlinePipelineIntegrationTest"),
                entry("methodhandle.alt-metafactory-runtime", "FALLBACK", "ALT_METAFACTORY_FALLBACK", "JvmHostedNativeRuntimeE2eTest"),
                entry("methodhandle.complex-adapter", "FALLBACK", "METHOD_HANDLE_CHAIN_FALLBACK", "JvmHostedNativeRuntimeE2eTest"),
                entry("methodhandle.permute-adapter", "FALLBACK", "METHOD_HANDLE_PERMUTE_FALLBACK", "JvmHostedNativeRuntimeE2eTest"),
                entry("methodhandle.filter-adapter", "FALLBACK", "METHOD_HANDLE_FILTER_FALLBACK", "JvmHostedNativeRuntimeE2eTest"),
                entry("methodhandle.fold-adapter", "FALLBACK", "METHOD_HANDLE_FOLD_FALLBACK", "JvmHostedNativeRuntimeE2eTest"),
                entry("methodhandle.collector-adapter", "FALLBACK", "METHOD_HANDLE_COLLECTOR_UNSUPPORTED", "JvmHostedNativeRuntimeE2eTest"),
                entry("reflection.static-common", "HELPER_BACKED", "REFLECTION_METHOD_HELPER", "JvmHostedNativeRuntimeE2eTest"),
                entry("signing.resign", "HELPER_BACKED", "SIGNATURE_RESIGNED", "JarSignatureResignerTest"),
                entry("toolchain.cross-target-build", "LLVM_NATIVE_PATH", "ZIG_CROSS_TARGET_SUPPORTED", "ZigCrossTargetBuildTest"),
                entry("toolchain.cross-target-runtime-e2e", "FALLBACK", "CROSS_TARGET_RUNTIME_E2E_PENDING", "ZigCrossTargetBuildTest"),
                entry("thread.start-join", "FALLBACK", "THREAD_HELPER_FALLBACK", "JvmHostedNativeRuntimeE2eTest"),
                entry("unsafe.raw-memory", "FALLBACK", "UNSAFE_RAW_MEMORY_FALLBACK", "BytecodeToSsaLowererTest"),
                entry("varhandle.dynamic-byte-view", "FALLBACK", "VAR_HANDLE_DYNAMIC_FALLBACK", "BytecodeToSsaLowererTest"),
                entry("wait-notify", "FALLBACK", "WAIT_NOTIFY_FALLBACK", "JvmHostedNativeRuntimeE2eTest"));
    }

    private SupportMatrixEntry entry(String feature, String status, String reasonCode, String testCoverage) {
        return new SupportMatrixEntry(feature, status, reasonCode, testCoverage);
    }
}
