package xyz.melodysky.ir.pass.protection;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import xyz.melodysky.ir.model.IrBlock;
import xyz.melodysky.ir.model.IrExceptionSiteKind;
import xyz.melodysky.ir.model.IrInstruction;
import xyz.melodysky.ir.model.IrMethod;
import xyz.melodysky.ir.model.IrSwitchCase;
import xyz.melodysky.ir.model.IrTerminator;
import xyz.melodysky.ir.model.IrType;
import xyz.melodysky.ir.model.IrValue;

final class MethodInliningRewriter {
    MethodInliningRewriteResult inline(
            IrMethod caller,
            IrMethod callee,
            MethodInliningSite site,
            long seed) {
        IrInstruction call = callInstruction(caller, site);
        if (call == null
                || !matchesCandidate(call, site.candidate())
                || !hasUniqueDefinitions(caller)) {
            return MethodInliningRewriteResult.rejected(MethodInliningReason.UNSAFE_CALL_SITE);
        }
        IrBlock callBlock = caller.blocks().get(site.blockIndex());
        if (callBlock.isExceptionHandler()
                || !callBlock.exceptionCatchTypes().isEmpty()
                || !callBlock.exceptionEdges().isEmpty()
                || !canDiscardUnprotectedCallEvidence(caller, call)
                || !accessIsSafe(caller, callee, call, site.candidate())
                || !returnShapeMatches(callee, call)) {
            return MethodInliningRewriteResult.rejected(MethodInliningReason.UNSAFE_CALL_SITE);
        }

        try {
            String stableSite = caller.methodKey() + "->" + callee.methodKey() + "@"
                    + callBlock.name() + ":" + site.instructionIndex();
            String token = new ProtectionRandom(seed).token("METHOD_INLINING", stableSite, 12);
            MethodInliningNames names = new MethodInliningNames(caller, token);
            String continuationBlock = names.continuationBlock();
            IrValue continuationParameter = callee.returnType() == IrType.VOID
                    ? null
                    : names.nextValue(callee.returnType());
            MethodInliningIrRemapper calleeRemapper =
                    new MethodInliningIrRemapper(callee, call.operands(), names);
            Map<IrValue, IrValue> callerReplacement = call.result()
                    .map(result -> Map.of(result, continuationParameter))
                    .orElseGet(Map::of);

            ArrayList<IrBlock> rewrittenBlocks = new ArrayList<>(
                    caller.blocks().size() + callee.blocks().size());
            for (int blockIndex = 0; blockIndex < caller.blocks().size(); blockIndex++) {
                IrBlock block = caller.blocks().get(blockIndex);
                if (blockIndex != site.blockIndex()) {
                    rewrittenBlocks.add(replaceCallerUses(block, callerReplacement));
                    continue;
                }

                rewrittenBlocks.add(new IrBlock(
                        block.name(),
                        block.parameters(),
                        block.exceptionCatchTypes(),
                        block.exceptionEdges(),
                        block.instructions().subList(0, site.instructionIndex()),
                        IrTerminator.gotoBlock(calleeRemapper.blockName(callee.blocks().get(0).name()))));
                for (IrBlock calleeBlock : callee.blocks()) {
                    rewrittenBlocks.add(calleeRemapper.cloneBlock(
                            calleeBlock,
                            continuationBlock,
                            continuationParameter));
                }
                rewrittenBlocks.add(new IrBlock(
                        continuationBlock,
                        continuationParameter == null ? List.of() : List.of(continuationParameter),
                        List.of(),
                        List.of(),
                        replaceCallerUses(
                                block.instructions().subList(
                                        site.instructionIndex() + 1,
                                        block.instructions().size()),
                                callerReplacement),
                        replaceCallerUses(block.terminator(), callerReplacement)));
            }
            return MethodInliningRewriteResult.success(new IrMethod(
                    caller.owner(),
                    caller.name(),
                    caller.descriptor(),
                    caller.returnType(),
                    caller.parameters(),
                    rewrittenBlocks));
        } catch (IllegalArgumentException exception) {
            return MethodInliningRewriteResult.rejected(MethodInliningReason.UNSAFE_CALL_SITE);
        }
    }

    private IrInstruction callInstruction(IrMethod caller, MethodInliningSite site) {
        if (site.blockIndex() < 0 || site.blockIndex() >= caller.blocks().size()) {
            return null;
        }
        IrBlock block = caller.blocks().get(site.blockIndex());
        if (!block.name().equals(site.blockName())
                || site.instructionIndex() < 0
                || site.instructionIndex() >= block.instructions().size()) {
            return null;
        }
        return block.instructions().get(site.instructionIndex());
    }

    private boolean matchesCandidate(IrInstruction call, MethodInliningCandidate candidate) {
        return call.opcode() == candidate.invokeOpcode()
                && call.symbol().filter(candidate.calleeMethodKey()::equals).isPresent();
    }

    private boolean accessIsSafe(
            IrMethod caller,
            IrMethod callee,
            IrInstruction call,
            MethodInliningCandidate candidate) {
        if (candidate.access() == MethodInliningAccess.STATIC) {
            return call.opcode() == xyz.melodysky.ir.model.IrOpcode.CALL_STATIC;
        }
        return call.opcode() == xyz.melodysky.ir.model.IrOpcode.CALL_SPECIAL
                && caller.owner().equals(callee.owner())
                && !caller.parameters().isEmpty()
                && !call.operands().isEmpty()
                && call.operands().get(0).equals(caller.parameters().get(0));
    }

    private boolean returnShapeMatches(IrMethod callee, IrInstruction call) {
        if (callee.returnType() == IrType.VOID) {
            return call.result().isEmpty();
        }
        return call.result().filter(result -> result.type() == callee.returnType()).isPresent();
    }

    /**
     * A frontend direct call carries pending-exception evidence even when the
     * analysis-approved callee is pure. Inlining removes that call, so its
     * unprotected synthetic exception definition may be removed as well. A
     * protected edge, a more specific exception check, or any observable use
     * of the exception value remains a fail-closed boundary.
     */
    private boolean canDiscardUnprotectedCallEvidence(
            IrMethod caller,
            IrInstruction call) {
        for (var site : call.exceptionSites()) {
            if (site.kind() != IrExceptionSiteKind.JVM_PENDING_EXCEPTION
                    || !site.handlers().isEmpty()
                    || site.exceptionValue()
                            .filter(value -> isUsed(caller, value))
                            .isPresent()) {
                return false;
            }
        }
        return true;
    }

    private boolean isUsed(IrMethod method, IrValue value) {
        for (IrBlock block : method.blocks()) {
            if (block.instructions().stream()
                    .anyMatch(instruction -> instruction.operands().contains(value))) {
                return true;
            }
            if (block.exceptionEdges().stream()
                    .flatMap(edge -> edge.arguments().stream())
                    .anyMatch(value::equals)) {
                return true;
            }
            if (block.instructions().stream()
                    .flatMap(instruction -> instruction.exceptionSites().stream())
                    .flatMap(site -> site.handlers().stream())
                    .flatMap(edge -> edge.arguments().stream())
                    .anyMatch(value::equals)) {
                return true;
            }
            IrTerminator terminator = block.terminator();
            if (terminator.value().filter(value::equals).isPresent()
                    || terminator.condition().filter(value::equals).isPresent()
                    || terminator.switchValue().filter(value::equals).isPresent()
                    || terminator.targetArguments().contains(value)
                    || terminator.trueTargetArguments().contains(value)
                    || terminator.falseTargetArguments().contains(value)
                    || terminator.defaultTargetArguments().contains(value)
                    || terminator.switchCases().stream()
                            .flatMap(switchCase -> switchCase.arguments().stream())
                            .anyMatch(value::equals)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasUniqueDefinitions(IrMethod method) {
        HashSet<IrValue> definitions = new HashSet<>();
        if (!method.parameters().stream().allMatch(definitions::add)) {
            return false;
        }
        for (IrBlock block : method.blocks()) {
            if (!block.parameters().stream().allMatch(definitions::add)) {
                return false;
            }
            for (IrInstruction instruction : block.instructions()) {
                if (instruction.result().isPresent()
                        && !definitions.add(instruction.result().orElseThrow())) {
                    return false;
                }
            }
        }
        return true;
    }

    private IrBlock replaceCallerUses(IrBlock block, Map<IrValue, IrValue> replacement) {
        if (replacement.isEmpty()) {
            return block;
        }
        return new IrBlock(
                block.name(),
                block.parameters(),
                block.exceptionCatchTypes(),
                block.exceptionEdges(),
                replaceCallerUses(block.instructions(), replacement),
                replaceCallerUses(block.terminator(), replacement));
    }

    private List<IrInstruction> replaceCallerUses(
            List<IrInstruction> instructions,
            Map<IrValue, IrValue> replacement) {
        if (replacement.isEmpty()) {
            return List.copyOf(instructions);
        }
        return instructions.stream()
                .map(instruction -> new IrInstruction(
                        instruction.result(),
                        instruction.opcode(),
                        replaceValues(instruction.operands(), replacement),
                        instruction.intLiteral(),
                        instruction.longLiteral(),
                        instruction.floatLiteral(),
                        instruction.doubleLiteral(),
                        instruction.symbol(),
                        instruction.exceptionSites()))
                .toList();
    }

    private IrTerminator replaceCallerUses(
            IrTerminator terminator,
            Map<IrValue, IrValue> replacement) {
        if (replacement.isEmpty()) {
            return terminator;
        }
        return switch (terminator.kind()) {
            case RETURN -> new IrTerminator(
                    terminator.kind(),
                    terminator.value().map(value -> replaceValue(value, replacement)));
            case THROW -> IrTerminator.throwValue(
                    replaceValue(terminator.value().orElseThrow(), replacement));
            case GOTO -> IrTerminator.gotoBlock(
                    terminator.target().orElseThrow(),
                    replaceValues(terminator.targetArguments(), replacement));
            case BRANCH -> IrTerminator.branch(
                    replaceValue(terminator.condition().orElseThrow(), replacement),
                    terminator.trueTarget().orElseThrow(),
                    replaceValues(terminator.trueTargetArguments(), replacement),
                    terminator.falseTarget().orElseThrow(),
                    replaceValues(terminator.falseTargetArguments(), replacement));
            case SWITCH -> {
                ArrayList<IrSwitchCase> cases = new ArrayList<>();
                for (IrSwitchCase switchCase : terminator.switchCases()) {
                    cases.add(new IrSwitchCase(
                            switchCase.key(),
                            switchCase.target(),
                            replaceValues(switchCase.arguments(), replacement)));
                }
                yield IrTerminator.switchOn(
                        replaceValue(terminator.switchValue().orElseThrow(), replacement),
                        terminator.defaultTarget().orElseThrow(),
                        replaceValues(terminator.defaultTargetArguments(), replacement),
                        cases);
            }
        };
    }

    private List<IrValue> replaceValues(
            List<IrValue> values,
            Map<IrValue, IrValue> replacement) {
        return values.stream().map(value -> replaceValue(value, replacement)).toList();
    }

    private IrValue replaceValue(IrValue value, Map<IrValue, IrValue> replacement) {
        return Optional.ofNullable(replacement.get(value)).orElse(value);
    }
}
