package xyz.melodysky.toolchain.localref;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import xyz.melodysky.ir.model.IrBlock;
import xyz.melodysky.ir.model.IrExceptionHandlers;
import xyz.melodysky.ir.model.IrInstruction;
import xyz.melodysky.ir.model.IrMethod;
import xyz.melodysky.ir.model.IrOpcode;
import xyz.melodysky.ir.model.IrType;
import xyz.melodysky.ir.model.IrValue;
import xyz.melodysky.runtime.PureNativeJdkRuntimeHelpers;

/**
 * Fail-closed validation for a planned JNI local-reference lifetime.
 */
public final class NativeLocalReferencePlanValidator {
    public Optional<String> validate(
            IrMethod method,
            NativeLocalReferencePlan plan) {
        if (!method.methodKey().equals(plan.methodKey())) {
            return Optional.of(
                    "local-reference plan belongs to a different method");
        }
        Optional<String> releaseFailure =
                validateReleaseOwnership(plan);
        if (releaseFailure.isPresent()) {
            return releaseFailure;
        }
        Optional<String> parallelTransferFailure =
                validateNoParallelOwnedTransfers(method, plan);
        if (parallelTransferFailure.isPresent()) {
            return parallelTransferFailure;
        }

        Set<String> cyclicBlocks = cyclicBlocks(method);
        if (cyclicBlocks.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Set<String>> transfers = transfers(method);
        LinkedHashSet<String> sinks =
                new LinkedHashSet<>(releasedValues(plan));
        sinks.addAll(activationExitValues(method, transfers));
        NativeLocalReferenceTransferSafety transferSafety =
                new NativeLocalReferenceTransferSafety();
        for (IrBlock block : method.blocks()) {
            if (!cyclicBlocks.contains(block.name())) {
                continue;
            }
            for (IrInstruction instruction : block.instructions()) {
                Optional<IrValue> result = instruction.result()
                        .filter(value ->
                                value.type() == IrType.REFERENCE)
                        .filter(value ->
                                instruction.opcode()
                                        != IrOpcode.CONST_NULL)
                        .filter(value ->
                                instruction.opcode()
                                        != IrOpcode.CHECKCAST);
                if (result.isPresent()
                        && !transferSafety.hasVerifiedSink(
                                result.orElseThrow().name(),
                                transfers,
                                sinks)) {
                    return Optional.of(
                            "owned local reference produced in a CFG cycle "
                                    + "has no verified release/transfer sink: "
                                    + result.orElseThrow().name());
                }
                for (var exceptionSite : instruction.exceptionSites()) {
                    if (exceptionSite.handlers().isEmpty()
                            || exceptionSite.exceptionValue().isEmpty()) {
                        continue;
                    }
                    String exceptionValue = exceptionSite
                            .exceptionValue()
                            .orElseThrow()
                            .name();
                    if (!transferSafety.hasVerifiedSink(
                            exceptionValue,
                            transfers,
                            sinks)) {
                        return Optional.of(
                                "caught pending-exception local in a CFG "
                                        + "cycle has no verified release/"
                                        + "handler-transfer sink: "
                                        + exceptionValue);
                    }
                }
            }
        }
        return Optional.empty();
    }

    private Optional<String> validateNoParallelOwnedTransfers(
            IrMethod method,
            NativeLocalReferencePlan plan) {
        Map<String, IrBlock> blocks = method.blocks().stream()
                .collect(java.util.stream.Collectors.toMap(
                        IrBlock::name,
                        block -> block));
        NativeLocalReferenceCfgFacts cfg =
                NativeLocalReferenceCfgFacts.analyze(method);
        for (IrBlock block : method.blocks()) {
            for (IrInstruction instruction : block.instructions()) {
                for (var site : instruction.exceptionSites()) {
                    for (var edge : IrExceptionHandlers.reachable(
                            site.handlers())) {
                        Optional<String> failure = parallelTransferFailure(
                                edge.arguments(),
                                blocks.get(edge.target()),
                                plan,
                                cfg);
                        if (failure.isPresent()) {
                            return failure;
                        }
                    }
                }
            }
            for (var edge : IrExceptionHandlers.reachable(
                    block.exceptionEdges())) {
                Optional<String> failure = parallelTransferFailure(
                        edge.arguments(),
                        blocks.get(edge.target()),
                        plan,
                        cfg);
                if (failure.isPresent()) {
                    return failure;
                }
            }
            var terminator = block.terminator();
            ArrayList<EdgeArguments> normalEdges = new ArrayList<>();
            terminator.target().ifPresent(target -> normalEdges.add(
                    new EdgeArguments(
                            target,
                            terminator.targetArguments())));
            terminator.trueTarget().ifPresent(target -> normalEdges.add(
                    new EdgeArguments(
                            target,
                            terminator.trueTargetArguments())));
            terminator.falseTarget().ifPresent(target -> normalEdges.add(
                    new EdgeArguments(
                            target,
                            terminator.falseTargetArguments())));
            terminator.defaultTarget().ifPresent(target -> normalEdges.add(
                    new EdgeArguments(
                            target,
                            terminator.defaultTargetArguments())));
            terminator.switchCases().forEach(switchCase -> normalEdges.add(
                    new EdgeArguments(
                            switchCase.target(),
                            switchCase.arguments())));
            for (EdgeArguments edge : normalEdges) {
                Optional<String> failure = parallelTransferFailure(
                        edge.arguments(),
                        blocks.get(edge.target()),
                        plan,
                        cfg);
                if (failure.isPresent()) {
                    return failure;
                }
            }
        }
        return Optional.empty();
    }

    private Optional<String> parallelTransferFailure(
            List<IrValue> arguments,
            IrBlock target,
            NativeLocalReferencePlan plan,
            NativeLocalReferenceCfgFacts cfg) {
        if (target == null || arguments.size() != target.parameters().size()) {
            return Optional.empty();
        }
        LinkedHashSet<String> transferredOrigins = new LinkedHashSet<>();
        for (int index = 0; index < arguments.size(); index++) {
            IrValue argument = arguments.get(index);
            if (argument.type() != IrType.REFERENCE
                    || target.parameters().get(index).type()
                            != IrType.REFERENCE
                    || !mayBeOwned(argument.name(), plan, new LinkedHashSet<>())) {
                continue;
            }
            String origin = ownershipOrigin(
                    argument.name(),
                    plan,
                    new LinkedHashSet<>());
            if (!transferredOrigins.add(origin)) {
                return Optional.of(
                        "owned local reference is transferred to multiple "
                                + "reference parameters on one edge: "
                                + origin);
            }
        }
        for (IrValue directLiveIn :
                cfg.liveIn().getOrDefault(target.name(), Set.of())) {
            if (directLiveIn.type() != IrType.REFERENCE
                    || target.parameters().contains(directLiveIn)
                    || !mayBeOwned(
                            directLiveIn.name(),
                            plan,
                            new LinkedHashSet<>())) {
                continue;
            }
            String origin = ownershipOrigin(
                    directLiveIn.name(),
                    plan,
                    new LinkedHashSet<>());
            if (!transferredOrigins.add(origin)) {
                return Optional.of(
                        "owned local reference is both an edge argument and "
                                + "a direct successor live-in: "
                                + origin);
            }
        }
        return Optional.empty();
    }

    private boolean mayBeOwned(
            String value,
            NativeLocalReferencePlan plan,
            Set<String> visiting) {
        NativeLocalReferenceOwnership ownership =
                plan.ownershipByValue().get(value);
        if (ownership == null) {
            return false;
        }
        return switch (ownership.kind()) {
            case OWNED, DYNAMIC -> true;
            case BORROWED -> false;
            case ALIAS -> {
                if (!visiting.add(value)) {
                    yield false;
                }
                boolean owned = mayBeOwned(
                        ownership.aliasSource().orElseThrow(),
                        plan,
                        visiting);
                visiting.remove(value);
                yield owned;
            }
        };
    }

    private String ownershipOrigin(
            String value,
            NativeLocalReferencePlan plan,
            Set<String> visiting) {
        NativeLocalReferenceOwnership ownership =
                plan.ownershipByValue().get(value);
        if (ownership == null
                || ownership.kind()
                        != NativeLocalReferenceOwnership.Kind.ALIAS) {
            return value;
        }
        if (!visiting.add(value)) {
            return value;
        }
        String origin = ownershipOrigin(
                ownership.aliasSource().orElseThrow(),
                plan,
                visiting);
        visiting.remove(value);
        return origin;
    }

    private Optional<String> validateReleaseOwnership(
            NativeLocalReferencePlan plan) {
        for (IrValue value : allReleases(plan)) {
            if (value.type() != IrType.REFERENCE) {
                return Optional.of(
                        "local-reference release targets a non-reference "
                                + "value: "
                                + value.name());
            }
            NativeLocalReferenceOwnership ownership =
                    plan.ownershipByValue().get(value.name());
            if (ownership == null) {
                return Optional.of(
                        "local-reference release has no ownership fact: "
                                + value.name());
            }
            if (ownership.kind()
                    == NativeLocalReferenceOwnership.Kind.BORROWED) {
                return Optional.of(
                        "planner attempted to release a statically borrowed "
                                + "reference: "
                                + value.name());
            }
        }
        return Optional.empty();
    }

    private List<IrValue> allReleases(NativeLocalReferencePlan plan) {
        ArrayList<IrValue> result = new ArrayList<>();
        plan.instructionReleases().values().forEach(schedule -> {
            result.addAll(schedule.normalPath());
            result.addAll(schedule.exceptionalPath());
        });
        plan.terminatorReleases().values().forEach(result::addAll);
        plan.normalEdgeReleases().values().forEach(result::addAll);
        return List.copyOf(result);
    }

    private Set<String> releasedValues(NativeLocalReferencePlan plan) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        plan.instructionReleases().values().forEach(schedule -> {
            schedule.normalPath().stream()
                    .map(IrValue::name)
                    .forEach(result::add);
            schedule.exceptionalPath().stream()
                    .map(IrValue::name)
                    .forEach(result::add);
        });
        plan.terminatorReleases().values().stream()
                .flatMap(List::stream)
                .map(IrValue::name)
                .forEach(result::add);
        return Set.copyOf(result);
    }

    private Set<String> activationExitValues(
            IrMethod method,
            Map<String, Set<String>> transfers) {
        Set<String> transferCycles = cyclicTransferValues(transfers);
        LinkedHashSet<String> result = new LinkedHashSet<>();
        method.blocks().stream()
                .filter(this::isActivationExit)
                .forEach(block -> {
                    /*
                     * JNI deletes every local reference still owned by the
                     * native activation when it returns.  An exception-edge
                     * argument may therefore terminate at an otherwise
                     * unused handler parameter in a return/unhandled-throw
                     * block; it need not be consumed by the terminator to be
                     * a real lifetime sink.
                     */
                    block.parameters().stream()
                            .filter(value ->
                                    value.type() == IrType.REFERENCE)
                            .map(IrValue::name)
                            .filter(value ->
                                    !transferCycles.contains(value))
                            .forEach(result::add);
                    block.terminator().value().stream()
                            .filter(value ->
                                    value.type() == IrType.REFERENCE)
                            .map(IrValue::name)
                            .filter(value ->
                                    !transferCycles.contains(value))
                            .forEach(result::add);
                });
        return Set.copyOf(result);
    }

    private boolean isActivationExit(IrBlock block) {
        return block.terminator().kind()
                        == xyz.melodysky.ir.model.IrTerminatorKind.RETURN
                || (block.terminator().kind()
                                == xyz.melodysky.ir.model
                                        .IrTerminatorKind.THROW
                        && IrExceptionHandlers.reachable(
                                        block.exceptionEdges())
                                .isEmpty());
    }

    private Set<String> cyclicTransferValues(
            Map<String, Set<String>> transfers) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String start : transfers.keySet()) {
            ArrayDeque<String> work = new ArrayDeque<>(
                    transfers.getOrDefault(start, Set.of()));
            LinkedHashSet<String> visited = new LinkedHashSet<>();
            while (!work.isEmpty()) {
                String current = work.removeFirst();
                if (current.equals(start)) {
                    result.add(start);
                    break;
                }
                if (visited.add(current)) {
                    transfers.getOrDefault(current, Set.of())
                            .forEach(work::addLast);
                }
            }
        }
        return Set.copyOf(result);
    }

    private Map<String, Set<String>> transfers(IrMethod method) {
        LinkedHashMap<String, LinkedHashSet<String>> mutable =
                new LinkedHashMap<>();
        Map<String, IrBlock> blocks = method.blocks().stream()
                .collect(java.util.stream.Collectors.toMap(
                        IrBlock::name,
                        block -> block));
        for (IrBlock block : method.blocks()) {
            for (IrInstruction instruction : block.instructions()) {
                if (instruction.opcode() == IrOpcode.CHECKCAST
                        && instruction.result().isPresent()
                        && !instruction.operands().isEmpty()) {
                    addTransfer(
                            mutable,
                            instruction.operands().get(0),
                            instruction.result().orElseThrow());
                } else if (instruction.opcode()
                                == IrOpcode.CALL_RUNTIME_HELPER
                        && instruction.result().isPresent()
                        && !instruction.operands().isEmpty()
                        && (baseSymbol(instruction.symbol().orElse(""))
                                        .equals(
                                                "j2ll_rt_objects_require_non_null")
                                || PureNativeJdkRuntimeHelpers
                                        .returnsOperandZeroAlias(
                                                instruction.symbol()
                                                        .orElse("")))) {
                    addTransfer(
                            mutable,
                            instruction.operands().get(0),
                            instruction.result().orElseThrow());
                }
                instruction.exceptionSites().stream()
                        .flatMap(site -> IrExceptionHandlers
                                .reachable(site.handlers()).stream())
                        .forEach(edge -> addArgumentTransfers(
                                mutable,
                                edge.arguments(),
                                blocks.get(edge.target())));
            }
            IrExceptionHandlers.reachable(block.exceptionEdges())
                    .forEach(edge -> addArgumentTransfers(
                            mutable,
                            edge.arguments(),
                            blocks.get(edge.target())));
            var terminator = block.terminator();
            terminator.target().ifPresent(target ->
                    addArgumentTransfers(
                            mutable,
                            terminator.targetArguments(),
                            blocks.get(target)));
            terminator.trueTarget().ifPresent(target ->
                    addArgumentTransfers(
                            mutable,
                            terminator.trueTargetArguments(),
                            blocks.get(target)));
            terminator.falseTarget().ifPresent(target ->
                    addArgumentTransfers(
                            mutable,
                            terminator.falseTargetArguments(),
                            blocks.get(target)));
            terminator.defaultTarget().ifPresent(target ->
                    addArgumentTransfers(
                            mutable,
                            terminator.defaultTargetArguments(),
                            blocks.get(target)));
            terminator.switchCases().forEach(switchCase ->
                    addArgumentTransfers(
                            mutable,
                            switchCase.arguments(),
                            blocks.get(switchCase.target())));
        }
        LinkedHashMap<String, Set<String>> result = new LinkedHashMap<>();
        mutable.forEach((source, targets) ->
                result.put(source, Set.copyOf(targets)));
        return result;
    }

    private void addArgumentTransfers(
            Map<String, LinkedHashSet<String>> transfers,
            List<IrValue> arguments,
            IrBlock target) {
        if (target == null || arguments.size() != target.parameters().size()) {
            return;
        }
        for (int index = 0; index < arguments.size(); index++) {
            if (arguments.get(index).type() == IrType.REFERENCE
                    && target.parameters().get(index).type()
                            == IrType.REFERENCE) {
                addTransfer(
                        transfers,
                        arguments.get(index),
                        target.parameters().get(index));
            }
        }
    }

    private void addTransfer(
            Map<String, LinkedHashSet<String>> transfers,
            IrValue source,
            IrValue target) {
        if (source.type() != IrType.REFERENCE
                || target.type() != IrType.REFERENCE) {
            return;
        }
        transfers.computeIfAbsent(
                        source.name(),
                        ignored -> new LinkedHashSet<>())
                .add(target.name());
    }

    private String baseSymbol(String symbol) {
        int separator = symbol.indexOf('|');
        return separator < 0 ? symbol : symbol.substring(0, separator);
    }

    private Set<String> cyclicBlocks(IrMethod method) {
        Map<String, Set<String>> successors = new HashMap<>();
        Set<String> names = method.blocks().stream()
                .map(IrBlock::name)
                .collect(java.util.stream.Collectors.toSet());
        for (IrBlock block : method.blocks()) {
            LinkedHashSet<String> targets = new LinkedHashSet<>();
            block.terminator().target()
                    .filter(names::contains)
                    .ifPresent(targets::add);
            block.terminator().trueTarget()
                    .filter(names::contains)
                    .ifPresent(targets::add);
            block.terminator().falseTarget()
                    .filter(names::contains)
                    .ifPresent(targets::add);
            block.terminator().defaultTarget()
                    .filter(names::contains)
                    .ifPresent(targets::add);
            block.terminator().switchCases().stream()
                    .map(switchCase -> switchCase.target())
                    .filter(names::contains)
                    .forEach(targets::add);
            block.instructions().stream()
                    .flatMap(instruction ->
                            instruction.exceptionSites().stream())
                    .flatMap(site -> IrExceptionHandlers
                            .reachable(site.handlers()).stream())
                    .map(edge -> edge.target())
                    .filter(names::contains)
                    .forEach(targets::add);
            IrExceptionHandlers.reachable(block.exceptionEdges()).stream()
                    .map(xyz.melodysky.ir.model.IrExceptionEdge::target)
                    .filter(names::contains)
                    .forEach(targets::add);
            successors.put(block.name(), Set.copyOf(targets));
        }
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String start : names) {
            ArrayDeque<String> work = new ArrayDeque<>(
                    successors.getOrDefault(start, Set.of()));
            LinkedHashSet<String> visited = new LinkedHashSet<>();
            while (!work.isEmpty()) {
                String current = work.removeFirst();
                if (current.equals(start)) {
                    result.add(start);
                    break;
                }
                if (visited.add(current)) {
                    successors.getOrDefault(current, Set.of())
                            .forEach(work::addLast);
                }
            }
        }
        return Set.copyOf(result);
    }

    private record EdgeArguments(
            String target,
            List<IrValue> arguments) {
        private EdgeArguments {
            arguments = List.copyOf(arguments);
        }
    }
}
