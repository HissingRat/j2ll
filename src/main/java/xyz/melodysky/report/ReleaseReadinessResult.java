package xyz.melodysky.report;

import java.util.List;

public record ReleaseReadinessResult(
        boolean passed,
        List<ReleaseReadinessCheck> checks,
        List<ReleaseReadinessMissingEvidence> missingEvidence,
        List<ReleaseBlockerCoverage> suiteCoverageByBlocker,
        boolean blockerEvidenceComplete,
        boolean targetEvidenceComplete,
        boolean finalArtifactWritten,
        boolean determinismEvidenceComplete,
        boolean metadataConsistencyPassed,
        boolean blockingSensitiveFactsPassed,
        boolean targetPackagePlanComplete,
        boolean betaProfilePassed,
        List<String> betaMissingEvidence,
        boolean cliArtifactSmokePassed,
        boolean docsExamplesValidated,
        boolean strictModePassed) {
    public ReleaseReadinessResult(boolean passed, List<ReleaseReadinessCheck> checks) {
        this(passed, checks, List.of(), List.of(), false, false, false, false, false, false, false,
                false, List.of(), false, false, false);
    }

    public ReleaseReadinessResult {
        checks = List.copyOf(checks);
        missingEvidence = List.copyOf(missingEvidence);
        suiteCoverageByBlocker = List.copyOf(suiteCoverageByBlocker);
        betaMissingEvidence = List.copyOf(betaMissingEvidence);
    }
}
