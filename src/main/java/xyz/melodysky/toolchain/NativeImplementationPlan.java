package xyz.melodysky.toolchain;

import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import xyz.melodysky.packaging.NativeRegistrationPlan;
import xyz.melodysky.packaging.MethodRewriteStrategy;
import xyz.melodysky.toolchain.localref.NativeLocalReferencePlan;

public record NativeImplementationPlan(
        List<NativeMethodImplementation> implementations,
        Map<String, String> unavailableReasonCodes,
        Map<String, NativeLocalReferencePlan> localReferencePlans) {
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
        LinkedHashMap<String, NativeLocalReferencePlan> stableLocalReferences =
                new LinkedHashMap<>();
        Objects.requireNonNull(
                        localReferencePlans,
                        "localReferencePlans")
                .entrySet()
                .stream()
                .filter(entry -> entry.getKey() != null
                        && entry.getValue() != null)
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    if (!entry.getKey().equals(entry.getValue().methodKey())) {
                        throw new IllegalArgumentException(
                                "local-reference plan key does not match method: "
                                        + entry.getKey());
                    }
                    stableLocalReferences.put(
                            entry.getKey(),
                            entry.getValue());
                });
        localReferencePlans =
                Collections.unmodifiableMap(stableLocalReferences);
        Map<String, NativeMethodImplementation> byMethod = implementations.stream()
                .collect(java.util.stream.Collectors.toUnmodifiableMap(
                        NativeMethodImplementation::methodKey,
                        implementation -> implementation));
        for (NativeMethodImplementation implementation : implementations) {
            implementation.coalescedIntoMethodKey().ifPresent(callerKey -> {
                NativeMethodImplementation caller = byMethod.get(callerKey);
                if (caller == null
                        || !caller.emitsStandaloneLlvmBody()
                        || caller.path() != NativeImplementationPath.LLVM_NATIVE_PATH) {
                    throw new IllegalArgumentException(
                            "coalesced method target must be an emitted LLVM implementation: "
                                    + implementation.methodKey()
                                    + " -> "
                                    + callerKey);
                }
            });
        }
    }

    public NativeImplementationPlan(
            List<NativeMethodImplementation> implementations,
            Map<String, String> unavailableReasonCodes) {
        this(implementations, unavailableReasonCodes, Map.of());
    }

    public NativeImplementationPlan(
            List<NativeMethodImplementation> implementations) {
        this(implementations, Map.of(), Map.of());
    }

    public NativeRegistrationPlan registrationPlan() {
        return new NativeRegistrationPlan(implementations.stream()
                .filter(implementation ->
                        implementation.decision().strategy()
                                != MethodRewriteStrategy
                                        .INTERNAL_NATIVE_ONLY)
                .map(NativeMethodImplementation::entry)
                .toList());
    }

    public List<NativeMethodImplementation> registeredImplementations() {
        return implementations.stream()
                .filter(implementation ->
                        implementation.decision().strategy()
                                != MethodRewriteStrategy
                                        .INTERNAL_NATIVE_ONLY)
                .toList();
    }

    public Optional<NativeMethodImplementation> implementationFor(String methodKey) {
        return implementations.stream()
                .filter(implementation -> implementation.methodKey().equals(methodKey))
                .findFirst();
    }

    public Optional<String> unavailableReasonCodeFor(String methodKey) {
        return Optional.ofNullable(unavailableReasonCodes.get(methodKey));
    }

    public Optional<NativeLocalReferencePlan> localReferencePlanFor(
            String methodKey) {
        return Optional.ofNullable(localReferencePlans.get(methodKey));
    }

    public List<NativeMethodImplementation> llvmImplementations() {
        return implementations.stream()
                .filter(implementation -> implementation.path() == NativeImplementationPath.LLVM_NATIVE_PATH)
                .toList();
    }

    /** LLVM methods which still own a physical standalone function body. */
    public List<NativeMethodImplementation> emittedLlvmImplementations() {
        return implementations.stream()
                .filter(NativeMethodImplementation::emitsStandaloneLlvmBody)
                .toList();
    }

}
