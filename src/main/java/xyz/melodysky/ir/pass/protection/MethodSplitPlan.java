package xyz.melodysky.ir.pass.protection;

import java.util.List;
import java.util.Objects;
import xyz.melodysky.ir.model.IrValue;

/**
 * Immutable boundary between method-splitting analysis and the caller/helper rewrite.
 *
 * <p>The helper described here is compiler-internal. It must never be added to the
 * Java-visible method list consumed by packaging or native registration.</p>
 */
public record MethodSplitPlan(
        String sourceMethodKey,
        String sourceBlock,
        int startInclusive,
        int endExclusive,
        List<IrValue> liveIns,
        List<IrValue> liveOuts,
        List<String> successorBlocks,
        String helperName,
        String helperDescriptor,
        String nativeSymbol) {
    public MethodSplitPlan {
        Objects.requireNonNull(sourceMethodKey, "sourceMethodKey");
        Objects.requireNonNull(sourceBlock, "sourceBlock");
        if (startInclusive < 0 || endExclusive <= startInclusive) {
            throw new IllegalArgumentException("method split region must be non-empty");
        }
        liveIns = List.copyOf(Objects.requireNonNull(liveIns, "liveIns"));
        liveOuts = List.copyOf(Objects.requireNonNull(liveOuts, "liveOuts"));
        successorBlocks = List.copyOf(Objects.requireNonNull(successorBlocks, "successorBlocks"));
        Objects.requireNonNull(helperName, "helperName");
        Objects.requireNonNull(helperDescriptor, "helperDescriptor");
        Objects.requireNonNull(nativeSymbol, "nativeSymbol");
        if (sourceMethodKey.isBlank()
                || sourceBlock.isBlank()
                || helperName.isBlank()
                || helperDescriptor.isBlank()
                || nativeSymbol.isBlank()) {
            throw new IllegalArgumentException("method split identities must not be blank");
        }
        if (liveOuts.size() != 1) {
            throw new IllegalArgumentException("v1 method split plan requires exactly one live-out");
        }
    }

    public IrValue liveOut() {
        return liveOuts.get(0);
    }
}
