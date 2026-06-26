package xyz.melodysky.pipeline;

import java.util.List;
import xyz.melodysky.diagnostic.Diagnostic;
import xyz.melodysky.diagnostic.DiagnosticStage;

public interface StageValidator<T> {
    DiagnosticStage stage();

    List<Diagnostic> validate(T artifact);
}
