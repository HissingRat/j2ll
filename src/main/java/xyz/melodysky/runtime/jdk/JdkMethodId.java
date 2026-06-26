package xyz.melodysky.runtime.jdk;

import java.util.Objects;

public record JdkMethodId(String owner, String name, String descriptor) {
    public JdkMethodId {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(descriptor, "descriptor");
    }

    public String methodKey() {
        return owner + "#" + name + "!" + descriptor;
    }
}
