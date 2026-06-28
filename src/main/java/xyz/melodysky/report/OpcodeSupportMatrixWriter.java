package xyz.melodysky.report;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.Comparator;
import java.util.List;

public final class OpcodeSupportMatrixWriter {
    private static final Gson GSON = new GsonBuilder()
            .disableHtmlEscaping()
            .setPrettyPrinting()
            .create();

    public String json() {
        return json(defaultEntries());
    }

    public String json(List<OpcodeSupportEntry> entries) {
        JsonObject root = new JsonObject();
        root.addProperty("schemaVersion", 1);
        root.addProperty("reportVersion", 1);
        JsonArray array = new JsonArray();
        entries.stream()
                .sorted(Comparator
                        .comparing(OpcodeSupportEntry::category)
                        .thenComparing(OpcodeSupportEntry::opcode))
                .forEach(entry -> {
                    JsonObject object = new JsonObject();
                    object.addProperty("opcode", entry.opcode());
                    object.addProperty("category", entry.category());
                    object.addProperty("status", entry.status());
                    object.addProperty("reasonCode", entry.reasonCode());
                    object.addProperty("testCoverage", entry.testCoverage());
                    object.addProperty("coverageLevel", entry.coverageLevel());
                    object.addProperty("evidenceCount", entry.evidenceCount());
                    array.add(object);
                });
        root.add("opcodes", array);
        return GSON.toJson(root) + "\n";
    }

    public List<OpcodeSupportEntry> defaultEntries() {
        return List.of(
                entry("iadd/isub/imul", "arithmetic", "LLVM_NATIVE_PATH", "LLVM_NATIVE_PATH", "BytecodeToSsaLowererTest.lowersStraightLineIntAddToThreeAddressIr"),
                entry("idiv/irem/ldiv/lrem", "arithmetic", "HELPER_BACKED", "DIV_REM_EXCEPTION_HELPER", "JvmHostedNativeRuntimeE2eTest.arithmeticExceptionHelpersRunInChildJvmThroughLlvmNativePath"),
                entry("i2b/i2d/f2i/lcmp/fcmpl", "conversion", "HELPER_BACKED", "JVM_NUMERIC_HELPER", "JvmHostedNativeRuntimeE2eTest.switchAndJvmNumericHelpersRunInChildJvmThroughLlvmNativePath"),
                entry("getfield/putfield/getstatic/putstatic", "field", "HELPER_BACKED", "FIELD_HELPER", "JvmHostedNativeRuntimeE2eTest.fieldHelpersInstanceMethodsAndDirectCallsRunInChildJvmThroughLlvmNativePath"),
                entry("invokestatic/invokespecial", "invoke", "LLVM_NATIVE_PATH", "DIRECT_LLVM_CALL", "JvmHostedNativeRuntimeE2eTest.privateSpecialDirectCallsRunInChildJvmThroughLlvmNativePath"),
                entry("invokevirtual/invokeinterface", "invoke", "HELPER_BACKED", "DISPATCH_HELPER", "JvmHostedNativeRuntimeE2eTest.virtualAndInterfaceDispatchHelpersRunInChildJvmThroughLlvmNativePath"),
                entry("invokedynamic", "invoke", "HELPER_BACKED", "LAMBDA_METAFACTORY_HELPER", "JvmHostedNativeRuntimeE2eTest.lambdaMetafactoryCommonShapesRunInChildJvmThroughJvmHelper"),
                entry("aload/astore/iload/istore/lload/lstore", "locals", "LLVM_NATIVE_PATH", "LLVM_NATIVE_PATH", "BytecodeToSsaLowererTest.lowersWideLocalIinc"),
                entry("monitorenter/monitorexit", "monitor", "HELPER_BACKED", "MONITOR_HELPER", "JvmHostedNativeRuntimeE2eTest.synchronizedBlockMonitorHelpersRunInChildJvmThroughLlvmNativePath"),
                entry("catch-all/finally-state-merge", "exception", "FRONTEND_SKIPPED", "UNSUPPORTED_EXCEPTION_STATE_MERGE", "WeirdBytecodeSeedCorpusTest.unsupportedWeirdBytecodeSeedsProducePreciseFrontendDiagnostics"),
                entry("new/newarray/anewarray", "object-array", "HELPER_BACKED", "ALLOCATION_HELPER", "JvmHostedNativeRuntimeE2eTest.allocationAndStringHelpersRunInChildJvmThroughLlvmNativePath"),
                entry("multianewarray", "object-array", "FALLBACK", "JVM_HELPER_FALLBACK", "BytecodeToSsaLowererTest.javaVisibleAllocationUsesJvmHelperBackedBackendOnly"),
                entry("checkcast/instanceof", "type", "HELPER_BACKED", "TYPE_HELPER", "JvmHostedNativeRuntimeE2eTest.objectConstructionAndTypeHelpersRunInChildJvmThroughLlvmNativePath"),
                entry("dup/dup_x1/dup2/swap", "stack", "LLVM_NATIVE_PATH", "LLVM_NATIVE_PATH", "BytecodeToSsaLowererTest.lowersStackPermutationOpcodes"),
                entry("tableswitch/lookupswitch", "switch", "LLVM_NATIVE_PATH", "LLVM_NATIVE_PATH", "JvmHostedNativeRuntimeE2eTest.switchAndJvmNumericHelpersRunInChildJvmThroughLlvmNativePath"),
                entry("athrow", "exception", "HELPER_BACKED", "EXCEPTION_HELPER", "JvmHostedNativeRuntimeE2eTest.explicitAthrowRunsInChildJvmThroughExceptionBridge"),
                entry("jsr/ret", "legacy-subroutine", "FRONTEND_SKIPPED", "UNSUPPORTED_FINALLY_SUBROUTINE", "BytecodeToSsaLowererTest.unsupportedMultiExitFinallyShapeProducesPreciseDiagnostic"));
    }

    private OpcodeSupportEntry entry(String opcode, String category, String status, String reasonCode, String testCoverage) {
        return new OpcodeSupportEntry(opcode, category, status, reasonCode, testCoverage);
    }
}
