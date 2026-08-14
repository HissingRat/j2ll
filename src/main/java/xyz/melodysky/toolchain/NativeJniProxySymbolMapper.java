package xyz.melodysky.toolchain;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import xyz.melodysky.toolchain.nativetext.NativeTextBuildKey;

/** Domain-separated hash-only symbols for LLVM JNI proxies and bridges. */
final class NativeJniProxySymbolMapper {
    private static final byte[] DOMAIN =
            "j2ll/llvm-jni-proxy/v1".getBytes(StandardCharsets.UTF_8);

    String proxySymbol(
            NativeTextBuildKey buildKey,
            String methodKey) {
        return symbol(buildKey, methodKey, "proxy");
    }

    List<String> bridgeSymbols(
            NativeTextBuildKey buildKey,
            String methodKey,
            int count) {
        ArrayList<String> result = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            result.add(symbol(
                    buildKey,
                    methodKey,
                    "bridge:" + index));
        }
        return List.copyOf(result);
    }

    private String symbol(
            NativeTextBuildKey buildKey,
            String methodKey,
            String purpose) {
        Objects.requireNonNull(buildKey, "buildKey");
        Objects.requireNonNull(methodKey, "methodKey");
        MessageDigest digest = sha256();
        update(digest, DOMAIN);
        update(digest, buildKey.bytes());
        update(digest, methodKey.getBytes(StandardCharsets.UTF_8));
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
