package xyz.melodysky.toolchain;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Hash-only block and SSA names scoped to one synthesized proxy function. */
final class NativeJniProxyLocalNameMapper {
    String block(String domain, String purpose) {
        return token(domain, "block:" + purpose);
    }

    String value(String domain, String purpose) {
        return "%" + token(domain, "value:" + purpose);
    }

    private String token(String domain, String purpose) {
        MessageDigest digest = sha256();
        update(digest, domain.getBytes(StandardCharsets.UTF_8));
        update(digest, purpose.getBytes(StandardCharsets.UTF_8));
        byte[] value = digest.digest();
        StringBuilder result = new StringBuilder(32);
        for (int index = 0; index < 16; index++) {
            result.append((char) ('a' + ((value[index] >>> 4) & 0x0f)));
            result.append((char) ('a' + (value[index] & 0x0f)));
        }
        return result.toString();
    }

    private void update(MessageDigest digest, byte[] value) {
        digest.update(ByteBuffer.allocate(Integer.BYTES)
                .putInt(value.length)
                .array());
        digest.update(value);
    }

    private MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
