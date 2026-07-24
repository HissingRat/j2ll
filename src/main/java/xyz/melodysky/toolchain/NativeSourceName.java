package xyz.melodysky.toolchain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class NativeSourceName {
    private static final int MAX_READABLE_PREFIX = 80;

    private NativeSourceName() {
    }

    public static String llvmFileName(String owner) {
        StringBuilder prefix = new StringBuilder();
        for (int index = 0; index < owner.length() && prefix.length() < MAX_READABLE_PREFIX; index++) {
            char ch = owner.charAt(index);
            prefix.append(isAsciiAlphaNumeric(ch) ? ch : '_');
        }
        if (prefix.isEmpty()) {
            prefix.append("class");
        }
        return prefix + "__" + sha256(owner).substring(0, 16) + ".ll";
    }

    private static boolean isAsciiAlphaNumeric(char ch) {
        return ch >= 'a' && ch <= 'z'
                || ch >= 'A' && ch <= 'Z'
                || ch >= '0' && ch <= '9';
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
