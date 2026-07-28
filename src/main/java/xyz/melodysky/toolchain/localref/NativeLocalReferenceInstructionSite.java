package xyz.melodysky.toolchain.localref;

import java.util.Objects;

/**
 * Stable location of an instruction in one SSA method.
 */
public record NativeLocalReferenceInstructionSite(
        String blockName,
        int instructionIndex) implements Comparable<NativeLocalReferenceInstructionSite> {
    public NativeLocalReferenceInstructionSite {
        Objects.requireNonNull(blockName, "blockName");
        if (blockName.isBlank()) {
            throw new IllegalArgumentException("blockName must not be blank");
        }
        if (instructionIndex < 0) {
            throw new IllegalArgumentException(
                    "instructionIndex must not be negative");
        }
    }

    @Override
    public int compareTo(NativeLocalReferenceInstructionSite other) {
        int block = blockName.compareTo(other.blockName);
        return block != 0
                ? block
                : Integer.compare(instructionIndex, other.instructionIndex);
    }
}
