package xyz.melodysky.ir.model;

import java.util.Objects;

/**
 * Opaque reference from one SSA call instruction into an IR call-indirection
 * plan.
 *
 * <p>Raw Java owner/member/descriptor identity deliberately stays out of this
 * record. The plan owns that mapping once, while protected instructions carry
 * only stable opaque ids and typed validation/lowering facts.</p>
 */
public record IrCallIndirectionRef(
        String planId,
        String groupId,
        String entryId,
        IrCallIndirectionMode mode,
        IrCallSignature signature,
        IrCallInvokeKind originalInvokeKind) {
    public IrCallIndirectionRef {
        requireOpaqueId(planId, "planId");
        requireOpaqueId(groupId, "groupId");
        requireOpaqueId(entryId, "entryId");
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(signature, "signature");
        Objects.requireNonNull(originalInvokeKind, "originalInvokeKind");
    }

    private static void requireOpaqueId(String value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
