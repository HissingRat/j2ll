package xyz.melodysky.analysis.method;

import java.util.Objects;

public record NativeMethodId(
        String owner,
        String name,
        String descriptor) implements Comparable<NativeMethodId> {
    public NativeMethodId {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(descriptor, "descriptor");
        if (owner.isBlank() || name.isBlank() || descriptor.isBlank()) {
            throw new IllegalArgumentException(
                    "native method identity components must not be blank");
        }
    }

    public static NativeMethodId fromMethodKey(String methodKey) {
        Objects.requireNonNull(methodKey, "methodKey");
        int ownerEnd = methodKey.indexOf('#');
        int descriptorStart = methodKey.indexOf('!');
        if (ownerEnd < 1 || descriptorStart <= ownerEnd + 1) {
            throw new IllegalArgumentException(
                    "invalid method key: " + methodKey);
        }
        return new NativeMethodId(
                methodKey.substring(0, ownerEnd),
                methodKey.substring(ownerEnd + 1, descriptorStart),
                methodKey.substring(descriptorStart + 1));
    }

    public String methodKey() {
        return owner + "#" + name + "!" + descriptor;
    }

    @Override
    public int compareTo(NativeMethodId other) {
        return methodKey().compareTo(other.methodKey());
    }
}
