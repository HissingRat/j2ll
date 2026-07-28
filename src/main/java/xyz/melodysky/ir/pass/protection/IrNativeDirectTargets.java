package xyz.melodysky.ir.pass.protection;

import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    private final Map<String, FunctionAbi> functionAbis;

    public IrNativeDirectTargets(Collection<String> methodKeys) {
        this(defaultFunctionAbis(methodKeys));
    }

    public IrNativeDirectTargets(Map<String, FunctionAbi> functionAbis) {
        Objects.requireNonNull(functionAbis, "functionAbis");
        LinkedHashMap<String, FunctionAbi> ordered = new LinkedHashMap<>();
        functionAbis.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    String methodKey =
                            Objects.requireNonNull(entry.getKey(), "methodKey");
                    if (methodKey.isBlank()) {
                        throw new IllegalArgumentException(
                                "native method key must not be blank");
                    }
                    ordered.put(
                            methodKey,
                            Objects.requireNonNull(entry.getValue(), "functionAbi"));
                });
        this.functionAbis = Map.copyOf(ordered);
        this.methodKeys = List.copyOf(ordered.keySet());
        methodKeySet = Set.copyOf(this.methodKeys);
    }

    private static Map<String, FunctionAbi> defaultFunctionAbis(
            Collection<String> methodKeys) {
        Objects.requireNonNull(methodKeys, "methodKeys");
        LinkedHashMap<String, FunctionAbi> result = new LinkedHashMap<>();
        methodKeys.stream()
                .map(methodKey -> Objects.requireNonNull(methodKey, "methodKey"))
                .peek(methodKey -> {
                    if (methodKey.isBlank()) {
                        throw new IllegalArgumentException("native method key must not be blank");
                    }
                })
                .distinct()
                .sorted(Comparator.naturalOrder())
                .forEach(methodKey ->
                        result.put(methodKey, FunctionAbi.noHiddenParameters()));
        return result;
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

    public FunctionAbi functionAbi(String methodKey) {
        FunctionAbi abi = functionAbis.get(methodKey);
        if (abi == null) {
            throw new IllegalArgumentException(
                    "method has no native direct-target ABI proof: " + methodKey);
        }
        return abi;
    }

    /**
     * Hidden LLVM implementation parameters that participate in the actual
     * function-pointer type even though they are not Java/SSA parameters.
     */
    public record FunctionAbi(
            boolean passesJniEnv,
            boolean passesOwnerClass) {
        public static FunctionAbi noHiddenParameters() {
            return new FunctionAbi(false, false);
        }

        String stableKey() {
            return (passesJniEnv ? "env" : "no-env")
                    + ":"
                    + (passesOwnerClass ? "owner" : "no-owner");
        }
    }
}
