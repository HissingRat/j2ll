package xyz.melodysky.toolchain;

import java.util.List;
import java.util.Objects;

public record ClassArtifactInput(
        String internalName,
        String sourceEntry,
        List<MethodArtifactInput> methods) {
    public ClassArtifactInput {
        Objects.requireNonNull(internalName, "internalName");
        Objects.requireNonNull(sourceEntry, "sourceEntry");
        methods = List.copyOf(Objects.requireNonNull(methods, "methods"));
    }
}
