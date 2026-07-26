package xyz.melodysky.backend.llvm.model;

import java.util.Objects;
import xyz.melodysky.ir.model.IrCallIndirectionMode;

/**
 * Backend copy of the opaque IR call-plan identity. Java owner/member identity
 * is intentionally absent.
 */
public record LlvmIrCallIndirectionRef(
        String groupId,
        String entryId,
        IrCallIndirectionMode mode) {
    public LlvmIrCallIndirectionRef {
        Objects.requireNonNull(groupId, "groupId");
        Objects.requireNonNull(entryId, "entryId");
        Objects.requireNonNull(mode, "mode");
        if (groupId.isBlank() || entryId.isBlank()) {
            throw new IllegalArgumentException("LLVM IR call-indirection ids must not be blank");
        }
    }
}
