package xyz.melodysky.ir.ssa;

import java.util.Objects;
import java.util.Optional;
import xyz.melodysky.frontend.classfile.ParsedMethod;
import xyz.melodysky.ir.model.IrMethod;
import xyz.melodysky.pipeline.LoweringStatus;

public record SsaMethodResult(
        ParsedMethod sourceMethod,
        Optional<IrMethod> irMethod,
        LoweringStatus status,
        String reasonCode,
        String reason) {
    public SsaMethodResult {
        Objects.requireNonNull(sourceMethod, "sourceMethod");
        Objects.requireNonNull(irMethod, "irMethod");
        Objects.requireNonNull(status, "status");
    }

    public static SsaMethodResult lowered(ParsedMethod sourceMethod, IrMethod irMethod) {
        return new SsaMethodResult(sourceMethod, Optional.of(irMethod), LoweringStatus.LOWERED, null, null);
    }

    public static SsaMethodResult halfLowered(
            ParsedMethod sourceMethod,
            IrMethod irMethod,
            String reasonCode,
            String reason) {
        return new SsaMethodResult(sourceMethod, Optional.of(irMethod), LoweringStatus.HALF_LOWERED, reasonCode, reason);
    }

    public static SsaMethodResult fallbackOnly(
            ParsedMethod sourceMethod,
            String reasonCode,
            String reason) {
        return new SsaMethodResult(sourceMethod, Optional.empty(), LoweringStatus.HALF_LOWERED, reasonCode, reason);
    }

    public static SsaMethodResult frontendSkipped(ParsedMethod sourceMethod, String reasonCode, String reason) {
        return new SsaMethodResult(sourceMethod, Optional.empty(), LoweringStatus.FRONTEND_SKIPPED, reasonCode, reason);
    }
}
