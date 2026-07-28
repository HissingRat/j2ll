package xyz.melodysky.toolchain.nativetext;

import java.util.Objects;

/**
 * One table-free affine mapping from logical ciphertext positions to
 * their physical storage positions.
 *
 * <p>The mapping stores logical byte {@code i} at
 * {@code (offset + i * stride) mod length}. A coprime stride makes that mapping
 * bijective without adding a permutation table or any ciphertext bytes.</p>
 */
public record NativeTextStoragePermutation(
        int length,
        int offset,
        int stride) {
    public NativeTextStoragePermutation {
        if (length < 0) {
            throw new IllegalArgumentException(
                    "native-text storage length must not be negative");
        }
        if (length <= 1) {
            if (offset != 0 || stride != 1) {
                throw new IllegalArgumentException(
                        "empty and singleton native-text storage must use the identity mapping");
            }
        } else {
            if (offset < 0 || offset >= length) {
                throw new IllegalArgumentException(
                        "native-text storage offset must be inside the ciphertext");
            }
            if (stride <= 0
                    || stride >= length
                    || !areCoprime(stride, length)) {
                throw new IllegalArgumentException(
                        "native-text storage stride must be positive, smaller than the ciphertext, and coprime with its length");
            }
        }
    }

    static NativeTextStoragePermutation identity(int length) {
        return new NativeTextStoragePermutation(length, 0, 1);
    }

    public int physicalIndex(int logicalIndex) {
        if (logicalIndex < 0 || logicalIndex >= length) {
            throw new IndexOutOfBoundsException(
                    "native-text logical index is outside the ciphertext");
        }
        if (length <= 1) {
            return logicalIndex;
        }
        return (int) ((offset + (long) logicalIndex * stride) % length);
    }

    public boolean isIdentity() {
        return offset == 0 && stride == 1;
    }

    byte[] store(byte[] logicalCiphertext) {
        Objects.requireNonNull(logicalCiphertext, "logicalCiphertext");
        if (logicalCiphertext.length != length) {
            throw new IllegalArgumentException(
                    "logical ciphertext length does not match its storage permutation");
        }
        byte[] stored = new byte[length];
        for (int logicalIndex = 0;
                logicalIndex < logicalCiphertext.length;
                logicalIndex++) {
            stored[physicalIndex(logicalIndex)] =
                    logicalCiphertext[logicalIndex];
        }
        return stored;
    }

    static boolean areCoprime(int first, int second) {
        int left = first;
        int right = second;
        while (right != 0) {
            int remainder = left % right;
            left = right;
            right = remainder;
        }
        return left == 1;
    }
}
