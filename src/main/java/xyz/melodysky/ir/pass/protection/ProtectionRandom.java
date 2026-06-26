package xyz.melodysky.ir.pass.protection;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class ProtectionRandom {
    private final long seed;

    public ProtectionRandom(long seed) {
        this.seed = seed;
    }

    public String token(String purpose, String stableInput, int hexChars) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(Long.toString(seed).getBytes(StandardCharsets.UTF_8));
            digest.update((byte) ':');
            digest.update(purpose.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) ':');
            digest.update(stableInput.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest.digest()).substring(0, hexChars);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
