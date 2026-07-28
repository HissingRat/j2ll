package xyz.melodysky.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class SupportMatrixWriterTest {
    @Test
    void writesDeterministicFeatureMatrix() {
        String json = new SupportMatrixWriter().json(List.of(
                new SupportMatrixEntry("zeta", "SKIPPED", "Z_REASON", "ZTest"),
                new SupportMatrixEntry("alpha", "LLVM_NATIVE_PATH", "A_REASON", "ATest")));

        assertEquals("""
                {
                  "schemaVersion": 1,
                  "reportVersion": 1,
                  "features": [
                    {
                      "feature": "alpha",
                      "status": "LLVM_NATIVE_PATH",
                      "reasonCode": "A_REASON",
                      "testCoverage": "ATest",
                      "coverageLevel": "unit",
                      "evidenceCount": 1
                    },
                    {
                      "feature": "zeta",
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
    void defaultMatrixDocumentsUnsupportedBoundariesAndTestPointers() {
        String json = new SupportMatrixWriter().json();

        for (String reasonCode : List.of(
                "UNSUPPORTED_EXCEPTION_STATE_MERGE",
                "UNSUPPORTED_JVM_EXCEPTION_FLOW",
                "UNSUPPORTED_FINALLY_SUBROUTINE",
                "UNSUPPORTED_MONITOR_FINALLY_INTERACTION",
                "NATIVE_IMPLEMENTATION_UNAVAILABLE",
                "UNSAFE_RAW_MEMORY_UNSUPPORTED",
                "VAR_HANDLE_DYNAMIC_UNSUPPORTED",
                "METHOD_HANDLE_CHAIN_UNSUPPORTED",
                "METHOD_HANDLE_PERMUTE_UNSUPPORTED",
                "METHOD_HANDLE_FILTER_UNSUPPORTED",
                "METHOD_HANDLE_FOLD_UNSUPPORTED",
                "METHOD_HANDLE_COLLECTOR_UNSUPPORTED",
                "WAIT_NOTIFY_UNSUPPORTED")) {
            assertTrue(json.contains("\"reasonCode\": \"" + reasonCode + "\""), reasonCode);
        }
        for (String reasonCode : List.of(
                "JVM_PENDING_EXCEPTION_ORDERED_DISPATCH",
                "JVM_PENDING_EXCEPTION_PROPAGATION",
                "LLVM_CONSTRUCTOR_SPLIT_BODY_IR",
                "LLVM_CLASS_INITIALIZER_BODY_IR",
                "JDK_INTRINSIC_HELPER",
                "THREAD_HELPER")) {
            assertTrue(json.contains("\"reasonCode\": \"" + reasonCode + "\""), reasonCode);
        }
        for (String feature : List.of(
                "exception.protected-jni-pending-ordered-handlers",
                "exception.unprotected-jni-pending-return",
                "initializer.constructor-linear-verifier-prefix",
                "initializer.class-initializer-body",
                "jdk.object-get-class",
                "thread.sleep-long")) {
            assertTrue(json.contains("\"feature\": \"" + feature + "\""), feature);
        }
        assertFalse(json.contains("\"reasonCode\": \"UNSUPPORTED_MULTI_EXIT_FINALLY\""), json);
        assertFalse(json.contains("\"reasonCode\": \"UNSUPPORTED_NESTED_FINALLY\""), json);
        assertTrue(json.contains("\"testCoverage\": \"JvmHostedNativeRuntimeE2eTest\""));
        assertTrue(json.contains("\"coverageLevel\": \"childJvmE2e\""));
        assertTrue(json.contains("\"evidenceCount\": 1"));
    }

    @Test
    void knownBlockerReasonsHaveSupportOrOpcodeMatrixCoverage() {
        String support = new SupportMatrixWriter().json();
        String opcode = new OpcodeSupportMatrixWriter().json();

        for (KnownBlockerEntry blocker : new KnownBlockersWriter().defaultEntries()) {
            if (blocker.severity().equals("non-goal")
                    || blocker.targetMilestone().equals("explicit-nongoal")) {
                continue;
            }
            assertTrue(
                    support.contains("\"reasonCode\": \"" + blocker.reasonCode() + "\"")
                            || opcode.contains("\"reasonCode\": \"" + blocker.reasonCode() + "\""),
                    blocker.reasonCode());
        }
    }

    @Test
    void defaultReleaseMatrixWritersAreByteStableAcrossInvocations() {
        SupportMatrixWriter supportWriter = new SupportMatrixWriter();
        OpcodeSupportMatrixWriter opcodeWriter = new OpcodeSupportMatrixWriter();
        KnownBlockersWriter blockersWriter = new KnownBlockersWriter();

        assertEquals(supportWriter.json(), supportWriter.json());
        assertEquals(opcodeWriter.json(), opcodeWriter.json());
        assertEquals(blockersWriter.json(), blockersWriter.json());
        assertTrue(supportWriter.json().contains("\"coverageLevel\""));
        assertTrue(opcodeWriter.json().contains("\"evidenceCount\""));
    }

    @Test
    void supportTierDocsMentionMajorMatrixBoundaries() throws Exception {
        String docs = Files.readString(Path.of("docs/java-support-tiers.md"));

        for (String boundary : List.of(
                "support-matrix.json",
                "UNSAFE_RAW_MEMORY_UNSUPPORTED",
                "UNSUPPORTED_EXCEPTION_STATE_MERGE",
                "SIGNATURE_RESIGNED")) {
            assertTrue(docs.contains(boundary), boundary);
        }
    }
}
