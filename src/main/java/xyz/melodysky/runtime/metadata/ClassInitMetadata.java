package xyz.melodysky.runtime.metadata;

import java.util.Objects;

public record ClassInitMetadata(
        boolean hasClassInitializer,
        String classObjectHandle,
        String initStateHandle) {
    public ClassInitMetadata {
        Objects.requireNonNull(classObjectHandle, "classObjectHandle");
        Objects.requireNonNull(initStateHandle, "initStateHandle");
        if (classObjectHandle.isBlank()) {
            throw new IllegalArgumentException("class object handle must not be blank");
        }
        if (initStateHandle.isBlank()) {
            throw new IllegalArgumentException("class init state handle must not be blank");
        }
    }
}
