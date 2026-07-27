package xyz.melodysky.pipeline;

import java.util.Objects;
import xyz.melodysky.diagnostic.DiagnosticStage;

/** Stable user-facing description of a selected method left in Java. */
public record SkippedMethod(
        String owner,
        String name,
        String descriptor,
        DiagnosticStage stage,
        String reasonCode,
        String reason) implements Comparable<SkippedMethod> {
    public SkippedMethod {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(descriptor, "descriptor");
        Objects.requireNonNull(stage, "stage");
        Objects.requireNonNull(reasonCode, "reasonCode");
        Objects.requireNonNull(reason, "reason");
    }

    public String methodKey() {
        return owner + "#" + name + "!" + descriptor;
    }

    @Override
    public int compareTo(SkippedMethod other) {
        int ownerOrder = owner.compareTo(other.owner);
        if (ownerOrder != 0) {
            return ownerOrder;
        }
        int nameOrder = name.compareTo(other.name);
        if (nameOrder != 0) {
            return nameOrder;
        }
        return descriptor.compareTo(other.descriptor);
    }
}
