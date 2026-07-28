package xyz.melodysky.ir.pass.protection;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import xyz.melodysky.ir.model.IrBlock;
import xyz.melodysky.ir.model.IrExceptionSite;
import xyz.melodysky.ir.model.IrExceptionSiteKind;
import xyz.melodysky.ir.model.IrInstruction;
import xyz.melodysky.ir.model.IrMethod;
import xyz.melodysky.ir.model.IrOpcode;
import xyz.melodysky.ir.model.IrTerminator;
import xyz.melodysky.ir.model.IrType;
import xyz.melodysky.ir.model.IrValue;
import xyz.melodysky.ir.validate.IrMethodValidator;

/**
 * Outlines a pure scalar SSA region into a compiler/native-internal helper.
 *
 * <p>V1 deliberately supports one live-out. The caller keeps its original terminator,
 * so a branch or switch can still fan out to multiple successors after the helper
 * computes the condition/selector.</p>
 */
public final class MethodSplittingPass {
    public static final String NAME = "METHOD_SPLITTING";
    public static final String DISABLED = "METHOD_SPLITTING_DISABLED";
    public static final String INPUT_INVALID = "METHOD_SPLITTING_INPUT_INVALID";
    public static final String VALIDATION_FAILED = "METHOD_SPLITTING_VALIDATION_FAILED";

    private final MethodSplitPlanner planner = new MethodSplitPlanner();
    private final MethodSplittingResultValidator resultValidator = new MethodSplittingResultValidator();

    public MethodSplittingResult run(IrMethod method, long seed) {
        return run(method, seed, true);
    }

    public MethodSplittingResult run(IrMethod method, long seed, boolean enabled) {
        if (!enabled) {
            return skipped(method, DISABLED);
        }
        List<String> inputErrors = new IrMethodValidator().validate(method).stream()
                .map(diagnostic -> diagnostic.code().value())
                .toList();
        if (!inputErrors.isEmpty()) {
            return new MethodSplittingResult(
                    method,
                    List.of(),
                    MethodSplittingStatus.FAILED,
                    INPUT_INVALID,
                    inputErrors);
        }

        MethodSplitPlanner.PlanSelection selection = planner.select(method, seed);
        if (!selection.selected()) {
            return skipped(method, selection.reasonCode());
        }
        MethodSplitPlan plan = selection.plan();
        OutlinedMethodHelper helper = buildHelper(method, plan);
        IrMethod caller = rewriteCaller(method, plan, helper);
        MethodSplittingResult candidate = new MethodSplittingResult(
                caller,
                List.of(helper),
                MethodSplittingStatus.RAN,
                NAME,
                List.of());
        List<String> validationErrors = resultValidator.validate(candidate);
        if (!validationErrors.isEmpty()) {
            return new MethodSplittingResult(
                    method,
                    List.of(),
                    MethodSplittingStatus.FAILED,
                    VALIDATION_FAILED,
                    validationErrors);
        }
        return candidate;
    }

    private MethodSplittingResult skipped(IrMethod method, String reasonCode) {
        return new MethodSplittingResult(
                method,
                List.of(),
                MethodSplittingStatus.SKIPPED,
                reasonCode,
                List.of());
    }

    private OutlinedMethodHelper buildHelper(IrMethod source, MethodSplitPlan plan) {
        IrBlock sourceBlock = source.blocks().stream()
                .filter(block -> block.name().equals(plan.sourceBlock()))
                .findFirst()
                .orElseThrow();
        List<IrInstruction> outlinedInstructions = sourceBlock.instructions()
                .subList(plan.startInclusive(), plan.endExclusive());
        String helperEntry = "outline_entry_" + plan.nativeSymbol().substring("j2ll_oh_".length());
        IrMethod helperBody = new IrMethod(
                source.owner(),
                plan.helperName(),
                plan.helperDescriptor(),
                plan.liveOut().type(),
                plan.liveIns(),
                List.of(new IrBlock(
                        helperEntry,
                        outlinedInstructions,
                        IrTerminator.returnValue(plan.liveOut()))));
        return new OutlinedMethodHelper(plan, helperBody);
    }

    private IrMethod rewriteCaller(
            IrMethod source,
            MethodSplitPlan plan,
            OutlinedMethodHelper helper) {
        ArrayList<IrBlock> blocks = new ArrayList<>(source.blocks().size());
        for (IrBlock block : source.blocks()) {
            if (!block.name().equals(plan.sourceBlock())) {
                blocks.add(block);
                continue;
            }
            ArrayList<IrInstruction> instructions = new ArrayList<>(
                    block.instructions().subList(0, plan.startInclusive()));
            IrValue pendingException = new IrValue(
                    "%j2ll_outline_pending_"
                            + plan.nativeSymbol().substring("j2ll_oh_".length()),
                    IrType.REFERENCE);
            instructions.add(IrInstruction.call(
                            Optional.of(plan.liveOut()),
                            IrOpcode.CALL_STATIC,
                            plan.liveIns(),
                            helper.methodKey())
                    .withExceptionSite(new IrExceptionSite(
                            IrExceptionSiteKind.JVM_PENDING_EXCEPTION,
                            List.of(),
                            Optional.of(pendingException))));
            blocks.add(new IrBlock(
                    block.name(),
                    block.parameters(),
                    block.exceptionCatchTypes(),
                    block.exceptionEdges(),
                    instructions,
                    block.terminator()));
        }
        return new IrMethod(
                source.owner(),
                source.name(),
                source.descriptor(),
                source.returnType(),
                source.parameters(),
                blocks);
    }
}
