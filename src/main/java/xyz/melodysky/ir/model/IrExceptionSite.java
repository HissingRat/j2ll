package xyz.melodysky.ir.model;

import java.util.List;
import java.util.Objects;

public record IrExceptionSite(IrExceptionSiteKind kind, List<IrExceptionEdge> handlers) {
    public IrExceptionSite {
        Objects.requireNonNull(kind, "kind");
        handlers = List.copyOf(Objects.requireNonNull(handlers, "handlers"));
    }
}
