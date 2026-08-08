package xyz.melodysky.toolchain.nativetext;

import java.util.List;
import java.util.Objects;

/**
 * One function-local encrypted record containing several C string values.
 *
 * <p>The record has one site-bound codec and one affine ciphertext array. Its
 * slices are build-time offsets only; no native pointer/offset directory is
 * emitted.</p>
 */
final class NativeTextTupleEncoding {
    private final NativeTextEncoding record;
    private final List<Slice> slices;

    NativeTextTupleEncoding(
            NativeTextEncoding record,
            List<Slice> slices) {
        this.record = Objects.requireNonNull(record, "record");
        this.slices = List.copyOf(slices);
        if (this.slices.isEmpty()) {
            throw new IllegalArgumentException(
                    "native-text tuple must contain at least one component");
        }
        for (Slice slice : this.slices) {
            if (slice.offset() < 0
                    || slice.length() < 0
                    || slice.offset() + slice.length()
                            >= record.decodedBufferLength()) {
                throw new IllegalArgumentException(
                        "native-text tuple slice is outside its record");
            }
        }
    }

    NativeTextEncoding record() {
        return record;
    }

    Slice slice(int componentIndex) {
        return slices.get(componentIndex);
    }

    int componentCount() {
        return slices.size();
    }

    record Slice(int offset, int length, LanePlan lanePlan) {
        Slice {
            Objects.requireNonNull(lanePlan, "lanePlan");
        }
    }

    record LanePlan(
            int seed,
            int step,
            int multiplier,
            int shift0,
            int shift1,
            int outputShift) {
        LanePlan {
            step |= 1;
            multiplier |= 1;
            if (shift0 <= 0 || shift0 >= Integer.SIZE
                    || shift1 <= 0 || shift1 >= Integer.SIZE
                    || outputShift < 0
                    || outputShift > 24
                    || outputShift % 8 != 0) {
                throw new IllegalArgumentException(
                        "native-text tuple lane plan is invalid");
            }
        }

        int maskByte(int localIndex) {
            if (localIndex < 0) {
                throw new IllegalArgumentException(
                        "native-text tuple lane index must not be negative");
            }
            int value = seed + step * (localIndex + 1);
            value ^= value >>> shift0;
            value *= multiplier;
            value ^= value >>> shift1;
            return (value >>> outputShift) & 0xff;
        }
    }
}
