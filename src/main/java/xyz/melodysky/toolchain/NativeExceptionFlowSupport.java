package xyz.melodysky.toolchain;

import java.util.List;
import xyz.melodysky.ir.model.IrExceptionSite;
import xyz.melodysky.ir.model.IrInstruction;
import xyz.melodysky.ir.model.IrMethod;
import xyz.melodysky.ir.ssa.JvmExceptionInstructionSemantics;

/**
 * Validates the exception-flow evidence required by the LLVM/JNI lowering path.
 *
 * <p>Every JVM-throwable instruction must carry a pending-exception value.
 * Protected sites additionally carry a complete handler transfer. This keeps
 * hand-written or protection-rewritten IR from silently executing past a
 * pending JNI exception.
 */
public final class NativeExceptionFlowSupport {
    private final JvmExceptionInstructionSemantics exceptionSemantics =
            new JvmExceptionInstructionSemantics();

    public boolean hasUnsupportedJvmFlow(IrMethod method) {
        return method.blocks().stream()
                .flatMap(block -> block.instructions().stream())
                .anyMatch(instruction -> !hasCompletePendingExceptionEvidence(instruction));
    }

    private boolean hasCompletePendingExceptionEvidence(IrInstruction instruction) {
        if (!exceptionSemantics.canRaiseJvmException(instruction)) {
            return instruction.exceptionSites().isEmpty();
        }
        List<IrExceptionSite> sites = instruction.exceptionSites();
        if (sites.isEmpty()) {
            return false;
        }
        IrExceptionSite first = sites.get(0);
        if (first.exceptionValue().isEmpty()) {
            return false;
        }
        var exception = first.exceptionValue().orElseThrow();
        return sites.stream().allMatch(site ->
                site.exceptionValue().equals(first.exceptionValue())
                        && site.handlers().equals(first.handlers())
                        && site.handlers().stream().allMatch(edge ->
                                !edge.arguments().isEmpty()
                                        && edge.arguments().get(0).equals(exception)));
    }
}
