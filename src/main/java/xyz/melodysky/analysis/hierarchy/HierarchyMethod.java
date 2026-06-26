package xyz.melodysky.analysis.hierarchy;

import java.util.Objects;
import xyz.melodysky.jvm.AccessFlags;
import xyz.melodysky.jvm.MethodSignature;

public record HierarchyMethod(
        String owner,
        MethodSignature signature,
        AccessFlags accessFlags,
        boolean hasCode,
        boolean external) {
    public HierarchyMethod {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(signature, "signature");
        Objects.requireNonNull(accessFlags, "accessFlags");
    }

    public boolean canBeOverridden() {
        return !accessFlags.isStatic()
                && !accessFlags.isPrivate()
                && !accessFlags.isFinal();
    }
}
