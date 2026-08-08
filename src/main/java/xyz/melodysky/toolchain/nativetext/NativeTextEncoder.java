package xyz.melodysky.toolchain.nativetext;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Domain-separated, build-keyed native text encoder.
 *
 * <p>This is an at-rest obfuscation primitive, not cryptographic secret
 * storage. Native code necessarily contains enough information to recover a
 * value when the JVM operation needs it.</p>
 */
public final class NativeTextEncoder {
    private static final byte[] KDF_DOMAIN =
            "j2ll-native-text-v3".getBytes(StandardCharsets.US_ASCII);
    private final NativeTextStoragePermutationPlanner storagePermutationPlanner =
            new NativeTextStoragePermutationPlanner();

    public NativeTextEncoding encode(
            NativeTextBuildKey buildKey,
            NativeTextPurpose purpose,
            String stableUseIdentity,
            String plaintext) {
        Objects.requireNonNull(plaintext, "plaintext");
        return encodeBytes(
                buildKey,
                purpose,
                stableUseIdentity,
                plaintext.getBytes(StandardCharsets.UTF_8));
    }

    public NativeTextEncoding encodeBytes(
            NativeTextBuildKey buildKey,
            NativeTextPurpose purpose,
            String stableUseIdentity,
            byte[] plaintext) {
        Objects.requireNonNull(buildKey, "buildKey");
        Objects.requireNonNull(purpose, "purpose");
        Objects.requireNonNull(stableUseIdentity, "stableUseIdentity");
        Objects.requireNonNull(plaintext, "plaintext");
        if (stableUseIdentity.isBlank()) {
            throw new IllegalArgumentException("native text use identity must not be blank");
        }

        byte[] plain = plaintext.clone();
        byte[] terminated = Arrays.copyOf(plain, plain.length + 1);
        byte[] derivation = derive(
                buildKey.bytes(),
                purpose.domain().getBytes(StandardCharsets.UTF_8),
                stableUseIdentity.getBytes(StandardCharsets.UTF_8),
                plain);
        NativeTextCodecPlan codecPlan = codecPlan(derivation);
        NativeTextStoragePermutation storagePermutation =
                storagePermutationPlanner.plan(
                        buildKey,
                        purpose,
                        stableUseIdentity,
                        terminated.length);
        byte[] ciphertext = storagePermutation.store(
                xorStream(terminated, codecPlan));
        String symbol = "j2ll_nt_"
                + HexFormat.of().formatHex(symbolDigest(derivation), 0, 12);
        return new NativeTextEncoding(
                symbol,
                purpose,
                plain.length,
                ciphertext,
                codecPlan,
                storagePermutation);
    }

    public String decodeUtf8(NativeTextEncoding encoding) {
        return new String(decodeBytes(encoding), StandardCharsets.UTF_8);
    }

    public byte[] decodeBytes(NativeTextEncoding encoding) {
        Objects.requireNonNull(encoding, "encoding");
        byte[] stored = encoding.ciphertext();
        byte[] terminated = new byte[stored.length];
        for (int logicalIndex = 0;
                logicalIndex < stored.length;
                logicalIndex++) {
            int physicalIndex = encoding.storagePermutation()
                    .physicalIndex(logicalIndex);
            terminated[logicalIndex] = (byte) (
                    stored[physicalIndex]
                            ^ encoding.codecPlan()
                                    .streamByte(logicalIndex));
        }
        if (terminated.length == 0 || terminated[terminated.length - 1] != 0) {
            throw new IllegalArgumentException("native text encoding has no decoded NUL terminator");
        }
        return Arrays.copyOf(terminated, terminated.length - 1);
    }

    private byte[] derive(byte[] buildKey, byte[] purpose, byte[] useIdentity, byte[] plaintext) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-512");
            digest.update(KDF_DOMAIN);
            updateLengthPrefixed(digest, buildKey);
            updateLengthPrefixed(digest, purpose);
            updateLengthPrefixed(digest, useIdentity);
            updateLengthPrefixed(digest, plaintext);
            return digest.digest();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-512 is unavailable", exception);
        }
    }

    private void updateLengthPrefixed(MessageDigest digest, byte[] value) {
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(value.length).array());
        digest.update(value);
    }

    private NativeTextCodecPlan codecPlan(byte[] derivation) {
        ByteBuffer words = ByteBuffer.wrap(derivation);
        NativeTextCodecFamily[] families = NativeTextCodecFamily.values();
        NativeTextCodecFamily family = families[
                Byte.toUnsignedInt(derivation[63]) % families.length];
        int schedule = Byte.toUnsignedInt(derivation[62]) % 3;
        boolean reverseTraversal = (derivation[61] & 1) != 0;
        int rotation0 = 5 + Byte.toUnsignedInt(derivation[56]) % 54;
        int rotation1 = 5 + Byte.toUnsignedInt(derivation[57]) % 54;
        int shift0 = 7 + Byte.toUnsignedInt(derivation[58]) % 23;
        int shift1 = 7 + Byte.toUnsignedInt(derivation[59]) % 23;
        int outputShift = (Byte.toUnsignedInt(derivation[60]) & 3) * 8;
        return new NativeTextCodecPlan(
                family,
                schedule,
                reverseTraversal,
                words.getLong(0),
                words.getLong(8),
                words.getLong(16),
                words.getLong(24),
                words.getLong(32),
                words.getLong(40),
                rotation0,
                rotation1,
                shift0,
                shift1,
                outputShift);
    }

    private byte[] symbolDigest(byte[] derivation) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(KDF_DOMAIN);
            digest.update((byte) 0);
            digest.update("symbol".getBytes(StandardCharsets.US_ASCII));
            digest.update(derivation);
            return digest.digest();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private byte[] xorStream(byte[] input, NativeTextCodecPlan codecPlan) {
        byte[] result = new byte[input.length];
        for (int index = 0; index < input.length; index++) {
            result[index] = (byte) (input[index] ^ codecPlan.streamByte(index));
        }
        return result;
    }
}
