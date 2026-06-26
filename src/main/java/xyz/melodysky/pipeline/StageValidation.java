package xyz.melodysky.pipeline;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import xyz.melodysky.diagnostic.Diagnostic;

public final class StageValidation {
    private StageValidation() {
    }

    public static <T> StageResult<T> validate(StageResult<T> result, StageValidator<T> validator) {
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(validator, "validator");
        if (result.artifact().isEmpty()) {
            return result;
        }

        List<Diagnostic> diagnostics = new ArrayList<>(result.diagnostics());
        diagnostics.addAll(validator.validate(result.artifact().get()));
        return new StageResult<>(result.stage(), result.artifact(), result.outcome(), diagnostics);
    }
}
