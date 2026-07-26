package xyz.melodysky.ir.pass.protection;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Explicit proof boundary for methods whose final implementation path is
 * native LLVM.
 *
 * <p>The call-indirection pass deliberately does not infer this property from
 * membership in {@code IrProgram}. Callers build this set from the final (or a
 * subsequently re-verified preliminary) native implementation plan.</p>
 */
public final class IrNativeDirectTargets {
    private final List<String> methodKeys;
    private final Set<String> methodKeySet;

    public IrNativeDirectTargets(Collection<String> methodKeys) {
        Objects.requireNonNull(methodKeys, "methodKeys");
        this.methodKeys = methodKeys.stream()
                .map(methodKey -> Objects.requireNonNull(methodKey, "methodKey"))
                .peek(methodKey -> {
                    if (methodKey.isBlank()) {
                        throw new IllegalArgumentException("native method key must not be blank");
                    }
                })
                .distinct()
                .sorted(Comparator.naturalOrder())
                .toList();
        methodKeySet = Set.copyOf(this.methodKeys);
    }

    public static IrNativeDirectTargets empty() {
        return new IrNativeDirectTargets(List.of());
    }

    public List<String> methodKeys() {
        return methodKeys;
    }

    public boolean contains(String methodKey) {
        return methodKeySet.contains(methodKey);
    }
}
