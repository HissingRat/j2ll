package xyz.melodysky.analysis.runtime;

import java.util.Objects;
import java.util.Optional;
import xyz.melodysky.jvm.MethodSignature;

public record AllocationSite(
        String id,
        String owner,
        MethodSignature method,
        int instructionIndex,
        Optional<String> allocatedType,
        boolean unknown) {
    public AllocationSite {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(allocatedType, "allocatedType");
    }

    public static AllocationSite known(
            String owner,
            MethodSignature method,
            int instructionIndex,
            String allocatedType) {
        return new AllocationSite(
                owner + "#" + method + "@alloc" + instructionIndex,
                owner,
                method,
                instructionIndex,
                Optional.of(allocatedType),
                false);
    }

    public static AllocationSite unknown(
            String owner,
            MethodSignature method,
            int instructionIndex) {
        return new AllocationSite(
                owner + "#" + method + "@alloc" + instructionIndex,
                owner,
                method,
                instructionIndex,
                Optional.empty(),
                true);
    }
}
