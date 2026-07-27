package xyz.melodysky.ir.ssa;

import java.util.Objects;
import java.util.Optional;
import xyz.melodysky.frontend.classfile.ParsedMethod;
import xyz.melodysky.ir.model.IrMethod;
import xyz.melodysky.diagnostic.DiagnosticStage;
import xyz.melodysky.pipeline.LoweringStatus;

public record SsaMethodResult(
        ParsedMethod sourceMethod,
        Optional<IrMethod> irMethod,
        LoweringStatus status,
        DiagnosticStage outcomeStage,
        String reasonCode,
        String reason) {
    public SsaMethodResult {
        Objects.requireNonNull(sourceMethod, "sourceMethod");
        Objects.requireNonNull(irMethod, "irMethod");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(outcomeStage, "outcomeStage");
        if (status == LoweringStatus.NATIVE_LOWERED && irMethod.isEmpty()) {
            throw new IllegalArgumentException("nativeLowered result requires IR");
        }
        if (status == LoweringStatus.SKIPPED && irMethod.isPresent()) {
            throw new IllegalArgumentException("skipped result must not retain partial IR");
        }
        if ((reasonCode == null) != (reason == null)) {
            throw new IllegalArgumentException("reasonCode and reason must be provided together");
        }
        if (status == LoweringStatus.SKIPPED && reasonCode == null) {
            throw new IllegalArgumentException("skipped result requires a reason");
        }
    }

    public static SsaMethodResult nativeLowered(ParsedMethod sourceMethod, IrMethod irMethod) {
        return new SsaMethodResult(
                sourceMethod,
                Optional.of(irMethod),
                LoweringStatus.NATIVE_LOWERED,
                DiagnosticStage.LOWERING,
                null,
                null);
    }

    public static SsaMethodResult skipped(
            ParsedMethod sourceMethod,
            String reasonCode,
            String reason) {
        return skipped(sourceMethod, DiagnosticStage.LOWERING, reasonCode, reason);
    }

    public static SsaMethodResult skipped(
            ParsedMethod sourceMethod,
            DiagnosticStage outcomeStage,
            String reasonCode,
            String reason) {
        return new SsaMethodResult(
                sourceMethod,
                Optional.empty(),
                LoweringStatus.SKIPPED,
                outcomeStage,
                reasonCode,
                reason);
    }
}
