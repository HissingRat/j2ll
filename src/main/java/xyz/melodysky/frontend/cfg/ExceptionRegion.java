package xyz.melodysky.frontend.cfg;

import java.util.Objects;

public record ExceptionRegion(
        int startInstructionIndex,
        int endInstructionIndexExclusive,
        int handlerInstructionIndex,
        String catchType) {
    public static final String CATCH_ALL = "<any>";

    public ExceptionRegion {
        if (startInstructionIndex < 0
                || endInstructionIndexExclusive < startInstructionIndex
                || handlerInstructionIndex < 0) {
            throw new IllegalArgumentException("invalid exception region instruction range");
        }
        catchType = catchType == null ? CATCH_ALL : catchType;
        Objects.requireNonNull(catchType, "catchType");
    }
}
