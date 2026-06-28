package xyz.melodysky.toolchain;

import java.net.URI;
import java.util.Objects;

public record ZigArchiveMetadata(
        String archiveName,
        URI downloadUri,
        boolean zipArchive,
        String expectedSha256,
        String signatureAvailabilityPolicy) {
    public ZigArchiveMetadata(String archiveName, URI downloadUri, boolean zipArchive, String expectedSha256) {
        this(archiveName, downloadUri, zipArchive, expectedSha256, "notVerifiedBoundary");
    }

    public ZigArchiveMetadata {
        Objects.requireNonNull(archiveName, "archiveName");
        Objects.requireNonNull(downloadUri, "downloadUri");
        Objects.requireNonNull(expectedSha256, "expectedSha256");
        Objects.requireNonNull(signatureAvailabilityPolicy, "signatureAvailabilityPolicy");
    }
}
