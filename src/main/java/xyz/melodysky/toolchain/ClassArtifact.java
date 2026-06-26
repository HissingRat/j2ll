package xyz.melodysky.toolchain;

import java.util.Objects;

public record ClassArtifact(
        String internalName,
        String fullHash,
        int hashPrefixLength,
        String directory,
        String sourceEntry,
        String safeInternalName) {
    public ClassArtifact {
        Objects.requireNonNull(internalName, "internalName");
        Objects.requireNonNull(fullHash, "fullHash");
        Objects.requireNonNull(directory, "directory");
        Objects.requireNonNull(sourceEntry, "sourceEntry");
        Objects.requireNonNull(safeInternalName, "safeInternalName");
    }
}
