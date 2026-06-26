package xyz.melodysky.runtime.jdk;

import java.util.List;
import java.util.Objects;

public record StringConcatBootstrapPlan(boolean stringConcatFactory, boolean supported, List<StringConcatToken> tokens, String reason) {
    public StringConcatBootstrapPlan {
        tokens = List.copyOf(Objects.requireNonNull(tokens, "tokens"));
        Objects.requireNonNull(reason, "reason");
    }
}
