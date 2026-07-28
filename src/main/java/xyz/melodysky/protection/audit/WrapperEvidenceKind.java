package xyz.melodysky.protection.audit;

/** Provenance of a wrapper-call-shape fact supplied to the attacker audit. */
public enum WrapperEvidenceKind {
    GHIDRA_HEADLESS_PCODE("ghidraHeadlessPcode", true),
    BINARY_CONTROL_FLOW("binaryControlFlow", true),
    GENERATED_NATIVE_PLAN("generatedNativePlan", false);

    private final String wireName;
    private final boolean finalBinaryEvidence;

    WrapperEvidenceKind(String wireName, boolean finalBinaryEvidence) {
        this.wireName = wireName;
        this.finalBinaryEvidence = finalBinaryEvidence;
    }

    public String wireName() {
        return wireName;
    }

    public boolean finalBinaryEvidence() {
        return finalBinaryEvidence;
    }
}
