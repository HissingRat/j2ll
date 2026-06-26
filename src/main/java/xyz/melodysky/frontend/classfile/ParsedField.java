package xyz.melodysky.frontend.classfile;

import java.util.Objects;
import xyz.melodysky.jvm.AccessFlags;

public record ParsedField(
        String owner,
        String name,
        String descriptor,
        AccessFlags accessFlags,
        String signature) {
    public ParsedField {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(descriptor, "descriptor");
        Objects.requireNonNull(accessFlags, "accessFlags");
    }
}
