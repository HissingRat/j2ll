package xyz.melodysky.backend.llvm.model;

import java.util.Objects;

public record LlvmSwitchCase(int key, String target) {
    public LlvmSwitchCase {
        Objects.requireNonNull(target, "target");
    }
}
