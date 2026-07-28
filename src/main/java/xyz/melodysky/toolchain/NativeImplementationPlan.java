package xyz.melodysky.toolchain;

import java.util.Comparator;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import xyz.melodysky.packaging.NativeRegistrationPlan;

public record NativeImplementationPlan(
        List<NativeMethodImplementation> implementations,
        Map<String, String> unavailableReasonCodes) {
    public NativeImplementationPlan {
        implementations = implementations.stream()
                .filter(Objects::nonNull)
                .sorted(Comparator.naturalOrder())
                .toList();
        LinkedHashMap<String, String> stableReasons = new LinkedHashMap<>();
        Objects.requireNonNull(
                        unavailableReasonCodes,
                        "unavailableReasonCodes")
                .entrySet()
                .stream()
                .filter(entry -> entry.getKey() != null
                        && entry.getValue() != null)
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry ->
                        stableReasons.put(entry.getKey(), entry.getValue()));
        unavailableReasonCodes = Collections.unmodifiableMap(stableReasons);
    }

    public NativeImplementationPlan(
            List<NativeMethodImplementation> implementations) {
        this(implementations, Map.of());
    }

    public NativeRegistrationPlan registrationPlan() {
        return new NativeRegistrationPlan(implementations.stream()
                .map(NativeMethodImplementation::entry)
                .toList());
    }

    public Optional<NativeMethodImplementation> implementationFor(String methodKey) {
        return implementations.stream()
                .filter(implementation -> implementation.methodKey().equals(methodKey))
                .findFirst();
    }

    public Optional<String> unavailableReasonCodeFor(String methodKey) {
        return Optional.ofNullable(unavailableReasonCodes.get(methodKey));
    }

    public List<NativeMethodImplementation> llvmImplementations() {
        return implementations.stream()
                .filter(implementation -> implementation.path() == NativeImplementationPath.LLVM_NATIVE_PATH)
                .toList();
    }

}
