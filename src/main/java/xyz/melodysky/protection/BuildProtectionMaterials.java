package xyz.melodysky.protection;

import java.util.Arrays;
import java.util.Objects;

/**
 * Mainline protection material derived once from one build identity.
 *
 * <p>This keeps domain and context choices out of pipeline orchestration. The
 * registration material is reserved for registration text/layout, while
 * general runtime metadata and business strings receive independent
 * native-text keys.</p>
 */
public final class BuildProtectionMaterials {
    private static final int NATIVE_TEXT_KEY_BYTES = 32;
    private static final int BUSINESS_NATIVE_TEXT_KEY_BYTES = 32;
    private static final int REGISTRATION_KEY_BYTES = 32;

    private final long irMethodSeed;
    private final long irProgramSeed;
    private final long fieldSeed;
    private final long businessStringSeed;
    private final long methodTableSeed;
    private final long wrapperSeed;
    private final long llvmSymbolSeed;
    private final long llvmProtectionSeed;
    private final byte[] nativeTextKey;
    private final byte[] businessNativeTextKey;
    private final byte[] registrationKey;

    private BuildProtectionMaterials(
            long irMethodSeed,
            long irProgramSeed,
            long fieldSeed,
            long businessStringSeed,
            long methodTableSeed,
            long wrapperSeed,
            long llvmSymbolSeed,
            long llvmProtectionSeed,
            byte[] nativeTextKey,
            byte[] businessNativeTextKey,
            byte[] registrationKey) {
        this.irMethodSeed = irMethodSeed;
        this.irProgramSeed = irProgramSeed;
        this.fieldSeed = fieldSeed;
        this.businessStringSeed = businessStringSeed;
        this.methodTableSeed = methodTableSeed;
        this.wrapperSeed = wrapperSeed;
        this.llvmSymbolSeed = llvmSymbolSeed;
        this.llvmProtectionSeed = llvmProtectionSeed;
        this.nativeTextKey = nativeTextKey.clone();
        this.businessNativeTextKey = businessNativeTextKey.clone();
        this.registrationKey = registrationKey.clone();
    }

    public static BuildProtectionMaterials derive(
            BuildProtectionIdentity identity) {
        Objects.requireNonNull(identity, "identity");
        return new BuildProtectionMaterials(
                identity.deriveLong(
                        BuildProtectionDomain.IR_METHOD,
                        "mainline/per-method"),
                identity.deriveLong(
                        BuildProtectionDomain.IR_PROGRAM,
                        "mainline/call-graph"),
                identity.deriveLong(
                        BuildProtectionDomain.FIELD,
                        "mainline/approved-field-plan"),
                identity.deriveLong(
                        BuildProtectionDomain.BUSINESS_STRING,
                        "mainline/template-carriers"),
                identity.deriveLong(
                        BuildProtectionDomain.METHOD_TABLE,
                        "mainline/registration-plan"),
                identity.deriveLong(
                        BuildProtectionDomain.WRAPPER,
                        "mainline/native-wrapper"),
                identity.deriveLong(
                        BuildProtectionDomain.LLVM_SYMBOL,
                        "mainline/name-mangling"),
                identity.deriveLong(
                        BuildProtectionDomain.LLVM_PROTECTION,
                        "mainline/module-passes"),
                identity.deriveBytes(
                        BuildProtectionDomain.NATIVE_TEXT,
                        "mainline/generated-c",
                        NATIVE_TEXT_KEY_BYTES),
                identity.deriveBytes(
                        BuildProtectionDomain.BUSINESS_NATIVE_TEXT,
                        "mainline/business-native-text",
                        BUSINESS_NATIVE_TEXT_KEY_BYTES),
                identity.deriveBytes(
                        BuildProtectionDomain.REGISTRATION,
                        "mainline/registration-text-and-layout",
                        REGISTRATION_KEY_BYTES));
    }

    public long irMethodSeed() {
        return irMethodSeed;
    }

    public long irProgramSeed() {
        return irProgramSeed;
    }

    public long fieldSeed() {
        return fieldSeed;
    }

    public long businessStringSeed() {
        return businessStringSeed;
    }

    public long methodTableSeed() {
        return methodTableSeed;
    }

    public long wrapperSeed() {
        return wrapperSeed;
    }

    public long llvmSymbolSeed() {
        return llvmSymbolSeed;
    }

    public long llvmProtectionSeed() {
        return llvmProtectionSeed;
    }

    public byte[] nativeTextKey() {
        return nativeTextKey.clone();
    }

    public byte[] businessNativeTextKey() {
        return businessNativeTextKey.clone();
    }

    public byte[] registrationKey() {
        return registrationKey.clone();
    }

    @Override
    public boolean equals(Object candidate) {
        if (!(candidate instanceof BuildProtectionMaterials other)) {
            return false;
        }
        return irMethodSeed == other.irMethodSeed
                && irProgramSeed == other.irProgramSeed
                && fieldSeed == other.fieldSeed
                && businessStringSeed == other.businessStringSeed
                && methodTableSeed == other.methodTableSeed
                && wrapperSeed == other.wrapperSeed
                && llvmSymbolSeed == other.llvmSymbolSeed
                && llvmProtectionSeed == other.llvmProtectionSeed
                && Arrays.equals(nativeTextKey, other.nativeTextKey)
                && Arrays.equals(
                        businessNativeTextKey,
                        other.businessNativeTextKey)
                && Arrays.equals(registrationKey, other.registrationKey);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(
                irMethodSeed,
                irProgramSeed,
                fieldSeed,
                businessStringSeed,
                methodTableSeed,
                wrapperSeed,
                llvmSymbolSeed,
                llvmProtectionSeed);
        result = 31 * result + Arrays.hashCode(nativeTextKey);
        result = 31 * result + Arrays.hashCode(businessNativeTextKey);
        return 31 * result + Arrays.hashCode(registrationKey);
    }

    @Override
    public String toString() {
        return "BuildProtectionMaterials[domainSeparated=true]";
    }
}
