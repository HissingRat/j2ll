package xyz.melodysky.config;

public enum SignaturePolicy {
    FAIL("fail"),
    STRIP("strip"),
    RESIGN("resign");

    private final String wireName;

    SignaturePolicy(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }

    public static SignaturePolicy parse(String value) {
        for (SignaturePolicy policy : values()) {
            if (policy.wireName.equals(value)) {
                return policy;
            }
        }
        throw new IllegalArgumentException("unsupported signaturePolicy: " + value);
    }
}
