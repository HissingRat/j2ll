package xyz.melodysky.cli;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record CliParseResult(Optional<CliOptions> options, List<String> errors) {
    public CliParseResult {
        Objects.requireNonNull(options, "options");
        errors = List.copyOf(Objects.requireNonNull(errors, "errors"));
    }

    public static CliParseResult success(CliOptions options) {
        return new CliParseResult(Optional.of(Objects.requireNonNull(options, "options")), List.of());
    }

    public static CliParseResult failure(List<String> errors) {
        return new CliParseResult(Optional.empty(), errors);
    }

    public boolean hasErrors() {
        return !errors.isEmpty();
    }
}
