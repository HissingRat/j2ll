package xyz.melodysky.packaging;

import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

public final class FallbackBlobCodec {
    public static final String ENCODING_VERSION = "fallbackBlobEncodingV1";
    public static final String COMPRESSION_ALGORITHM = "j2ll-rle-byte-pairs-v1";
    public static final String ENCRYPTION_ALGORITHM = "xor-sha256-key-stream-v1";

    public EncodedFallbackBlob encode(byte[] originalBytes, String keyMaterial) {
        Objects.requireNonNull(originalBytes, "originalBytes");
        Objects.requireNonNull(keyMaterial, "keyMaterial");
        byte[] key = sha256Bytes(keyMaterial.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        byte[] compressed = rleEncode(originalBytes);
        byte[] encoded = xor(compressed, key);
        return new EncodedFallbackBlob(
                originalBytes.clone(),
                encoded,
                key,
                sha256Hex(originalBytes),
                sha256Hex(encoded),
                COMPRESSION_ALGORITHM,
                ENCRYPTION_ALGORITHM,
                ENCODING_VERSION);
    }

    public byte[] decode(EncodedFallbackBlob blob) {
        Objects.requireNonNull(blob, "blob");
        if (!sha256Hex(blob.encodedBytes()).equals(blob.encodedSha256())) {
            throw new IllegalArgumentException("fallback blob encoded SHA-256 mismatch");
        }
        byte[] compressed = xor(blob.encodedBytes(), blob.keyBytes());
        byte[] decoded = rleDecode(compressed);
        if (!sha256Hex(decoded).equals(blob.originalSha256())) {
            throw new IllegalArgumentException("fallback blob original SHA-256 mismatch");
        }
        return decoded;
    }

    private byte[] rleEncode(byte[] original) {
        ByteArrayOutputStream output = new ByteArrayOutputStream(original.length * 2 + 4);
        output.write((original.length >>> 24) & 0xff);
        output.write((original.length >>> 16) & 0xff);
        output.write((original.length >>> 8) & 0xff);
        output.write(original.length & 0xff);
        for (int index = 0; index < original.length;) {
            int value = original[index] & 0xff;
            int count = 1;
            while (index + count < original.length
                    && count < 255
                    && (original[index + count] & 0xff) == value) {
                count++;
            }
            output.write(count);
            output.write(value);
            index += count;
        }
        return output.toByteArray();
    }

    private byte[] rleDecode(byte[] compressed) {
        if (compressed.length < 4) {
            throw new IllegalArgumentException("fallback blob compressed payload is too short");
        }
        int expectedLength = ((compressed[0] & 0xff) << 24)
                | ((compressed[1] & 0xff) << 16)
                | ((compressed[2] & 0xff) << 8)
                | (compressed[3] & 0xff);
        int maxDecodedLength = ((compressed.length - 4) / 2) * 255;
        if (expectedLength < 0 || expectedLength > maxDecodedLength) {
            throw new IllegalArgumentException("fallback blob decoded length exceeds compressed payload capacity");
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream(expectedLength);
        for (int index = 4; index < compressed.length; index += 2) {
            if (index + 1 >= compressed.length) {
                throw new IllegalArgumentException("fallback blob compressed payload has a truncated run");
            }
            int count = compressed[index] & 0xff;
            int value = compressed[index + 1] & 0xff;
            if (count == 0) {
                throw new IllegalArgumentException("fallback blob compressed payload has an empty run");
            }
            for (int repeat = 0; repeat < count; repeat++) {
                output.write(value);
            }
        }
        byte[] decoded = output.toByteArray();
        if (decoded.length != expectedLength) {
            throw new IllegalArgumentException("fallback blob decoded length mismatch");
        }
        return decoded;
    }

    private byte[] xor(byte[] input, byte[] key) {
        byte[] output = new byte[input.length];
        for (int index = 0; index < input.length; index++) {
            int stream = (key[index % key.length] & 0xff) ^ ((index * 31 + (index >>> 3)) & 0xff);
            output[index] = (byte) ((input[index] & 0xff) ^ stream);
        }
        return output;
    }

    private String sha256Hex(byte[] bytes) {
        return HexFormat.of().formatHex(sha256Bytes(bytes));
    }

    private byte[] sha256Bytes(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required", exception);
        }
    }
}
