package xyz.melodysky.report;

import java.util.Objects;

public record OpcodeSupportEntry(
        String opcode,
        String category,
        String status,
        String reasonCode,
        String testCoverage,
        String coverageLevel,
        int evidenceCount) {
    public OpcodeSupportEntry(String opcode, String category, String status, String reasonCode, String testCoverage) {
        this(opcode, category, status, reasonCode, testCoverage, inferCoverageLevel(testCoverage), 1);
    }

    public OpcodeSupportEntry {
        Objects.requireNonNull(opcode, "opcode");
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(reasonCode, "reasonCode");
        Objects.requireNonNull(testCoverage, "testCoverage");
        Objects.requireNonNull(coverageLevel, "coverageLevel");
        if (opcode.isBlank()
                || category.isBlank()
                || status.isBlank()
                || reasonCode.isBlank()
                || testCoverage.isBlank()
                || coverageLevel.isBlank()
                || evidenceCount < 1) {
            throw new IllegalArgumentException("opcode support matrix fields must not be blank");
        }
    }

    private static String inferCoverageLevel(String testCoverage) {
        if (testCoverage.contains("ReleaseSuite")) {
            return "releaseSuite";
        }
        if (testCoverage.contains("E2e") || testCoverage.contains("JvmHostedNativeRuntimeE2eTest")) {
            return "childJvmE2e";
        }
        if (testCoverage.contains("Pipeline") || testCoverage.contains("Integration")) {
            return "integration";
        }
        return "unit";
    }
}
