package xyz.melodysky.toolchain.localref;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import xyz.melodysky.ir.model.IrBlock;
import xyz.melodysky.ir.model.IrExceptionEdge;
import xyz.melodysky.ir.model.IrExceptionHandlers;
import xyz.melodysky.ir.model.IrInstruction;
import xyz.melodysky.ir.model.IrMethod;
import xyz.melodysky.ir.model.IrTerminator;
import xyz.melodysky.ir.model.IrType;
import xyz.melodysky.ir.model.IrValue;

/**
 * Immutable normal-CFG and reference liveness facts.
 */
final class NativeLocalReferenceCfgFacts {
    private final Map<String, IrBlock> blocks;
    private final Map<String, List<NormalEdgeFacts>> normalEdges;
    private final Map<String, Set<IrValue>> liveIn;
    private final Map<String, Set<IrValue>> liveOut;

    private NativeLocalReferenceCfgFacts(
            Map<String, IrBlock> blocks,
            Map<String, List<NormalEdgeFacts>> normalEdges,
            Map<String, Set<IrValue>> liveIn,
            Map<String, Set<IrValue>> liveOut) {
        this.blocks = blocks;
        this.normalEdges = normalEdges;
        this.liveIn = liveIn;
        this.liveOut = liveOut;
    }

    static NativeLocalReferenceCfgFacts analyze(IrMethod method) {
        LinkedHashMap<String, IrBlock> blocks = blocks(method);
        Map<String, List<NormalEdgeFacts>> edges =
                normalEdges(method);
        Liveness liveness = liveness(method, edges);
        return new NativeLocalReferenceCfgFacts(
                Collections.unmodifiableMap(blocks),
                edges,
                liveness.liveIn(),
                liveness.liveOut());
    }

    Map<String, IrBlock> blocks() {
        return blocks;
    }

    List<NormalEdgeFacts> normalEdges(String blockName) {
        return normalEdges.getOrDefault(blockName, List.of());
    }

    Map<String, Set<IrValue>> liveIn() {
        return liveIn;
    }

    Set<IrValue> liveOut(String blockName) {
        return liveOut.getOrDefault(blockName, Set.of());
    }

    LinkedHashSet<IrValue> explicitThrowNeeded(IrBlock block) {
        LinkedHashSet<IrValue> needed = new LinkedHashSet<>();
        IrExceptionHandlers.reachable(block.exceptionEdges()).stream()
                .map(this::handlerNeeded)
                .forEach(needed::addAll);
        return needed;
    }

    LinkedHashSet<IrValue> exceptionalNeeded(
            IrInstruction instruction) {
        LinkedHashSet<IrValue> needed = new LinkedHashSet<>();
        instruction.exceptionSites().stream()
                .flatMap(site -> IrExceptionHandlers
                        .reachable(site.handlers()).stream())
                .map(this::handlerNeeded)
                .forEach(needed::addAll);
        return needed;
    }

    Optional<String> validateUniformProtectedHandlerNeeds(
            IrMethod method) {
        for (IrBlock block : method.blocks()) {
            Optional<String> blockFailure =
                    validateUniformHandlerNeeds(
                            IrExceptionHandlers.reachable(
                                    block.exceptionEdges()),
                            block.name() + ":terminator");
            if (blockFailure.isPresent()) {
                return blockFailure;
            }
            for (int instructionIndex = 0;
                    instructionIndex < block.instructions().size();
                    instructionIndex++) {
                IrInstruction instruction =
                        block.instructions().get(instructionIndex);
                for (int siteIndex = 0;
                        siteIndex < instruction.exceptionSites().size();
                        siteIndex++) {
                    List<IrExceptionEdge> handlers = IrExceptionHandlers
                            .reachable(instruction
                            .exceptionSites()
                            .get(siteIndex)
                            .handlers());
                    if (handlers.size() < 2) {
                        continue;
                    }
                    Optional<String> failure =
                            validateUniformHandlerNeeds(
                                    handlers,
                                    block.name()
                                            + ":"
                                            + instructionIndex
                                            + ":"
                                            + siteIndex);
                    if (failure.isPresent()) {
                        return failure;
                    }
                }
            }
        }
        return Optional.empty();
    }

    private Optional<String> validateUniformHandlerNeeds(
            List<IrExceptionEdge> handlers,
            String location) {
        if (handlers.size() < 2) {
            return Optional.empty();
        }
        Set<IrValue> expected = handlerNeeded(handlers.get(0));
        for (int handlerIndex = 1;
                handlerIndex < handlers.size();
                handlerIndex++) {
            if (!expected.equals(handlerNeeded(handlers.get(handlerIndex)))) {
                return Optional.of(
                        "protected exception handlers require different "
                                + "reference live sets at "
                                + location);
            }
        }
        return Optional.empty();
    }

    private LinkedHashSet<IrValue> handlerNeeded(IrExceptionEdge edge) {
        LinkedHashSet<IrValue> needed = new LinkedHashSet<>();
        edge.arguments().stream()
                .filter(value -> value.type() == IrType.REFERENCE)
                .forEach(needed::add);
        Set<IrValue> directUses =
                liveIn.getOrDefault(edge.target(), Set.of());
        IrBlock target = blocks.get(edge.target());
        if (target != null) {
            directUses.stream()
                    .filter(value -> value.type() == IrType.REFERENCE)
                    .filter(value -> !target.parameters().contains(value))
                    .forEach(needed::add);
        }
        return needed;
    }

    boolean edgeNeeds(
            IrValue value,
            NormalEdgeFacts edge) {
        if (edge.arguments().contains(value)) {
            return true;
        }
        Set<IrValue> successorLive =
                liveIn.getOrDefault(
                        edge.edge().targetBlock(),
                        Set.of());
        if (!successorLive.contains(value)) {
            return false;
        }
        return !blocks.get(edge.edge().targetBlock())
                .parameters()
                .contains(value);
    }

    static List<IrValue> referenceOperands(
            IrInstruction instruction) {
        return instruction.operands().stream()
                .filter(value -> value.type() == IrType.REFERENCE)
                .toList();
    }

    static List<IrValue> referenceTerminatorUses(
            IrTerminator terminator) {
        ArrayList<IrValue> result = new ArrayList<>();
        terminator.value().filter(value ->
                value.type() == IrType.REFERENCE).ifPresent(result::add);
        terminator.condition().filter(value ->
                value.type() == IrType.REFERENCE).ifPresent(result::add);
        terminator.switchValue().filter(value ->
                value.type() == IrType.REFERENCE).ifPresent(result::add);
        terminator.targetArguments().stream()
                .filter(value -> value.type() == IrType.REFERENCE)
                .forEach(result::add);
        terminator.trueTargetArguments().stream()
                .filter(value -> value.type() == IrType.REFERENCE)
                .forEach(result::add);
        terminator.falseTargetArguments().stream()
                .filter(value -> value.type() == IrType.REFERENCE)
                .forEach(result::add);
        terminator.defaultTargetArguments().stream()
                .filter(value -> value.type() == IrType.REFERENCE)
                .forEach(result::add);
        terminator.switchCases().stream()
                .flatMap(switchCase ->
                        switchCase.arguments().stream())
                .filter(value -> value.type() == IrType.REFERENCE)
                .forEach(result::add);
        return List.copyOf(result);
    }

    private static Liveness liveness(
            IrMethod method,
            Map<String, List<NormalEdgeFacts>> edges) {
        Map<String, IrBlock> blocks = blocks(method);
        LinkedHashMap<String, Set<IrValue>> liveIn =
                new LinkedHashMap<>();
        LinkedHashMap<String, Set<IrValue>> liveOut =
                new LinkedHashMap<>();
        method.blocks().forEach(block -> {
            liveIn.put(block.name(), Set.of());
            liveOut.put(block.name(), Set.of());
        });
        boolean changed;
        do {
            changed = false;
            for (int index = method.blocks().size() - 1;
                    index >= 0;
                    index--) {
                IrBlock block = method.blocks().get(index);
                LinkedHashSet<IrValue> out = new LinkedHashSet<>();
                for (NormalEdgeFacts edge :
                        edges.getOrDefault(block.name(), List.of())) {
                    out.addAll(liveIn.getOrDefault(
                            edge.edge().targetBlock(),
                            Set.of()));
                    edge.arguments().stream()
                            .filter(value ->
                                    value.type() == IrType.REFERENCE)
                            .forEach(out::add);
                }
                for (IrExceptionEdge edge : IrExceptionHandlers.reachable(
                        block.exceptionEdges())) {
                    out.addAll(liveIn.getOrDefault(
                            edge.target(),
                            Set.of()));
                    edge.arguments().stream()
                            .filter(value -> value.type() == IrType.REFERENCE)
                            .forEach(out::add);
                }

                LinkedHashSet<IrValue> in =
                        new LinkedHashSet<>(out);
                referenceTerminatorUses(block.terminator())
                        .forEach(in::add);
                List<IrInstruction> instructions =
                        block.instructions();
                for (int instructionIndex =
                                instructions.size() - 1;
                        instructionIndex >= 0;
                        instructionIndex--) {
                    IrInstruction instruction =
                            instructions.get(instructionIndex);
                    instruction.result()
                            .filter(value ->
                                    value.type() == IrType.REFERENCE)
                            .ifPresent(in::remove);
                    LinkedHashSet<IrValue> exceptionDefinitions =
                            instruction.exceptionSites().stream()
                                    .flatMap(site ->
                                            site.exceptionValue().stream())
                                    .filter(value ->
                                            value.type()
                                                    == IrType.REFERENCE)
                                    .collect(java.util.stream.Collectors
                                            .toCollection(
                                                    LinkedHashSet::new));
                    exceptionDefinitions.forEach(in::remove);
                    referenceOperands(instruction).forEach(in::add);
                    instruction.exceptionSites().stream()
                            .flatMap(site -> IrExceptionHandlers
                                    .reachable(site.handlers())
                                    .stream())
                            .map(edge -> handlerNeeded(
                                    edge,
                                    blocks,
                                    liveIn))
                            .flatMap(Set::stream)
                            .filter(value ->
                                    !exceptionDefinitions.contains(value))
                            .forEach(in::add);
                }
                block.parameters().stream()
                        .filter(value -> value.type() == IrType.REFERENCE)
                        .forEach(in::remove);
                Set<IrValue> stableOut = Set.copyOf(out);
                Set<IrValue> stableIn = Set.copyOf(in);
                if (!stableOut.equals(liveOut.get(block.name()))
                        || !stableIn.equals(liveIn.get(block.name()))) {
                    liveOut.put(block.name(), stableOut);
                    liveIn.put(block.name(), stableIn);
                    changed = true;
                }
            }
        } while (changed);
        return new Liveness(
                Collections.unmodifiableMap(liveIn),
                Collections.unmodifiableMap(liveOut));
    }

    private static LinkedHashSet<IrValue> handlerNeeded(
            IrExceptionEdge edge,
            Map<String, IrBlock> blocks,
            Map<String, Set<IrValue>> liveIn) {
        LinkedHashSet<IrValue> needed = new LinkedHashSet<>();
        edge.arguments().stream()
                .filter(value -> value.type() == IrType.REFERENCE)
                .forEach(needed::add);
        IrBlock target = blocks.get(edge.target());
        liveIn.getOrDefault(edge.target(), Set.of()).stream()
                .filter(value -> value.type() == IrType.REFERENCE)
                .filter(value -> target == null
                        || !target.parameters().contains(value))
                .forEach(needed::add);
        return needed;
    }

    private static Map<String, List<NormalEdgeFacts>> normalEdges(
            IrMethod method) {
        LinkedHashMap<String, List<NormalEdgeFacts>> result =
                new LinkedHashMap<>();
        for (IrBlock block : method.blocks()) {
            ArrayList<NormalEdgeFacts> edges = new ArrayList<>();
            IrTerminator terminator = block.terminator();
            switch (terminator.kind()) {
                case GOTO -> edges.add(edge(
                        block.name(),
                        0,
                        terminator.target().orElseThrow(),
                        terminator.targetArguments()));
                case BRANCH -> {
                    edges.add(edge(
                            block.name(),
                            0,
                            terminator.trueTarget().orElseThrow(),
                            terminator.trueTargetArguments()));
                    edges.add(edge(
                            block.name(),
                            1,
                            terminator.falseTarget().orElseThrow(),
                            terminator.falseTargetArguments()));
                }
                case SWITCH -> {
                    edges.add(edge(
                            block.name(),
                            0,
                            terminator.defaultTarget().orElseThrow(),
                            terminator.defaultTargetArguments()));
                    for (int index = 0;
                            index < terminator.switchCases().size();
                            index++) {
                        var switchCase =
                                terminator.switchCases().get(index);
                        edges.add(edge(
                                block.name(),
                                index + 1,
                                switchCase.target(),
                                switchCase.arguments()));
                    }
                }
                case RETURN, THROW -> {
                }
            }
            result.put(block.name(), List.copyOf(edges));
        }
        return Collections.unmodifiableMap(result);
    }

    private static NormalEdgeFacts edge(
            String source,
            int ordinal,
            String target,
            List<IrValue> arguments) {
        return new NormalEdgeFacts(
                new NativeLocalReferenceNormalEdge(
                        source,
                        ordinal,
                        target),
                arguments);
    }

    private static LinkedHashMap<String, IrBlock> blocks(
            IrMethod method) {
        LinkedHashMap<String, IrBlock> result = new LinkedHashMap<>();
        for (IrBlock block : method.blocks()) {
            if (result.putIfAbsent(block.name(), block) != null) {
                throw new IllegalArgumentException(
                        "duplicate IR block " + block.name());
            }
        }
        return result;
    }

    record NormalEdgeFacts(
            NativeLocalReferenceNormalEdge edge,
            List<IrValue> arguments) {
        NormalEdgeFacts {
            arguments = List.copyOf(arguments);
        }
    }

    private record Liveness(
            Map<String, Set<IrValue>> liveIn,
            Map<String, Set<IrValue>> liveOut) {
    }
}
