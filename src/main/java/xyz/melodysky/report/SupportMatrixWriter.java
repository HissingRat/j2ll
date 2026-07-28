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
                entry("exception.protected-jni-pending-ordered-handlers", "HELPER_BACKED", "JVM_PENDING_EXCEPTION_ORDERED_DISPATCH", "ProtectedJvmExceptionFlowNativeRuntimeE2eTest"),
                entry("exception.unprotected-jni-pending-return", "HELPER_BACKED", "JVM_PENDING_EXCEPTION_PROPAGATION", "LlvmExceptionFlowLowererTest.unprotectedPendingExceptionRunsCleanupAndReturnsWithoutClearing"),
                entry("exception.supported-catch-finally-shapes", "HELPER_BACKED", "JVM_PENDING_EXCEPTION_ORDERED_DISPATCH", "WeirdBytecodeSeedCorpusTest.supportedWeirdBytecodeSeedsLowerWithoutSilentSkip"),
                entry("exception.missing-pending-or-handler-transfer-evidence", "SKIPPED", "UNSUPPORTED_JVM_EXCEPTION_FLOW", "FinalNativeCoverageResolverTest.missingPendingExceptionEvidenceUsesTheGeneralJvmFlowDiagnostic"),
                entry("exception.unreconciled-handler-frame", "SKIPPED", "UNSUPPORTED_EXCEPTION_STATE_MERGE", "KnownBlockersWriterTest"),
                entry("exception.legacy-finally-subroutine", "SKIPPED", "UNSUPPORTED_FINALLY_SUBROUTINE", "WeirdBytecodeSeedCorpusTest"),
                entry("exception.complex-monitor-finally", "SKIPPED", "UNSUPPORTED_MONITOR_FINALLY_INTERACTION", "KnownBlockersWriterTest"),
                entry("initializer.class-initializer-body", "LLVM_NATIVE_PATH", "LLVM_CLASS_INITIALIZER_BODY_IR", "JvmHostedNativeRuntimeE2eTest.constructorAndClassInitializerBodyHelpersRunInChildJvm"),
                entry("initializer.constructor-linear-verifier-prefix", "LLVM_NATIVE_PATH", "LLVM_CONSTRUCTOR_SPLIT_BODY_IR", "JvmHostedNativeRuntimeE2eTest.constructorStubPreservesParameterizedSuperInvocationInChildJvm"),
                entry("initializer.constructor-nonlinear-or-exception-table", "SKIPPED", "NATIVE_IMPLEMENTATION_UNAVAILABLE", "InitializerImplementationPlannerTest.constructorWithExceptionTableFailsClosed"),
                entry("jdk.collections", "HELPER_BACKED", "JDK_COLLECTION_HELPER", "JvmHostedNativeRuntimeE2eTest"),
                entry("jdk.object-get-class", "HELPER_BACKED", "JDK_INTRINSIC_HELPER", "ObjectGetClassNativeHelperTest"),
                entry("llvm.primitive-scalar-straight-line", "LLVM_NATIVE_PATH", "LLVM_NATIVE_PATH", "JvmHostedNativeRuntimeE2eTest"),
                entry("method.interface-no-code", "INELIGIBLE", "NO_CODE", "MainlinePipelineIntegrationTest"),
                entry("methodhandle.alt-metafactory-runtime", "SKIPPED", "ALT_METAFACTORY_UNSUPPORTED", "JvmHostedNativeRuntimeE2eTest"),
                entry("methodhandle.complex-adapter", "SKIPPED", "METHOD_HANDLE_CHAIN_UNSUPPORTED", "JvmHostedNativeRuntimeE2eTest"),
                entry("methodhandle.permute-adapter", "SKIPPED", "METHOD_HANDLE_PERMUTE_UNSUPPORTED", "JvmHostedNativeRuntimeE2eTest"),
                entry("methodhandle.filter-adapter", "SKIPPED", "METHOD_HANDLE_FILTER_UNSUPPORTED", "JvmHostedNativeRuntimeE2eTest"),
                entry("methodhandle.fold-adapter", "SKIPPED", "METHOD_HANDLE_FOLD_UNSUPPORTED", "JvmHostedNativeRuntimeE2eTest"),
                entry("methodhandle.collector-adapter", "SKIPPED", "METHOD_HANDLE_COLLECTOR_UNSUPPORTED", "JvmHostedNativeRuntimeE2eTest"),
                entry("reflection.static-common", "HELPER_BACKED", "REFLECTION_METHOD_HELPER", "JvmHostedNativeRuntimeE2eTest"),
                entry("signing.resign", "HELPER_BACKED", "SIGNATURE_RESIGNED", "JarSignatureResignerTest"),
                entry("toolchain.cross-target-build", "LLVM_NATIVE_PATH", "ZIG_CROSS_TARGET_SUPPORTED", "ZigCrossTargetBuildTest"),
                entry("toolchain.cross-target-runtime-e2e", "SKIPPED", "CROSS_TARGET_RUNTIME_E2E_PENDING", "ZigCrossTargetBuildTest"),
                entry("thread.sleep-long", "HELPER_BACKED", "THREAD_HELPER", "ThreadSleepNativeHelperTest"),
                entry("thread.start-join", "SKIPPED", "THREAD_HELPER_UNSUPPORTED", "JvmHostedNativeRuntimeE2eTest"),
                entry("unsafe.raw-memory", "SKIPPED", "UNSAFE_RAW_MEMORY_UNSUPPORTED", "BytecodeToSsaLowererTest"),
                entry("varhandle.dynamic-byte-view", "SKIPPED", "VAR_HANDLE_DYNAMIC_UNSUPPORTED", "BytecodeToSsaLowererTest"),
                entry("wait-notify", "SKIPPED", "WAIT_NOTIFY_UNSUPPORTED", "JvmHostedNativeRuntimeE2eTest"));
    }

    private SupportMatrixEntry entry(String feature, String status, String reasonCode, String testCoverage) {
        return new SupportMatrixEntry(feature, status, reasonCode, testCoverage);
    }
}
