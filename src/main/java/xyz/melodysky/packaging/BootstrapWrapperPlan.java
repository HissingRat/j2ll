package xyz.melodysky.packaging;

import java.util.Objects;

public record BootstrapWrapperPlan(
        String owner,
        String wrapperSymbol,
        String registerSymbol) implements Comparable<BootstrapWrapperPlan> {
    public BootstrapWrapperPlan {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(wrapperSymbol, "wrapperSymbol");
        Objects.requireNonNull(registerSymbol, "registerSymbol");
    }

    @Override
    public int compareTo(BootstrapWrapperPlan other) {
        return owner.compareTo(other.owner);
    }
}
