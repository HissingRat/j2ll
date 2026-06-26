package xyz.melodysky.runtime.metadata;

public record SignatureMetadata(String signature) {
    public boolean present() {
        return signature != null && !signature.isBlank();
    }

    public static SignatureMetadata of(String signature) {
        return new SignatureMetadata(signature);
    }
}
