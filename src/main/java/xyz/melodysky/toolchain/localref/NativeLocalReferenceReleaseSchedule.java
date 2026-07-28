package xyz.melodysky.toolchain.localref;

import java.util.List;
import java.util.Objects;
import xyz.melodysky.ir.model.IrValue;

/**
 * Local-reference releases after one instruction.
 *
 * <p>{@code normalPath} runs only after the instruction completed without a
 * pending JVM exception. {@code exceptionalPath} runs only before transfer to
 * a protected handler. Keeping the two lists distinct prevents a normal-path
 * ownership transfer (for example CHECKCAST) from invalidating the handle
 * delivered to the successor while still bounding a catch loop.</p>
 */
public record NativeLocalReferenceReleaseSchedule(
        List<IrValue> normalPath,
        List<IrValue> exceptionalPath) {
    public NativeLocalReferenceReleaseSchedule {
        normalPath = stable(normalPath, "normalPath");
        exceptionalPath = stable(exceptionalPath, "exceptionalPath");
    }

    public boolean isEmpty() {
        return normalPath.isEmpty() && exceptionalPath.isEmpty();
    }

    private static List<IrValue> stable(
            List<IrValue> values,
            String label) {
        return Objects.requireNonNull(values, label).stream()
                .filter(Objects::nonNull)
                .distinct()
                .sorted()
                .toList();
    }
}
