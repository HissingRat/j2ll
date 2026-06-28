package xyz.melodysky.report;

import java.util.Objects;

public record SupportMatrixEntry(
        String feature,
        String status,
        String reasonCode,
        String testCoverage,
        String coverageLevel,
        int evidenceCount) {
    public SupportMatrixEntry(String feature, String status, String reasonCode, String testCoverage) {
        this(feature, status, reasonCode, testCoverage, inferCoverageLevel(testCoverage), 1);
    }

    public SupportMatrixEntry {
        Objects.requireNonNull(feature, "feature");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(reasonCode, "reasonCode");
        Objects.requireNonNull(testCoverage, "testCoverage");
        Objects.requireNonNull(coverageLevel, "coverageLevel");
        if (feature.isBlank()
                || status.isBlank()
                || reasonCode.isBlank()
                || testCoverage.isBlank()
                || coverageLevel.isBlank()
                || evidenceCount < 1) {
            throw new IllegalArgumentException("support matrix fields must not be blank");
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
