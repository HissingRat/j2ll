package xyz.melodysky.packaging;

import java.util.List;
import java.util.Objects;
import org.objectweb.asm.tree.MethodNode;

public record FallbackBlobInput(
        String originalMethodId,
        String originalMethodKey,
        String ownerInternalName,
        String methodName,
        String descriptor,
        boolean staticMethod,
        MethodNode methodNode,
        String reasonCode,
        List<FallbackSidecarFieldAccess> sidecarFieldAccesses) {
    public FallbackBlobInput {
        Objects.requireNonNull(originalMethodId, "originalMethodId");
        Objects.requireNonNull(originalMethodKey, "originalMethodKey");
        Objects.requireNonNull(ownerInternalName, "ownerInternalName");
        Objects.requireNonNull(methodName, "methodName");
        Objects.requireNonNull(descriptor, "descriptor");
        Objects.requireNonNull(reasonCode, "reasonCode");
        sidecarFieldAccesses = List.copyOf(
                Objects.requireNonNull(sidecarFieldAccesses, "sidecarFieldAccesses"));
    }

    public FallbackBlobInput(
            String originalMethodId,
            String originalMethodKey,
            String ownerInternalName,
            String methodName,
            String descriptor,
            boolean staticMethod,
            MethodNode methodNode,
            String reasonCode) {
        this(
                originalMethodId,
                originalMethodKey,
                ownerInternalName,
                methodName,
                descriptor,
                staticMethod,
                methodNode,
                reasonCode,
                List.of());
    }

    public FallbackBlobInput(
            String originalMethodId,
            String originalMethodKey,
            String ownerInternalName) {
        this(
                originalMethodId,
                originalMethodKey,
                ownerInternalName,
                methodName(originalMethodKey),
                descriptor(originalMethodKey),
                true,
                null,
                "JVM_HELPER_FALLBACK",
                List.of());
    }

    static String methodName(String methodKey) {
        int ownerEnd = methodKey.indexOf('#');
        int descriptorStart = methodKey.indexOf('!');
        if (ownerEnd < 0 || descriptorStart < ownerEnd) {
            throw new IllegalArgumentException("invalid method key: " + methodKey);
        }
        return methodKey.substring(ownerEnd + 1, descriptorStart);
    }

    static String descriptor(String methodKey) {
        int descriptorStart = methodKey.indexOf('!');
        if (descriptorStart < 0 || descriptorStart + 1 >= methodKey.length()) {
            throw new IllegalArgumentException("invalid method key: " + methodKey);
        }
        return methodKey.substring(descriptorStart + 1);
    }
}
