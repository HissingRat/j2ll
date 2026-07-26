package xyz.melodysky.ir.pass.protection;

import java.util.Objects;
import xyz.melodysky.ir.model.IrCallIndirectionRef;

public record IrCallIndirectionSite(
        IrCallSiteId siteId,
        IrCallIndirectionRef reference,
        IrCallSemantics semantics) {
    public IrCallIndirectionSite {
        Objects.requireNonNull(siteId, "siteId");
        Objects.requireNonNull(reference, "reference");
        Objects.requireNonNull(semantics, "semantics");
        if (reference.originalInvokeKind() != semantics.originalInvokeKind()) {
            throw new IllegalArgumentException("call-indirection reference/semantics invoke kind mismatch");
        }
    }
}
