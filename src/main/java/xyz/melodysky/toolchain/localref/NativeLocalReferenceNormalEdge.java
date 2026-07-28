package xyz.melodysky.toolchain.localref;

import java.util.Objects;

/**
 * Stable ordinal of a normal CFG edge.
 *
 * <p>GOTO uses ordinal {@code 0}; BRANCH uses true {@code 0} and false
 * {@code 1}; SWITCH uses default {@code 0} followed by the stable case order.</p>
 */
public record NativeLocalReferenceNormalEdge(
        String sourceBlock,
        int ordinal,
        String targetBlock) implements Comparable<NativeLocalReferenceNormalEdge> {
    public NativeLocalReferenceNormalEdge {
        Objects.requireNonNull(sourceBlock, "sourceBlock");
        Objects.requireNonNull(targetBlock, "targetBlock");
        if (sourceBlock.isBlank() || targetBlock.isBlank()) {
            throw new IllegalArgumentException(
                    "normal-edge block names must not be blank");
        }
        if (ordinal < 0) {
            throw new IllegalArgumentException(
                    "normal-edge ordinal must not be negative");
        }
    }

    @Override
    public int compareTo(NativeLocalReferenceNormalEdge other) {
        int source = sourceBlock.compareTo(other.sourceBlock);
        if (source != 0) {
            return source;
        }
        int order = Integer.compare(ordinal, other.ordinal);
        return order != 0 ? order : targetBlock.compareTo(other.targetBlock);
    }
}
