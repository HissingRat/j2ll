package xyz.melodysky.toolchain;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Program-level JNI local-reference lifetime facts for a frozen direct-call
 * closure.
 */
public record NativeLocalReferenceCallGraphAnalysis(
        Set<String> referenceProducingMethodKeys,
        Set<String> unboundedMethodKeys) {
    public NativeLocalReferenceCallGraphAnalysis {
        referenceProducingMethodKeys = stableCopy(
                referenceProducingMethodKeys,
                "referenceProducingMethodKeys");
        unboundedMethodKeys = stableCopy(
                unboundedMethodKeys,
                "unboundedMethodKeys");
    }

    private static Set<String> stableCopy(
            Set<String> values,
            String name) {
        LinkedHashSet<String> sorted = new LinkedHashSet<>();
        Objects.requireNonNull(values, name).stream()
                .filter(Objects::nonNull)
                .sorted()
                .forEach(sorted::add);
        return Collections.unmodifiableSet(sorted);
    }
}
