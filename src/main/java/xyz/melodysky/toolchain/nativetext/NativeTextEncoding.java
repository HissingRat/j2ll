package xyz.melodysky.toolchain.nativetext;

import java.util.Arrays;
import java.util.Objects;

/**
 * One independently encoded, NUL-terminated UTF-8 native text value.
 *
 * <p>This model deliberately represents one use. It has no collection/table
 * API, so consumers cannot accidentally recreate a process-wide
 * pointer/length/key directory.</p>
 */
public final class NativeTextEncoding {
    private final String symbol;
    private final NativeTextPurpose purpose;
    private final int utf8Length;
    private final byte[] ciphertext;
    private final NativeTextCodecPlan codecPlan;
    private final NativeTextStoragePermutation storagePermutation;

    NativeTextEncoding(
            String symbol,
            NativeTextPurpose purpose,
            int utf8Length,
            byte[] ciphertext,
            NativeTextCodecPlan codecPlan,
            NativeTextStoragePermutation storagePermutation) {
        this.symbol = Objects.requireNonNull(symbol, "symbol");
        this.purpose = Objects.requireNonNull(purpose, "purpose");
        if (utf8Length < 0) {
            throw new IllegalArgumentException("UTF-8 length must not be negative");
        }
        this.ciphertext = Objects.requireNonNull(ciphertext, "ciphertext").clone();
        if (this.ciphertext.length != utf8Length + 1) {
            throw new IllegalArgumentException(
                    "native text ciphertext must contain one encoded NUL terminator");
        }
        this.utf8Length = utf8Length;
        this.codecPlan = Objects.requireNonNull(codecPlan, "codecPlan");
        this.storagePermutation = Objects.requireNonNull(
                storagePermutation,
                "storagePermutation");
        if (this.storagePermutation.length() != this.ciphertext.length) {
            throw new IllegalArgumentException(
                    "native-text storage permutation length must match its ciphertext");
        }
    }

    public String symbol() {
        return symbol;
    }

    public NativeTextPurpose purpose() {
        return purpose;
    }

    public int utf8Length() {
        return utf8Length;
    }

    public int decodedBufferLength() {
        return ciphertext.length;
    }

    public byte[] ciphertext() {
        return ciphertext.clone();
    }

    public NativeTextCodecPlan codecPlan() {
        return codecPlan;
    }

    public NativeTextStoragePermutation storagePermutation() {
        return storagePermutation;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof NativeTextEncoding encoding)) {
            return false;
        }
        return utf8Length == encoding.utf8Length
                && symbol.equals(encoding.symbol)
                && purpose == encoding.purpose
                && codecPlan.equals(encoding.codecPlan)
                && storagePermutation.equals(encoding.storagePermutation)
                && Arrays.equals(ciphertext, encoding.ciphertext);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(
                symbol,
                purpose,
                utf8Length,
                codecPlan,
                storagePermutation);
        return 31 * result + Arrays.hashCode(ciphertext);
    }
}
