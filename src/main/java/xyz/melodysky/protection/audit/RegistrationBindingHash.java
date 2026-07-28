package xyz.melodysky.protection.audit;

/** Hash-only native registration mapping observed during JNI_OnLoad. */
public record RegistrationBindingHash(
        String ownerHash,
        String methodNameHash,
        String descriptorHash,
        String functionIdentityHash)
        implements Comparable<RegistrationBindingHash> {
    public RegistrationBindingHash {
        ownerHash = HashOnlyEvidence.requireSha256(ownerHash, "ownerHash");
        methodNameHash = HashOnlyEvidence.requireSha256(
                methodNameHash,
                "methodNameHash");
        descriptorHash = HashOnlyEvidence.requireSha256(
                descriptorHash,
                "descriptorHash");
        functionIdentityHash = HashOnlyEvidence.requireSha256(
                functionIdentityHash,
                "functionIdentityHash");
    }

    @Override
    public int compareTo(RegistrationBindingHash other) {
        int owner = ownerHash.compareTo(other.ownerHash);
        if (owner != 0) {
            return owner;
        }
        int method = methodNameHash.compareTo(other.methodNameHash);
        if (method != 0) {
            return method;
        }
        int descriptor = descriptorHash.compareTo(other.descriptorHash);
        if (descriptor != 0) {
            return descriptor;
        }
        return functionIdentityHash.compareTo(other.functionIdentityHash);
    }
}
