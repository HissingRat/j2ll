package xyz.melodysky.protection;

/**
 * Closed registry of build-identity KDF domains.
 *
 * <p>Adding a protection consumer requires adding one explicit entry here and
 * extending the uniqueness/separation tests. Callers cannot derive material
 * from ad-hoc string domains.</p>
 */
public enum BuildProtectionDomain {
    IR_METHOD("IR_METHOD_PROTECTION"),
    IR_PROGRAM("IR_PROGRAM_PROTECTION"),
    FIELD("FIELD_INTERNALIZATION"),
    BUSINESS_STRING("BUSINESS_STRING"),
    METHOD_TABLE("METHOD_TABLE"),
    WRAPPER("NATIVE_WRAPPER"),
    LLVM_SYMBOL("LLVM_SYMBOL"),
    LLVM_PROTECTION("LLVM_PROTECTION"),
    NATIVE_TEXT("NATIVE_TEXT"),
    BUSINESS_NATIVE_TEXT("BUSINESS_NATIVE_TEXT"),
    REGISTRATION("NATIVE_REGISTRATION"),
    REPORT_IDENTITY("REPORT_IDENTITY");

    private final String wireName;

    BuildProtectionDomain(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }
}
