package xyz.melodysky.toolchain;

import java.io.IOException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public interface ZigArchiveVerifier {
    void verify(Path archive, ZigArchiveMetadata metadata) throws IOException;

    String policy();

    static ZigArchiveVerifier sha256() {
        return new ZigArchiveVerifier() {
            @Override
            public void verify(Path archive, ZigArchiveMetadata metadata) throws IOException {
                String actual = sha256Hex(archive);
                if (!actual.equalsIgnoreCase(metadata.expectedSha256())) {
                    throw new IOException("managed Zig archive checksum mismatch for " + metadata.archiveName()
                            + ": expected " + metadata.expectedSha256() + " but found " + actual);
                }
            }

            @Override
            public String policy() {
                return "sha256Required;signatureStatus=notVerifiedBoundary";
            }
        };
    }

    static ZigArchiveVerifier boundaryOnly() {
        return new ZigArchiveVerifier() {
            @Override
            public void verify(Path archive, ZigArchiveMetadata metadata) {
                if (metadata.expectedSha256().isBlank()) {
                    throw new IllegalStateException("unexpected managed Zig checksum metadata shape");
                }
            }

            @Override
            public String policy() {
                return "checksumSignatureInterfacePresent:notYetHardcoded";
            }
        };
    }

    private static String sha256Hex(Path archive) throws IOException {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(java.nio.file.Files.readAllBytes(archive)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
