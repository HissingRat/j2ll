package xyz.melodysky.ir.pass.protection;

import java.util.Objects;
import java.util.Optional;
import xyz.melodysky.ir.model.IrMethod;

record MethodInliningRewriteResult(Optional<IrMethod> method, String reasonCode) {
    MethodInliningRewriteResult {
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(reasonCode, "reasonCode");
    }

    static MethodInliningRewriteResult success(IrMethod method) {
        return new MethodInliningRewriteResult(Optional.of(method), MethodInliningReason.INLINED);
    }

    static MethodInliningRewriteResult rejected(String reasonCode) {
        return new MethodInliningRewriteResult(Optional.empty(), reasonCode);
    }
}
