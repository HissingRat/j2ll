package xyz.melodysky.packaging;

import java.util.Objects;
import java.util.Optional;
import xyz.melodysky.frontend.classfile.ParsedMethod;

public record MethodRewriteDecision(
        ParsedMethod method,
        MethodRewriteStrategy strategy,
        String registrationOwner,
        Optional<String> generatedHelperName,
        String reasonCode) {
    public MethodRewriteDecision {
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(strategy, "strategy");
        Objects.requireNonNull(registrationOwner, "registrationOwner");
        Objects.requireNonNull(generatedHelperName, "generatedHelperName");
    }
}
