package xyz.melodysky.testsupport.corpus;

import java.util.List;
import java.util.Map;

public record CorpusCase(
        String name,
        String category,
        List<String> features,
        Map<String, String> expectedSupportStatuses,
        String mainClass,
        Map<String, byte[]> jarEntries,
        List<String> selectors,
        boolean protectionEnabled,
        String signaturePolicy,
        String signingJson,
        Map<String, String> environment,
        boolean expectedPipelineSuccess,
        String targetJson,
        String configJsonOverride,
        String extraTopLevelConfigFields,
        String expectedFailureStage,
        String expectedFailureReasonCode,
        boolean runChildDifferential) {
    public static CorpusCase expectedConfigFailure(
            String name,
            String configJsonOverride,
            String expectedFailureReasonCode) {
        return new CorpusCase(
                name,
                "config-failure",
                List.of("config-failure"),
                Map.of(expectedFailureReasonCode, "expected"),
                "pkg.CorpusMain",
                Map.of(),
                List.of(),
                false,
                "fail",
                null,
                Map.of(),
                false,
                null,
                configJsonOverride,
                null,
                "CONFIG",
                expectedFailureReasonCode,
                false);
    }

    public CorpusCase(
            String name,
            String category,
            List<String> features,
            Map<String, String> expectedSupportStatuses,
            String mainClass,
            Map<String, byte[]> jarEntries,
            List<String> selectors,
            boolean protectionEnabled,
            String signaturePolicy,
            String signingJson,
            Map<String, String> environment,
            boolean expectedPipelineSuccess) {
        this(
                name,
                category,
                features,
                expectedSupportStatuses,
                mainClass,
                jarEntries,
                selectors,
                protectionEnabled,
                signaturePolicy,
                signingJson,
                environment,
                expectedPipelineSuccess,
                null,
                null,
                null,
                expectedPipelineSuccess ? null : "PIPELINE",
                null,
                true);
    }

    public CorpusCase(
            String name,
            String category,
            List<String> features,
            Map<String, String> expectedSupportStatuses,
            String mainClass,
            Map<String, byte[]> jarEntries,
            List<String> selectors,
            boolean protectionEnabled,
            String signaturePolicy,
            String signingJson,
            Map<String, String> environment,
            boolean expectedPipelineSuccess,
            String targetJson) {
        this(
                name,
                category,
                features,
                expectedSupportStatuses,
                mainClass,
                jarEntries,
                selectors,
                protectionEnabled,
                signaturePolicy,
                signingJson,
                environment,
                expectedPipelineSuccess,
                targetJson,
                null,
                null,
                expectedPipelineSuccess ? null : "PIPELINE",
                null,
                true);
    }

    public CorpusCase withExtraTopLevelConfigFields(String extraTopLevelConfigFields) {
        return new CorpusCase(
                name,
                category,
                features,
                expectedSupportStatuses,
                mainClass,
                jarEntries,
                selectors,
                protectionEnabled,
                signaturePolicy,
                signingJson,
                environment,
                expectedPipelineSuccess,
                targetJson,
                configJsonOverride,
                extraTopLevelConfigFields,
                expectedFailureStage,
                expectedFailureReasonCode,
                runChildDifferential);
    }

    public CorpusCase withExpectedFailure(String stage, String reasonCode) {
        return new CorpusCase(
                name,
                category,
                features,
                expectedSupportStatuses.isEmpty() && reasonCode != null
                        ? Map.of(reasonCode, "expected")
                        : expectedSupportStatuses,
                mainClass,
                jarEntries,
                selectors,
                protectionEnabled,
                signaturePolicy,
                signingJson,
                environment,
                expectedPipelineSuccess,
                targetJson,
                configJsonOverride,
                extraTopLevelConfigFields,
                stage,
                reasonCode,
                runChildDifferential);
    }

    public CorpusCase(
            String name,
            String mainClass,
            Map<String, byte[]> jarEntries,
            List<String> selectors,
            boolean protectionEnabled) {
        this(
                name,
                "smoke",
                List.of(),
                Map.of(),
                mainClass,
                jarEntries,
                selectors,
                protectionEnabled,
                "fail",
                null,
                Map.of(),
                true,
                null,
                null,
                null,
                null,
                null,
                true);
    }

    public CorpusCase {
        if (name.isBlank() || category.isBlank() || signaturePolicy.isBlank()) {
            throw new IllegalArgumentException("corpus case identity fields must not be blank");
        }
        if (runChildDifferential && mainClass.isBlank()) {
            throw new IllegalArgumentException("mainClass must not be blank for child JVM differential cases");
        }
        features = List.copyOf(features).stream().sorted().toList();
        expectedSupportStatuses = Map.copyOf(expectedSupportStatuses);
        jarEntries = Map.copyOf(jarEntries);
        selectors = List.copyOf(selectors);
        environment = Map.copyOf(environment);
    }
}
