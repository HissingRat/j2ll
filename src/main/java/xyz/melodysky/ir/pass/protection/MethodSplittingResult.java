package xyz.melodysky.ir.pass.protection;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import xyz.melodysky.ir.model.IrMethod;

/**
 * Atomic method-splitting output. Consumers must take the caller and helpers together.
 */
public record MethodSplittingResult(
        IrMethod caller,
        List<OutlinedMethodHelper> helpers,
        MethodSplittingStatus status,
        String reasonCode,
        List<String> validationErrors) {
    public MethodSplittingResult {
        Objects.requireNonNull(caller, "caller");
        helpers = List.copyOf(Objects.requireNonNull(helpers, "helpers"));
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(reasonCode, "reasonCode");
        validationErrors = List.copyOf(Objects.requireNonNull(validationErrors, "validationErrors"));
        if (reasonCode.isBlank()) {
            throw new IllegalArgumentException("method splitting reason code must not be blank");
        }
        if (status == MethodSplittingStatus.RAN && (helpers.isEmpty() || !validationErrors.isEmpty())) {
            throw new IllegalArgumentException("successful method splitting requires validated helpers");
        }
        if (status != MethodSplittingStatus.RAN && !helpers.isEmpty()) {
            throw new IllegalArgumentException("non-successful method splitting must roll back helpers");
        }
    }

    public Optional<OutlinedMethodHelper> helper() {
        return helpers.size() == 1 ? Optional.of(helpers.get(0)) : Optional.empty();
    }
}
