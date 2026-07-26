package xyz.melodysky.ir.pass.protection;

import java.util.Objects;
import java.util.Optional;
import xyz.melodysky.ir.model.IrCallInvokeKind;

/**
 * Analysis-owned resolution fact consumed by IR call-indirection planning.
 *
 * <p>Fallback and helper sensitivity are explicit so a protection pass cannot
 * accidentally turn a conservative JVM call decision into a native direct
 * target.</p>
 */
public record IrDirectCallFact(
        IrCallSiteId siteId,
        IrCallInvokeKind originalInvokeKind,
        IrDirectCallResolutionKind resolutionKind,
        Optional<String> directTargetMethodKey,
        boolean fallbackRequired,
        boolean helperSensitive) {
    public IrDirectCallFact {
        Objects.requireNonNull(siteId, "siteId");
        Objects.requireNonNull(originalInvokeKind, "originalInvokeKind");
        Objects.requireNonNull(resolutionKind, "resolutionKind");
        Objects.requireNonNull(directTargetMethodKey, "directTargetMethodKey");
        directTargetMethodKey.ifPresent(target -> {
            if (target.isBlank()) {
                throw new IllegalArgumentException("directTargetMethodKey must not be blank");
            }
        });
        boolean singleTarget = resolutionKind == IrDirectCallResolutionKind.BYTECODE_DIRECT
                || resolutionKind == IrDirectCallResolutionKind.DEVIRTUALIZED_SINGLE_TARGET;
        if (singleTarget != directTargetMethodKey.isPresent()) {
            throw new IllegalArgumentException(
                    "single-target call facts require exactly one direct target; unresolved/multi-target facts require none");
        }
    }

    public static IrDirectCallFact bytecodeDirect(
            IrCallSiteId siteId,
            IrCallInvokeKind invokeKind,
            String targetMethodKey) {
        return new IrDirectCallFact(
                siteId,
                invokeKind,
                IrDirectCallResolutionKind.BYTECODE_DIRECT,
                Optional.of(targetMethodKey),
                false,
                false);
    }

    public static IrDirectCallFact devirtualized(
            IrCallSiteId siteId,
            IrCallInvokeKind invokeKind,
            String targetMethodKey) {
        return new IrDirectCallFact(
                siteId,
                invokeKind,
                IrDirectCallResolutionKind.DEVIRTUALIZED_SINGLE_TARGET,
                Optional.of(targetMethodKey),
                false,
                false);
    }

    public static IrDirectCallFact unresolved(
            IrCallSiteId siteId,
            IrCallInvokeKind invokeKind,
            IrDirectCallResolutionKind resolutionKind) {
        if (resolutionKind != IrDirectCallResolutionKind.UNRESOLVED
                && resolutionKind != IrDirectCallResolutionKind.MULTIPLE_TARGETS) {
            throw new IllegalArgumentException("unresolved fact requires unresolved or multiple-target resolution");
        }
        return new IrDirectCallFact(
                siteId,
                invokeKind,
                resolutionKind,
                Optional.empty(),
                true,
                false);
    }

    public IrDirectCallFact requiringFallback() {
        return new IrDirectCallFact(
                siteId,
                originalInvokeKind,
                resolutionKind,
                directTargetMethodKey,
                true,
                helperSensitive);
    }

    public IrDirectCallFact helperSensitiveCall() {
        return new IrDirectCallFact(
                siteId,
                originalInvokeKind,
                resolutionKind,
                directTargetMethodKey,
                fallbackRequired,
                true);
    }

    public boolean resolvedSingleTarget() {
        return resolutionKind == IrDirectCallResolutionKind.BYTECODE_DIRECT
                || resolutionKind == IrDirectCallResolutionKind.DEVIRTUALIZED_SINGLE_TARGET;
    }
}
