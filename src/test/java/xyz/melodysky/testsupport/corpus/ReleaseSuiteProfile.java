package xyz.melodysky.testsupport.corpus;

import java.util.List;

public enum ReleaseSuiteProfile {
    SMOKE("smoke", List.of(
            "llvm-native")),
    STANDARD("standard", List.of(
            "llvm-native",
            "mixed-helper-skipped")),
    BETA("beta", List.of(
            "cli-artifact-smoke",
            "docs-examples-validated",
            "report-index",
            "llvm-native",
            "mixed-helper-skipped")),
    RC("rc", List.of(
            "llvm-native",
            "mixed-helper-skipped",
            "strip-policy",
            "resign-policy",
            "packaging-preservation",
            "config-failure",
            "artifact-audit-failure",
            "required-target-failure",
            "determinism",
            "known-blocker-evidence"));

    private final String wireName;
    private final List<String> requiredCategories;

    ReleaseSuiteProfile(String wireName, List<String> requiredCategories) {
        this.wireName = wireName;
        this.requiredCategories = requiredCategories;
    }

    public String wireName() {
        return wireName;
    }

    public List<String> requiredCategories() {
        return requiredCategories;
    }
}
