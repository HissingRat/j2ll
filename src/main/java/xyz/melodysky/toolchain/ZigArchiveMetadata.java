package xyz.melodysky.toolchain;

import java.net.URI;
import java.util.Objects;

public record ZigArchiveMetadata(
        String archiveName,
        URI downloadUri,
        boolean zipArchive,
        String expectedSha256) {
    public ZigArchiveMetadata {
        Objects.requireNonNull(archiveName, "archiveName");
        Objects.requireNonNull(downloadUri, "downloadUri");
        Objects.requireNonNull(expectedSha256, "expectedSha256");
    }
}
