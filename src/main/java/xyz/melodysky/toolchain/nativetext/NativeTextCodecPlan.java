package xyz.melodysky.toolchain.nativetext;

import java.util.Objects;

/**
 * One per-use native-text codec schedule.
 *
 * <p>The plan is deliberately attached to one encoding rather than stored in
 * a process-wide codec table. Family, schedule, traversal and material all
 * vary with the build key, text purpose and stable use identity.</p>
 */
public final class NativeTextCodecPlan {
    private final NativeTextCodecFamily family;
    private final int schedule;
    private final boolean reverseTraversal;
    private final long key0;
    private final long key1;
    private final long key2;
    private final long step;
    private final long multiplier0;
    private final long multiplier1;
    private final int rotation0;
    private final int rotation1;
    private final int shift0;
    private final int shift1;
    private final int outputShift;

    NativeTextCodecPlan(
            NativeTextCodecFamily family,
            int schedule,
            boolean reverseTraversal,
            long key0,
            long key1,
            long key2,
            long step,
            long multiplier0,
            long multiplier1,
            int rotation0,
            int rotation1,
            int shift0,
            int shift1,
            int outputShift) {
        this.family = Objects.requireNonNull(family, "family");
        if (schedule < 0 || schedule > 2) {
            throw new IllegalArgumentException("native-text schedule must be in [0, 2]");
        }
        requireRotation(rotation0);
        requireRotation(rotation1);
        requireShift(shift0);
        requireShift(shift1);
        if (outputShift < 0 || outputShift > 56 || outputShift % 8 != 0) {
            throw new IllegalArgumentException(
                    "native-text output shift must select one 64-bit byte lane");
        }
        this.schedule = schedule;
        this.reverseTraversal = reverseTraversal;
        this.key0 = key0;
        this.key1 = key1;
        this.key2 = key2;
        this.step = step | 1L;
        this.multiplier0 = multiplier0 | 1L;
        this.multiplier1 = multiplier1 | 1L;
        this.rotation0 = rotation0;
        this.rotation1 = rotation1;
        this.shift0 = shift0;
        this.shift1 = shift1;
        this.outputShift = outputShift;
    }

    public NativeTextCodecFamily family() {
        return family;
    }

    public int schedule() {
        return schedule;
    }

    public boolean reverseTraversal() {
        return reverseTraversal;
    }

    /**
     * A non-secret shape label suitable for focused diversity tests.
     */
    public String shapeId() {
        return family.name()
                + ':'
                + schedule
                + ':'
                + (reverseTraversal ? "reverse" : "forward")
                + ':'
                + rotation0
                + ':'
                + rotation1
                + ':'
                + shift0
                + ':'
                + shift1
                + ':'
                + outputShift;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof NativeTextCodecPlan plan)) {
            return false;
        }
        return schedule == plan.schedule
                && reverseTraversal == plan.reverseTraversal
                && key0 == plan.key0
                && key1 == plan.key1
                && key2 == plan.key2
                && step == plan.step
                && multiplier0 == plan.multiplier0
                && multiplier1 == plan.multiplier1
                && rotation0 == plan.rotation0
                && rotation1 == plan.rotation1
                && shift0 == plan.shift0
                && shift1 == plan.shift1
                && outputShift == plan.outputShift
                && family == plan.family;
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                family,
                schedule,
                reverseTraversal,
                key0,
                key1,
                key2,
                step,
                multiplier0,
                multiplier1,
                rotation0,
                rotation1,
                shift0,
                shift1,
                outputShift);
    }

    long key0() {
        return key0;
    }

    long key1() {
        return key1;
    }

    long key2() {
        return key2;
    }

    long step() {
        return step;
    }

    long multiplier0() {
        return multiplier0;
    }

    long multiplier1() {
        return multiplier1;
    }

    int rotation0() {
        return rotation0;
    }

    int rotation1() {
        return rotation1;
    }

    int shift0() {
        return shift0;
    }

    int shift1() {
        return shift1;
    }

    int outputShift() {
        return outputShift;
    }

    int feistelRoundKey(int round) {
        if (round < 0 || round >= 4) {
            throw new IllegalArgumentException("native-text Feistel round must be in [0, 3]");
        }
        int keyIndex = feistelKeyIndex(round);
        return switch (keyIndex) {
            case 0 -> (int) key1;
            case 1 -> (int) (key1 >>> 32);
            case 2 -> (int) key2;
            case 3 -> (int) (key2 >>> 32);
            default -> throw new IllegalStateException("unreachable native-text key index");
        };
    }

    int feistelRotation(int round) {
        if (round < 0 || round >= 4) {
            throw new IllegalArgumentException("native-text Feistel round must be in [0, 3]");
        }
        return 1 + Math.floorMod(
                rotation0 + round * (schedule + 5),
                31);
    }

    int streamByte(int index) {
        if (index < 0) {
            throw new IllegalArgumentException("native-text stream index must not be negative");
        }
        long word = switch (family) {
            case WEYL_ARX -> weylArx(index);
            case DUAL_LANE_ARX -> dualLaneArx(index);
            case FEISTEL_32 -> feistel32(index);
            case FOLD_ROTATE -> foldRotate(index);
        };
        return (int) ((word >>> outputShift) & 0xffL);
    }

    private long weylArx(int index) {
        long ordinal = index + 1L;
        long lane = key0 + step * ordinal;
        long companion = key1 ^ (key2 + multiplier0 * ordinal);
        switch (schedule) {
            case 0 -> {
                lane ^= Long.rotateLeft(companion, rotation0);
                lane = (lane ^ (lane >>> shift0)) * multiplier1;
                lane ^= lane >>> shift1;
            }
            case 1 -> {
                lane += Long.rotateRight(companion, rotation0);
                lane ^= lane >>> shift1;
                lane *= multiplier1;
                lane ^= Long.rotateLeft(lane + key2, rotation1);
            }
            case 2 -> {
                lane ^= Long.rotateLeft(companion + key2, rotation1);
                lane += Long.rotateRight(lane ^ key1, rotation0);
                lane = (lane ^ (lane >>> shift0)) * multiplier1;
                lane ^= lane >>> shift1;
            }
            default -> throw new IllegalStateException("unreachable native-text schedule");
        }
        return lane ^ Long.rotateRight(companion, rotation1);
    }

    private long dualLaneArx(int index) {
        long ordinal = index + 1L;
        long first = key0 + step * ordinal;
        long second = key1 ^ (multiplier0 * ordinal + key2);
        switch (schedule) {
            case 0 -> {
                first += Long.rotateLeft(second, rotation0);
                second ^= Long.rotateRight(first, rotation1);
                first ^= second + multiplier1;
            }
            case 1 -> {
                second += Long.rotateRight(first, rotation1);
                first ^= Long.rotateLeft(second, rotation0);
                second += first ^ multiplier1;
            }
            case 2 -> {
                first ^= Long.rotateRight(second + key2, rotation0);
                second += Long.rotateLeft(first, rotation1);
                first += second ^ multiplier1;
            }
            default -> throw new IllegalStateException("unreachable native-text schedule");
        }
        first ^= first >>> shift0;
        second ^= second >>> shift1;
        return first ^ Long.rotateLeft(second, rotation1);
    }

    private long feistel32(int index) {
        long base = key0 + step * (index + 1L);
        int left = (int) (base >>> 32);
        int right = (int) base;
        for (int round = 0; round < 4; round++) {
            int mixed = Integer.rotateLeft(
                            right ^ feistelRoundKey(round),
                            feistelRotation(round))
                    * (int) multiplier0;
            int next = left ^ mixed;
            left = right;
            right = next;
        }
        return (Integer.toUnsignedLong(left) << 32)
                | Integer.toUnsignedLong(right);
    }

    private int feistelKeyIndex(int round) {
        int offset = schedule;
        if (reverseTraversal) {
            return Math.floorMod(3 - round + offset, 4);
        }
        return (round + offset) & 3;
    }

    private long foldRotate(int index) {
        long ordinal = index + 1L;
        long value = key0 ^ (step * ordinal);
        switch (schedule) {
            case 0 -> {
                value = Long.rotateLeft(value + key1, rotation0);
                value *= multiplier0;
                value ^= Long.rotateRight(value + key2, rotation1);
                value += multiplier1 * ordinal;
            }
            case 1 -> {
                value ^= Long.rotateRight(value + key2, rotation1);
                value *= multiplier1;
                value = Long.rotateLeft(value + key1, rotation0);
                value ^= multiplier0 * ordinal;
            }
            case 2 -> {
                value += Long.rotateLeft(key1 ^ ordinal, rotation0);
                value ^= value >>> shift0;
                value *= multiplier0;
                value = Long.rotateRight(value ^ key2, rotation1);
                value += multiplier1;
            }
            default -> throw new IllegalStateException("unreachable native-text schedule");
        }
        return value ^ (value >>> shift1);
    }

    private static void requireRotation(int value) {
        if (value <= 0 || value >= Long.SIZE) {
            throw new IllegalArgumentException(
                    "native-text rotation must be in [1, 63]");
        }
    }

    private static void requireShift(int value) {
        if (value <= 0 || value >= Long.SIZE) {
            throw new IllegalArgumentException(
                    "native-text shift must be in [1, 63]");
        }
    }
}
