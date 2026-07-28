package xyz.melodysky.ir.model;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record IrExceptionSite(
        IrExceptionSiteKind kind,
        List<IrExceptionEdge> handlers,
        Optional<IrValue> exceptionValue) {
    public IrExceptionSite {
        Objects.requireNonNull(kind, "kind");
        handlers = List.copyOf(Objects.requireNonNull(handlers, "handlers"));
        Objects.requireNonNull(exceptionValue, "exceptionValue");
    }

    public IrExceptionSite(IrExceptionSiteKind kind, List<IrExceptionEdge> handlers) {
        this(kind, handlers, Optional.empty());
    }
}
