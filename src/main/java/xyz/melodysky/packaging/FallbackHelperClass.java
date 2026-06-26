package xyz.melodysky.packaging;

import java.util.Objects;

public record FallbackHelperClass(String internalName, byte[] bytes) {
    public FallbackHelperClass {
        Objects.requireNonNull(internalName, "internalName");
        Objects.requireNonNull(bytes, "bytes");
        bytes = bytes.clone();
    }

    @Override
    public byte[] bytes() {
        return bytes.clone();
    }
}
