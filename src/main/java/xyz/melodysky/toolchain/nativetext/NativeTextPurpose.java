package xyz.melodysky.toolchain.nativetext;

/**
 * Domain separation for native text derivation.
 *
 * <p>Registration, runtime metadata, diagnostics and business strings must not
 * reuse an encoding stream merely because their plaintext happens to match.</p>
 */
public enum NativeTextPurpose {
    REGISTRATION_LOADER_ANCHOR("registration-loader-anchor"),
    REGISTRATION_OWNER("registration-owner"),
    REGISTRATION_METHOD_NAME("registration-method-name"),
    REGISTRATION_DESCRIPTOR("registration-descriptor"),
    REGISTRATION_ERROR("registration-error"),
    RUNTIME_CLASS_NAME("runtime-class-name"),
    RUNTIME_METHOD_NAME("runtime-method-name"),
    RUNTIME_FIELD_NAME("runtime-field-name"),
    RUNTIME_DESCRIPTOR("runtime-descriptor"),
    RUNTIME_ERROR("runtime-error"),
    GENERATED_C_FRAGMENT("generated-c-fragment"),
    BUSINESS_STRING("business-string");

    private final String domain;

    NativeTextPurpose(String domain) {
        this.domain = domain;
    }

    public String domain() {
        return domain;
    }
}
