package xyz.melodysky.ir.pass.protection;

import java.util.Objects;
import xyz.melodysky.ir.model.IrCallInvokeKind;

/**
 * Java-visible semantics that the backend must retain while materializing one
 * IR indirection site.
 */
public record IrCallSemantics(
        IrCallInvokeKind originalInvokeKind,
        boolean receiverNullCheckRequired,
        boolean classInitializationGuardRequired,
        boolean exceptionPropagationPreserved,
        boolean fallbackRequired) {
    public IrCallSemantics {
        Objects.requireNonNull(originalInvokeKind, "originalInvokeKind");
        if (receiverNullCheckRequired != originalInvokeKind.hasReceiver()) {
            throw new IllegalArgumentException("receiver null-check policy must match invoke kind");
        }
        if (classInitializationGuardRequired && originalInvokeKind != IrCallInvokeKind.STATIC) {
            throw new IllegalArgumentException("only static calls require class initialization guards");
        }
    }
}
