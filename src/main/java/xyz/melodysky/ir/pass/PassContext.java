package xyz.melodysky.ir.pass;

import xyz.melodysky.diagnostic.DiagnosticBag;

public record PassContext(DiagnosticBag diagnostics) {
    public static PassContext empty() {
        return new PassContext(new DiagnosticBag());
    }
}
