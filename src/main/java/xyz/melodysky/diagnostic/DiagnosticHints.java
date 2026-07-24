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
            case "INVALID_EMBEDDED_LIBRARY_DIRECTORY" ->
                    "Use a relative Java package path such as native0 or xyz/Melody/natives; do not use dots, '..', backslashes or META-INF.";
            case "GENERATED_RUNTIME_LOADER_ENTRY_COLLISION",
                    "GENERATED_RUNTIME_LOADER_VERSIONED_SHADOW" ->
                    "Choose an unused embeddedLibraryDirectory or remove the conflicting base/versioned Loader.class entry from the input JAR.";
            case "MISSING_REQUIRED_FIELD" -> missingRequiredFieldHint(message);
            case "ZIG_TARGET_UNBUILDABLE" ->
                    "Open logs/zig-build.log, verify the managed Zig 0.15.2 install, and inspect the named target capability or link failure.";
            case "SYMBOL_AUDIT_FAILED" ->
                    "Open reports/symbol-audit.json and fix missing or unexpected exports for the named native target.";
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
