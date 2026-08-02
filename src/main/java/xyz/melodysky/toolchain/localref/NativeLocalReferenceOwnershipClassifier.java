package xyz.melodysky.toolchain.localref;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import xyz.melodysky.ir.model.IrBlock;
import xyz.melodysky.ir.model.IrInstruction;
import xyz.melodysky.ir.model.IrMethod;
import xyz.melodysky.ir.model.IrOpcode;
import xyz.melodysky.ir.model.IrType;
import xyz.melodysky.ir.model.IrValue;
import xyz.melodysky.runtime.PureNativeJdkRuntimeHelpers;

/**
 * Classifies reference SSA values as owned, borrowed, dynamic or alias.
 */
final class NativeLocalReferenceOwnershipClassifier {
    Classification classify(IrMethod method) {
        LinkedHashMap<String, NativeLocalReferenceOwnership> ownership =
                ownership(method);
        Optional<String> definitionFailure =
                validateReferenceDefinitions(method, ownership);
        if (definitionFailure.isPresent()) {
            return new Classification(
                    ownership,
                    definitionFailure);
        }
        return new Classification(
                ownership,
                validateOwnedAliasLinearity(method, ownership));
    }

    boolean shouldEmitRelease(
            IrValue value,
            Map<String, NativeLocalReferenceOwnership> ownership) {
        return ownershipMayBeOwned(
                value.name(),
                ownership,
                new LinkedHashSet<>());
    }

    Optional<IrValue> aliasSource(IrInstruction instruction) {
        if (instruction.result()
                        .filter(value ->
                                value.type() == IrType.REFERENCE)
                        .isEmpty()
                || instruction.operands().isEmpty()) {
            return Optional.empty();
        }
        if (instruction.opcode() == IrOpcode.CHECKCAST) {
            return Optional.of(instruction.operands().get(0));
        }
        if (instruction.opcode() == IrOpcode.CALL_RUNTIME_HELPER
                && (baseSymbol(instruction.symbol().orElse(""))
                                .equals("j2ll_rt_objects_require_non_null")
                        || PureNativeJdkRuntimeHelpers
                                .returnsOperandZeroAlias(
                                        instruction.symbol().orElse("")))) {
            return Optional.of(instruction.operands().get(0));
        }
        return Optional.empty();
    }

    private LinkedHashMap<String, NativeLocalReferenceOwnership> ownership(
            IrMethod method) {
        LinkedHashMap<String, NativeLocalReferenceOwnership> result =
                new LinkedHashMap<>();
        method.parameters().stream()
                .filter(value -> value.type() == IrType.REFERENCE)
                .forEach(value -> result.put(
                        value.name(),
                        NativeLocalReferenceOwnership.borrowed()));
        for (IrBlock block : method.blocks()) {
            block.parameters().stream()
                    .filter(value -> value.type() == IrType.REFERENCE)
                    .forEach(value -> result.put(
                            value.name(),
                            NativeLocalReferenceOwnership.dynamic()));
            for (IrInstruction instruction : block.instructions()) {
                instruction.exceptionSites().stream()
                        .flatMap(site -> site.exceptionValue().stream())
                        .filter(value ->
                                value.type() == IrType.REFERENCE)
                        .forEach(value -> result.put(
                                value.name(),
                                NativeLocalReferenceOwnership.owned()));
                instruction.result()
                        .filter(value ->
                                value.type() == IrType.REFERENCE)
                        .ifPresent(value -> result.put(
                                value.name(),
                                ownershipOf(instruction)));
            }
        }
        return result;
    }

    private NativeLocalReferenceOwnership ownershipOf(
            IrInstruction instruction) {
        if (instruction.opcode() == IrOpcode.CONST_NULL) {
            return NativeLocalReferenceOwnership.borrowed();
        }
        return aliasSource(instruction)
                .map(source ->
                        NativeLocalReferenceOwnership.alias(
                                source.name()))
                .orElseGet(NativeLocalReferenceOwnership::owned);
    }

    private Optional<String> validateReferenceDefinitions(
            IrMethod method,
            Map<String, NativeLocalReferenceOwnership> ownership) {
        for (IrValue value : allReferenceUses(method)) {
            if (!ownership.containsKey(value.name())) {
                return Optional.of(
                        "reference value has no ownership origin: "
                                + value.name());
            }
        }
        for (Map.Entry<String, NativeLocalReferenceOwnership> entry :
                ownership.entrySet()) {
            if (entry.getValue().kind()
                            == NativeLocalReferenceOwnership.Kind.ALIAS
                    && !ownership.containsKey(
                            entry.getValue().aliasSource().orElseThrow())) {
                return Optional.of(
                        "reference alias has no ownership source: "
                                + entry.getKey());
            }
        }
        return Optional.empty();
    }

    private Optional<String> validateOwnedAliasLinearity(
            IrMethod method,
            Map<String, NativeLocalReferenceOwnership> ownership) {
        Map<String, IrBlock> blocks = method.blocks().stream()
                .collect(java.util.stream.Collectors.toMap(
                        IrBlock::name,
                        block -> block));
        for (IrBlock block : method.blocks()) {
            for (int instructionIndex = 0;
                    instructionIndex < block.instructions().size();
                    instructionIndex++) {
                IrInstruction instruction =
                        block.instructions().get(instructionIndex);
                Optional<IrValue> alias = aliasSource(instruction);
                if (alias.isEmpty()
                        || !shouldEmitRelease(
                                alias.orElseThrow(),
                                ownership)) {
                    continue;
                }
                if (hasReachableUseAfterAlias(
                        block,
                        instructionIndex,
                        alias.orElseThrow(),
                        blocks,
                        ownership)) {
                    return Optional.of(
                            "owned reference alias remains live after "
                                    + "ownership transfer: "
                                    + alias.orElseThrow().name());
                }
            }
        }
        return Optional.empty();
    }

    private boolean hasReachableUseAfterAlias(
            IrBlock definingBlock,
            int instructionIndex,
            IrValue source,
            Map<String, IrBlock> blocks,
            Map<String, NativeLocalReferenceOwnership> ownership) {
        List<IrInstruction> instructions = definingBlock.instructions();
        for (int index = instructionIndex + 1;
                index < instructions.size();
                index++) {
            if (instructionUses(instructions.get(index), source)) {
                return true;
            }
        }
        if (NativeLocalReferenceCfgFacts.referenceTerminatorUses(
                        definingBlock.terminator())
                .contains(source)) {
            return true;
        }

        ArrayDeque<Successor> work = new ArrayDeque<>();
        for (int index = instructionIndex + 1;
                index < instructions.size();
                index++) {
            instructions.get(index).exceptionSites().stream()
                    .flatMap(site -> site.handlers().stream())
                    .map(edge -> new Successor(
                            edge.target(),
                            edge.arguments()))
                    .forEach(work::addLast);
        }
        normalSuccessors(definingBlock).forEach(work::addLast);
        if (definingBlock.terminator().kind()
                        == xyz.melodysky.ir.model.IrTerminatorKind.THROW
                && !definingBlock.exceptionEdges().isEmpty()) {
            definingBlock.exceptionEdges().stream()
                    .map(edge -> new Successor(
                            edge.target(),
                            edge.arguments()))
                    .forEach(work::addLast);
        }

        LinkedHashSet<String> visited = new LinkedHashSet<>();
        while (!work.isEmpty()) {
            Successor successor = work.removeFirst();
            String blockName = successor.target();
            IrBlock block = blocks.get(blockName);
            if (block == null) {
                continue;
            }
            if (rebinds(
                    source,
                    successor.arguments(),
                    block,
                    ownership)) {
                continue;
            }
            if (!visited.add(blockName)) {
                continue;
            }
            ValueFlow valueFlow = valueFlow(block, source);
            if (valueFlow == ValueFlow.USED) {
                return true;
            }
            if (valueFlow == ValueFlow.REDEFINED) {
                continue;
            }
            successors(block).forEach(work::addLast);
        }
        return false;
    }

    private boolean rebinds(
            IrValue source,
            List<IrValue> arguments,
            IrBlock target,
            Map<String, NativeLocalReferenceOwnership> ownership) {
        for (int index = 0; index < target.parameters().size(); index++) {
            if (!target.parameters().get(index).equals(source)) {
                continue;
            }
            if (arguments.size() != target.parameters().size()) {
                return false;
            }
            return !ownershipOrigin(
                            arguments.get(index).name(),
                            ownership,
                            new LinkedHashSet<>())
                    .equals(ownershipOrigin(
                            source.name(),
                            ownership,
                            new LinkedHashSet<>()));
        }
        return false;
    }

    private ValueFlow valueFlow(IrBlock block, IrValue value) {
        for (IrInstruction instruction : block.instructions()) {
            if (instructionUses(instruction, value)) {
                return ValueFlow.USED;
            }
            if (instruction.result().filter(value::equals).isPresent()
                    || instruction.exceptionSites().stream()
                            .flatMap(site ->
                                    site.exceptionValue().stream())
                            .anyMatch(value::equals)) {
                return ValueFlow.REDEFINED;
            }
        }
        boolean used = NativeLocalReferenceCfgFacts.referenceTerminatorUses(
                                block.terminator())
                        .contains(value)
                || block.exceptionEdges().stream()
                        .flatMap(edge -> edge.arguments().stream())
                        .anyMatch(value::equals);
        return used ? ValueFlow.USED : ValueFlow.UNCHANGED;
    }

    private boolean instructionUses(
            IrInstruction instruction,
            IrValue value) {
        return NativeLocalReferenceCfgFacts.referenceOperands(instruction)
                        .contains(value)
                || instruction.exceptionSites().stream()
                        .flatMap(site -> site.handlers().stream())
                        .flatMap(edge -> edge.arguments().stream())
                        .anyMatch(value::equals);
    }

    private List<Successor> successors(IrBlock block) {
        ArrayList<Successor> result =
                new ArrayList<>(normalSuccessors(block));
        block.instructions().stream()
                .flatMap(instruction ->
                        instruction.exceptionSites().stream())
                .flatMap(site -> site.handlers().stream())
                .map(edge -> new Successor(
                        edge.target(),
                        edge.arguments()))
                .forEach(result::add);
        block.exceptionEdges().stream()
                .map(edge -> new Successor(
                        edge.target(),
                        edge.arguments()))
                .forEach(result::add);
        return List.copyOf(result);
    }

    private List<Successor> normalSuccessors(IrBlock block) {
        ArrayList<Successor> result = new ArrayList<>();
        var terminator = block.terminator();
        terminator.target().ifPresent(target -> result.add(
                new Successor(target, terminator.targetArguments())));
        terminator.trueTarget().ifPresent(target -> result.add(
                new Successor(target, terminator.trueTargetArguments())));
        terminator.falseTarget().ifPresent(target -> result.add(
                new Successor(target, terminator.falseTargetArguments())));
        terminator.defaultTarget().ifPresent(target -> result.add(
                new Successor(target, terminator.defaultTargetArguments())));
        terminator.switchCases().forEach(switchCase -> result.add(
                new Successor(
                        switchCase.target(),
                        switchCase.arguments())));
        return List.copyOf(result);
    }

    private String ownershipOrigin(
            String value,
            Map<String, NativeLocalReferenceOwnership> ownership,
            Set<String> visiting) {
        NativeLocalReferenceOwnership current = ownership.get(value);
        if (current == null
                || current.kind()
                        != NativeLocalReferenceOwnership.Kind.ALIAS) {
            return value;
        }
        if (!visiting.add(value)) {
            return value;
        }
        String result = ownershipOrigin(
                current.aliasSource().orElseThrow(),
                ownership,
                visiting);
        visiting.remove(value);
        return result;
    }

    private List<IrValue> allReferenceUses(IrMethod method) {
        ArrayList<IrValue> result = new ArrayList<>();
        for (IrBlock block : method.blocks()) {
            for (IrInstruction instruction : block.instructions()) {
                NativeLocalReferenceCfgFacts.referenceOperands(
                                instruction)
                        .forEach(result::add);
                instruction.exceptionSites().stream()
                        .flatMap(site -> site.handlers().stream())
                        .flatMap(edge -> edge.arguments().stream())
                        .filter(value ->
                                value.type() == IrType.REFERENCE)
                        .forEach(result::add);
            }
            NativeLocalReferenceCfgFacts.referenceTerminatorUses(
                            block.terminator())
                    .forEach(result::add);
            block.exceptionEdges().stream()
                    .flatMap(edge -> edge.arguments().stream())
                    .filter(value -> value.type() == IrType.REFERENCE)
                    .forEach(result::add);
        }
        return List.copyOf(result);
    }

    private boolean ownershipMayBeOwned(
            String value,
            Map<String, NativeLocalReferenceOwnership> ownership,
            Set<String> visiting) {
        NativeLocalReferenceOwnership current = ownership.get(value);
        if (current == null) {
            return false;
        }
        return switch (current.kind()) {
            case OWNED, DYNAMIC -> true;
            case BORROWED -> false;
            case ALIAS -> {
                if (!visiting.add(value)) {
                    throw new IllegalArgumentException(
                            "cyclic reference ownership alias: " + value);
                }
                boolean owned = ownershipMayBeOwned(
                        current.aliasSource().orElseThrow(),
                        ownership,
                        visiting);
                visiting.remove(value);
                yield owned;
            }
        };
    }

    private String baseSymbol(String symbol) {
        int separator = symbol.indexOf('|');
        return separator < 0 ? symbol : symbol.substring(0, separator);
    }

    record Classification(
            Map<String, NativeLocalReferenceOwnership> ownershipByValue,
            Optional<String> failureReason) {
        Classification {
            ownershipByValue = Collections.unmodifiableMap(
                    new LinkedHashMap<>(ownershipByValue));
        }
    }

    private record Successor(
            String target,
            List<IrValue> arguments) {
        private Successor {
            arguments = List.copyOf(arguments);
        }
    }

    private enum ValueFlow {
        USED,
        REDEFINED,
        UNCHANGED
    }
}
