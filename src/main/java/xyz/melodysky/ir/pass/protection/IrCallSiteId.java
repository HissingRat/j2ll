package xyz.melodysky.ir.pass.protection;

import java.util.Objects;

/**
 * Stable location of one call in an SSA method.
 *
 * <p>The instruction index is local to the named basic block. This id is an
 * analysis/planning key and is deliberately not copied into protected call
 * instructions.</p>
 */
public record IrCallSiteId(String callerMethodKey, String blockName, int instructionIndex)
        implements Comparable<IrCallSiteId> {
    public IrCallSiteId {
        requireText(callerMethodKey, "callerMethodKey");
        requireText(blockName, "blockName");
        if (instructionIndex < 0) {
            throw new IllegalArgumentException("instructionIndex must be non-negative");
        }
    }

    @Override
    public int compareTo(IrCallSiteId other) {
        int byMethod = callerMethodKey.compareTo(other.callerMethodKey);
        if (byMethod != 0) {
            return byMethod;
        }
        int byBlock = blockName.compareTo(other.blockName);
        if (byBlock != 0) {
            return byBlock;
        }
        return Integer.compare(instructionIndex, other.instructionIndex);
    }

    public String stableKey() {
        return callerMethodKey + "@" + blockName + ":" + instructionIndex;
    }

    private static void requireText(String value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
