package xyz.melodysky.packaging;

import java.util.List;

public record SignatureActionReport(
        String action,
        boolean signedInput,
        List<String> removedEntries,
        String reasonCode,
        String reason) {
    public SignatureActionReport {
        removedEntries = List.copyOf(removedEntries);
    }

    public static SignatureActionReport none(boolean signedInput) {
        return new SignatureActionReport("none", signedInput, List.of(), "SIGNATURE_NOT_PRESENT", "input JAR is not signed");
    }

    public static SignatureActionReport fail(boolean signedInput, String reason) {
        return new SignatureActionReport("fail", signedInput, List.of(), "SIGNED_INPUT_REJECTED", reason);
    }

    public static SignatureActionReport strip(boolean signedInput, List<String> removedEntries) {
        return new SignatureActionReport(
                "strip",
                signedInput,
                removedEntries,
                "SIGNATURE_STRIPPED",
                "existing signature files were removed before output JAR packaging");
    }

    public static SignatureActionReport resignFailed(boolean signedInput, List<String> removedEntries, String reasonCode, String reason) {
        return new SignatureActionReport("resignFailed", signedInput, removedEntries, reasonCode, reason);
    }

    public static SignatureActionReport resigned(boolean signedInput, List<String> removedEntries) {
        return new SignatureActionReport(
                "resign",
                signedInput,
                removedEntries,
                "SIGNATURE_RESIGNED",
                "old signature files were removed and output JAR was signed with the configured key");
    }
}
