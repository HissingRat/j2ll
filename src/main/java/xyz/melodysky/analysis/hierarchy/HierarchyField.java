package xyz.melodysky.analysis.hierarchy;

import java.util.Objects;
import xyz.melodysky.jvm.AccessFlags;
import xyz.melodysky.jvm.FieldSignature;

public record HierarchyField(
        String owner,
        FieldSignature signature,
        AccessFlags accessFlags,
        boolean external) {
    public HierarchyField {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(signature, "signature");
        Objects.requireNonNull(accessFlags, "accessFlags");
    }
}
