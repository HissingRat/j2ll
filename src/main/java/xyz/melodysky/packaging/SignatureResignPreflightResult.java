package xyz.melodysky.packaging;

public record SignatureResignPreflightResult(
        boolean successful,
        String reasonCode,
        String reason) {
    public static SignatureResignPreflightResult ok() {
        return new SignatureResignPreflightResult(true, "SIGNATURE_RESIGN_READY", "signing config is valid");
    }

    public static SignatureResignPreflightResult failed(String reasonCode, String reason) {
        return new SignatureResignPreflightResult(false, reasonCode, reason);
    }
}
