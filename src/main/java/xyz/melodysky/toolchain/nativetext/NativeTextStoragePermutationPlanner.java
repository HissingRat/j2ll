package xyz.melodysky.toolchain.nativetext;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;

/**
 * Derives one build-, purpose- and use-bound affine storage permutation.
 */
final class NativeTextStoragePermutationPlanner {
    private static final byte[] KDF_DOMAIN =
            "j2ll-native-text-storage-affine-v1"
                    .getBytes(StandardCharsets.US_ASCII);

    NativeTextStoragePermutation plan(
            NativeTextBuildKey buildKey,
            NativeTextPurpose purpose,
            String stableUseIdentity,
            int length) {
        Objects.requireNonNull(buildKey, "buildKey");
        Objects.requireNonNull(purpose, "purpose");
        Objects.requireNonNull(stableUseIdentity, "stableUseIdentity");
        if (stableUseIdentity.isBlank()) {
            throw new IllegalArgumentException(
                    "native text use identity must not be blank");
        }
        if (length <= 1) {
            return NativeTextStoragePermutation.identity(length);
        }

        ByteBuffer seed = ByteBuffer.wrap(derive(
                buildKey,
                purpose,
                stableUseIdentity,
                length));
        int offset = (int) Math.floorMod(
                seed.getLong(),
                (long) length);
        int stride = selectStride(seed.getLong(), length);
        if (offset == 0 && stride == 1) {
            offset = 1 + (int) Math.floorMod(
                    seed.getLong(),
                    (long) length - 1L);
        }
        return new NativeTextStoragePermutation(length, offset, stride);
    }

    private int selectStride(long seed, int length) {
        boolean hasNonTrivialUnit = false;
        for (int candidate = 2;
                candidate < length - 1;
                candidate++) {
            if (NativeTextStoragePermutation.areCoprime(
                    candidate,
                    length)) {
                hasNonTrivialUnit = true;
                break;
            }
        }

        int eligible = 0;
        for (int candidate = 1; candidate < length; candidate++) {
            if (eligibleStride(
                    candidate,
                    length,
                    hasNonTrivialUnit)) {
                eligible++;
            }
        }
        int selected = (int) Math.floorMod(seed, (long) eligible);
        for (int candidate = 1; candidate < length; candidate++) {
            if (!eligibleStride(
                    candidate,
                    length,
                    hasNonTrivialUnit)) {
                continue;
            }
            if (selected-- == 0) {
                return candidate;
            }
        }
        throw new IllegalStateException(
                "native-text affine stride selection has no eligible unit");
    }

    private boolean eligibleStride(
            int candidate,
            int length,
            boolean excludeTrivialUnits) {
        return (!excludeTrivialUnits
                        || (candidate != 1
                                && candidate != length - 1))
                && NativeTextStoragePermutation.areCoprime(
                        candidate,
                        length);
    }

    private byte[] derive(
            NativeTextBuildKey buildKey,
            NativeTextPurpose purpose,
            String stableUseIdentity,
            int length) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(KDF_DOMAIN);
            updateLengthPrefixed(digest, buildKey.bytes());
            updateLengthPrefixed(
                    digest,
                    purpose.domain().getBytes(StandardCharsets.UTF_8));
            updateLengthPrefixed(
                    digest,
                    stableUseIdentity.getBytes(StandardCharsets.UTF_8));
            digest.update(ByteBuffer.allocate(Integer.BYTES)
                    .putInt(length)
                    .array());
            return digest.digest();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(
                    "SHA-256 is unavailable",
                    exception);
        }
    }

    private void updateLengthPrefixed(
            MessageDigest digest,
            byte[] value) {
        digest.update(ByteBuffer.allocate(Integer.BYTES)
                .putInt(value.length)
                .array());
        digest.update(value);
    }
}
