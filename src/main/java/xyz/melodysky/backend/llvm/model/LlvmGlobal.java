package xyz.melodysky.backend.llvm.model;

import java.util.Objects;

public record LlvmGlobal(String name, String definition) {
    public LlvmGlobal {
        Objects.requireNonNull(name, "name");
        if (name.isBlank()) {
            throw new IllegalArgumentException("LLVM global name must not be blank");
        }
        Objects.requireNonNull(definition, "definition");
        if (definition.isBlank()) {
            throw new IllegalArgumentException("LLVM global definition must not be blank");
        }
    }

    /**
     * Returns whether this definition has LLVM private or internal linkage.
     *
     * <p>The linkage token is the first token in a global definition. Other linkage kinds,
     * including link-once and appending retention roots such as {@code llvm.used}, deliberately
     * remain outside global-layout perturbation.
     */
    public boolean hasModuleLocalLinkage() {
        String normalized = definition.stripLeading();
        return normalized.equals("private")
                || normalized.startsWith("private ")
                || normalized.equals("internal")
                || normalized.startsWith("internal ");
    }
}
