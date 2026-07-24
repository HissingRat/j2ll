package xyz.melodysky.toolchain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class CIdentifier {
    private static final int HASH_HEX_LENGTH = 32;

    private CIdentifier() {
    }

    public static String forIdentity(String identity) {
        return "h_" + sha256(identity).substring(0, HASH_HEX_LENGTH);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
