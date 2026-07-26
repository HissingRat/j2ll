package xyz.melodysky.packaging;

import java.util.List;
import xyz.melodysky.diagnostic.Diagnostic;

public record InternalizedFieldTransformResult(
        byte[] classBytes,
        List<String> removedFieldKeys,
        List<Diagnostic> diagnostics) {
    public InternalizedFieldTransformResult {
        classBytes = classBytes.clone();
        removedFieldKeys = removedFieldKeys.stream().sorted().distinct().toList();
        diagnostics = List.copyOf(diagnostics);
    }

    @Override
    public byte[] classBytes() {
        return classBytes.clone();
    }
}
