package xyz.melodysky.runtime.jdk;

import java.util.Objects;
import java.util.Optional;
import java.util.List;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Type;

public record LambdaMetafactoryPlan(
        boolean lambdaMetafactory,
        boolean supported,
        Optional<Handle> implementationHandle,
        boolean serializable,
        List<String> markerInterfaces,
        List<String> bridgeMethodDescriptors,
        String reason) {
    public LambdaMetafactoryPlan {
        Objects.requireNonNull(implementationHandle, "implementationHandle");
        markerInterfaces = markerInterfaces.stream().filter(Objects::nonNull).sorted().distinct().toList();
        bridgeMethodDescriptors = bridgeMethodDescriptors.stream().filter(Objects::nonNull).sorted().distinct().toList();
        Objects.requireNonNull(reason, "reason");
    }

    public LambdaMetafactoryPlan(
            boolean lambdaMetafactory,
            boolean supported,
            Optional<Handle> implementationHandle,
            String reason) {
        this(lambdaMetafactory, supported, implementationHandle, false, List.of(), List.of(), reason);
    }
}
