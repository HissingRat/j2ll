package xyz.melodysky.toolchain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class NativeLibraryName {
    private static final String SAFE_NAME_PATTERN = "[A-Za-z0-9][A-Za-z0-9_-]{0,63}";

    private NativeLibraryName() {
    }

    public static String derive(String protectionSeed) {
        try {
            String seedHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(protectionSeed.getBytes(StandardCharsets.UTF_8)));
            return seedHash.substring(0, 16);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    public static boolean isSafe(String value) {
        return value != null && value.matches(SAFE_NAME_PATTERN);
    }
}
