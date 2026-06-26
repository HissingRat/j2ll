package xyz.melodysky.toolchain;

import java.io.IOException;
import java.nio.file.Path;

public interface ZigArchiveVerifier {
    void verify(Path archive, ZigArchiveMetadata metadata) throws IOException;

    String policy();

    static ZigArchiveVerifier boundaryOnly() {
        return new ZigArchiveVerifier() {
            @Override
            public void verify(Path archive, ZigArchiveMetadata metadata) {
                if (!metadata.expectedSha256().equals(ZigArchiveResolver.CHECKSUM_BOUNDARY)) {
                    throw new IllegalStateException("unexpected managed Zig checksum metadata shape");
                }
            }

            @Override
            public String policy() {
                return "checksumSignatureInterfacePresent:notYetHardcoded";
            }
        };
    }
}
