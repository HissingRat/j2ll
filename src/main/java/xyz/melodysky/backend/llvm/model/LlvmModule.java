package xyz.melodysky.backend.llvm.model;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

public record LlvmModule(String identifier, List<LlvmDeclaration> declarations, List<LlvmFunction> functions) {
    public LlvmModule {
        Objects.requireNonNull(identifier, "identifier");
        declarations = declarations.stream()
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(LlvmDeclaration::name))
                .toList();
        functions = functions.stream()
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(LlvmFunction::name))
                .toList();
    }

    public LlvmModule(String identifier, List<LlvmFunction> functions) {
        this(identifier, List.of(), functions);
    }
}
