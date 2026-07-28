package xyz.melodysky.toolchain;

import java.util.Objects;

/** Emits the defining-class lookup used by instance LLVM wrappers. */
final class HostJniDefiningOwnerSource {
    void appendLookup(StringBuilder source, String ownerInternalName) {
        appendLookup(source, ownerInternalName, "owner");
    }

    void appendLookup(
            StringBuilder source,
            String ownerInternalName,
            String variableName) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(ownerInternalName, "ownerInternalName");
        Objects.requireNonNull(variableName, "variableName");
        if (ownerInternalName.isBlank()
                || ownerInternalName.indexOf('.') >= 0
                || ownerInternalName.indexOf('"') >= 0
                || ownerInternalName.indexOf('\\') >= 0) {
            throw new IllegalArgumentException(
                    "invalid defining owner internal name: " + ownerInternalName);
        }
        if (!variableName.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw new IllegalArgumentException(
                    "invalid defining owner C variable: " + variableName);
        }
        source.append("    jclass ")
                .append(variableName)
                .append(" = (*env)->FindClass(env, \"")
                .append(ownerInternalName)
                .append("\");\n")
                .append("    if (")
                .append(variableName)
                .append(" == NULL) {\n");
    }
}
