package xyz.melodysky.diagnostic;

public final class DiagnosticHints {
    private DiagnosticHints() {
    }

    public static String hint(Diagnostic diagnostic) {
        return hint(diagnostic.code().value(), diagnostic.message());
    }

    public static String hint(String reasonCode, String message) {
        return switch (reasonCode) {
            case "INVALID_SELECTOR" -> "Use owner/Class#method!(descriptor), for example example/Adder#add!(II)I.";
            case "MISSING_REQUIRED_FIELD" -> missingRequiredFieldHint(message);
            case "ZIG_TARGET_UNBUILDABLE" ->
                    "For beta builds, select the current host target or install the required Zig/SDK cross-target support.";
            case "SIGNED_INPUT_REJECTED" ->
                    "Use signaturePolicy strip or resign, or provide an unsigned input JAR.";
            case "SIGNATURE_RESIGN_FAILED" ->
                    "Check signing.storeFile, signing.alias and signing credentials before using signaturePolicy resign.";
            case "ARTIFACT_AUDIT_FAILED" ->
                    "Open reports/artifact-audit.json and remove the reported plaintext or metadata mismatch before finalization.";
            case "RELEASE_READINESS_FAILED" ->
                    "Open reports/release-readiness.json and satisfy the listed missingEvidence entries.";
            default -> "";
        };
    }

    private static String missingRequiredFieldHint(String message) {
        if (message != null && message.contains("schemaVersion")) {
            return "Add \"schemaVersion\": 1 to the config root.";
        }
        return "Compare the config against docs/config.schema.json and docs/examples/minimal-config.json.";
    }
}
