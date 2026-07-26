package xyz.melodysky.frontend.classfile;

import java.util.Objects;
import xyz.melodysky.jvm.AccessFlags;

public record ParsedField(
        String owner,
        String name,
        String descriptor,
        AccessFlags accessFlags,
        String signature,
        Object constantValue,
        boolean hasAnnotations) {
    public ParsedField {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(descriptor, "descriptor");
        Objects.requireNonNull(accessFlags, "accessFlags");
    }

    public ParsedField(
            String owner,
            String name,
            String descriptor,
            AccessFlags accessFlags,
            String signature) {
        this(owner, name, descriptor, accessFlags, signature, null, false);
    }

    public boolean hasConstantValue() {
        return constantValue != null;
    }
}
