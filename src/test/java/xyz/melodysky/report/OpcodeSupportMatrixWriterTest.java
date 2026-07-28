package xyz.melodysky.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class OpcodeSupportMatrixWriterTest {
    @Test
    void writesDeterministicOpcodeMatrixGolden() {
        String json = new OpcodeSupportMatrixWriter().json(List.of(
                new OpcodeSupportEntry("z-op", "stack", "SKIPPED", "Z_REASON", "ZTest"),
                new OpcodeSupportEntry("a-op", "arithmetic", "LLVM_NATIVE_PATH", "A_REASON", "ATest"),
                new OpcodeSupportEntry("b-op", "arithmetic", "HELPER_BACKED", "B_REASON", "BTest")));

        assertEquals("""
                {
                  "schemaVersion": 1,
                  "reportVersion": 1,
                  "opcodes": [
                    {
                      "opcode": "a-op",
                      "category": "arithmetic",
                      "status": "LLVM_NATIVE_PATH",
                      "reasonCode": "A_REASON",
                      "testCoverage": "ATest",
                      "coverageLevel": "unit",
                      "evidenceCount": 1
                    },
                    {
                      "opcode": "b-op",
                      "category": "arithmetic",
                      "status": "HELPER_BACKED",
                      "reasonCode": "B_REASON",
                      "testCoverage": "BTest",
                      "coverageLevel": "unit",
                      "evidenceCount": 1
                    },
                    {
                      "opcode": "z-op",
                      "category": "stack",
                      "status": "SKIPPED",
                      "reasonCode": "Z_REASON",
                      "testCoverage": "ZTest",
                      "coverageLevel": "unit",
                      "evidenceCount": 1
                    }
                  ]
                }
                """, json);
    }

    @Test
    void defaultMatrixCoversCurrentOpcodeReleaseGateBuckets() {
        String json = new OpcodeSupportMatrixWriter().json();

        for (String category : List.of(
                "\"category\": \"arithmetic\"",
                "\"category\": \"invoke\"",
                "\"category\": \"stack\"",
                "\"category\": \"switch\"",
                "\"category\": \"exception\"",
                "\"category\": \"legacy-subroutine\"")) {
            assertTrue(json.contains(category), category);
        }
        for (String reasonCode : List.of(
                "LLVM_NATIVE_PATH",
                "DISPATCH_HELPER",
                "JVM_PENDING_EXCEPTION_ORDERED_DISPATCH",
                "JDK_INTRINSIC_HELPER",
                "THREAD_HELPER",
                "MULTIANEWARRAY_UNSUPPORTED",
                "UNSUPPORTED_EXCEPTION_STATE_MERGE",
                "UNSUPPORTED_FINALLY_SUBROUTINE")) {
            assertTrue(json.contains("\"reasonCode\": \"" + reasonCode + "\""), reasonCode);
        }
        assertTrue(json.contains("\"testCoverage\": \"JvmHostedNativeRuntimeE2eTest"));
        assertTrue(json.contains("\"testCoverage\": \"BytecodeToSsaLowererTest"));
        assertTrue(json.contains("\"coverageLevel\": \"childJvmE2e\""));
        assertTrue(json.contains("\"coverageLevel\": \"unit\""));
    }
}
