package xyz.melodysky.protection.audit;

import java.util.Objects;

/** Hash-only occurrence metric for caller-supplied sensitive plaintext. */
public record SensitivePlaintextMetric(
        String literalHash,
        int nativeUtf8Occurrences,
        int nativeUtf16LeOccurrences,
        int generatedCOccurrences)
        implements Comparable<SensitivePlaintextMetric> {
    public SensitivePlaintextMetric {
        Objects.requireNonNull(literalHash, "literalHash");
        if (literalHash.isBlank()
                || nativeUtf8Occurrences < 0
                || nativeUtf16LeOccurrences < 0
                || generatedCOccurrences < 0) {
            throw new IllegalArgumentException("sensitive plaintext metric is invalid");
        }
    }

    public int totalOccurrences() {
        return nativeUtf8Occurrences
                + nativeUtf16LeOccurrences
                + generatedCOccurrences;
    }

    @Override
    public int compareTo(SensitivePlaintextMetric other) {
        return literalHash.compareTo(other.literalHash);
    }
}
