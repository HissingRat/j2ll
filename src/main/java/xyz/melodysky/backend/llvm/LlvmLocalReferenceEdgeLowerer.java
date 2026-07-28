package xyz.melodysky.backend.llvm;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import xyz.melodysky.backend.llvm.model.LlvmBasicBlock;
import xyz.melodysky.backend.llvm.model.LlvmSwitchCase;
import xyz.melodysky.backend.llvm.model.LlvmTerminator;
import xyz.melodysky.ir.model.IrBlock;
import xyz.melodysky.ir.model.IrTerminatorKind;
import xyz.melodysky.toolchain.localref.NativeLocalReferenceNormalEdge;
import xyz.melodysky.toolchain.localref.NativeLocalReferencePlan;

/**
 * Inserts cleanup adapters selected by a verified local-reference plan and
 * splits parallel LLVM edges so every phi incoming has a distinct
 * predecessor.
 */
final class LlvmLocalReferenceEdgeLowerer {
    EdgeResult lower(
            IrBlock source,
            LlvmExceptionFlowLowerer.BlockResult lowered,
            NativeLocalReferencePlan plan,
            LlvmLocalReferenceLowering localReferences,
            Set<String> usedBlockNames) {
        return lower(
                source,
                lowered,
                plan.methodKey(),
                Optional.of(plan),
                Optional.of(localReferences),
                usedBlockNames);
    }

    EdgeResult splitParallelEdges(
            IrBlock source,
            LlvmExceptionFlowLowerer.BlockResult lowered,
            String methodKey,
            Set<String> usedBlockNames) {
        return lower(
                source,
                lowered,
                methodKey,
                Optional.empty(),
                Optional.empty(),
                usedBlockNames);
    }

    private EdgeResult lower(
            IrBlock source,
            LlvmExceptionFlowLowerer.BlockResult lowered,
            String methodKey,
            Optional<NativeLocalReferencePlan> plan,
            Optional<LlvmLocalReferenceLowering> localReferences,
            Set<String> usedBlockNames) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(lowered, "lowered");
        Objects.requireNonNull(methodKey, "methodKey");
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(localReferences, "localReferences");
        Objects.requireNonNull(usedBlockNames, "usedBlockNames");

        List<NativeLocalReferenceNormalEdge> edges = normalEdges(source);
        Set<String> parallelTargets = edges.stream()
                .collect(Collectors.groupingBy(
                        NativeLocalReferenceNormalEdge::targetBlock,
                        Collectors.counting()))
                .entrySet()
                .stream()
                .filter(entry -> entry.getValue() > 1)
                .map(Map.Entry::getKey)
                .collect(Collectors.toUnmodifiableSet());
        LinkedHashMap<Integer, String> adapterByOrdinal =
                new LinkedHashMap<>();
        LinkedHashMap<NativeLocalReferenceNormalEdge, String>
                predecessorByEdge = new LinkedHashMap<>();
        ArrayList<LlvmBasicBlock> adapters = new ArrayList<>();
        for (NativeLocalReferenceNormalEdge edge : edges) {
            List<xyz.melodysky.ir.model.IrValue> releases =
                    plan.map(value -> value.releasesOn(edge))
                            .orElse(List.of());
            if (releases.isEmpty()
                    && !parallelTargets.contains(edge.targetBlock())) {
                predecessorByEdge.put(
                        edge,
                        lowered.normalExitBlock());
                continue;
            }
            String adapter = uniqueName(
                    "j2ll.lref.edge."
                            + stableHash(methodKey
                                    + "|"
                                    + edge.sourceBlock()
                                    + "|"
                                    + edge.ordinal()
                                    + "|"
                                    + edge.targetBlock()),
                    usedBlockNames);
            adapterByOrdinal.put(edge.ordinal(), adapter);
            predecessorByEdge.put(edge, adapter);
            adapters.add(new LlvmBasicBlock(
                    adapter,
                    releases.isEmpty()
                            ? List.of()
                            : localReferences
                                    .orElseThrow()
                                    .releases(releases),
                    LlvmTerminator.gotoBlock(edge.targetBlock())));
        }
        if (adapterByOrdinal.isEmpty()) {
            return new EdgeResult(
                    lowered.blocks(),
                    predecessorByEdge);
        }

        ArrayList<LlvmBasicBlock> blocks =
                new ArrayList<>(lowered.blocks().size()
                        + adapters.size());
        boolean replaced = false;
        for (LlvmBasicBlock block : lowered.blocks()) {
            if (block.name().equals(lowered.normalExitBlock())) {
                blocks.add(new LlvmBasicBlock(
                        block.name(),
                        block.instructions(),
                        rewriteTerminator(
                                block.terminator(),
                                adapterByOrdinal)));
                replaced = true;
            } else {
                blocks.add(block);
            }
        }
        if (!replaced) {
            throw new IllegalArgumentException(
                    "normal local-reference cleanup exit block is missing: "
                            + lowered.normalExitBlock());
        }
        blocks.addAll(adapters);
        return new EdgeResult(blocks, predecessorByEdge);
    }

    private LlvmTerminator rewriteTerminator(
            LlvmTerminator terminator,
            Map<Integer, String> adapters) {
        return switch (terminator.kind()) {
            case GOTO -> LlvmTerminator.gotoBlock(
                    adapters.getOrDefault(
                            0,
                            terminator.target().orElseThrow()));
            case BRANCH -> LlvmTerminator.branch(
                    terminator.condition().orElseThrow(),
                    adapters.getOrDefault(
                            0,
                            terminator.trueTarget().orElseThrow()),
                    adapters.getOrDefault(
                            1,
                            terminator.falseTarget().orElseThrow()));
            case SWITCH -> {
                ArrayList<LlvmSwitchCase> cases = new ArrayList<>();
                for (int index = 0;
                        index < terminator.switchCases().size();
                        index++) {
                    LlvmSwitchCase switchCase =
                            terminator.switchCases().get(index);
                    cases.add(new LlvmSwitchCase(
                            switchCase.key(),
                            adapters.getOrDefault(
                                    index + 1,
                                    switchCase.target())));
                }
                yield LlvmTerminator.switchOn(
                        terminator.switchValue().orElseThrow(),
                        adapters.getOrDefault(
                                0,
                                terminator.defaultTarget().orElseThrow()),
                        cases);
            }
            case RETURN, THROW -> throw new IllegalArgumentException(
                    "activation exit cannot have a normal-edge cleanup adapter");
        };
    }

    private List<NativeLocalReferenceNormalEdge> normalEdges(
            IrBlock source) {
        var terminator = source.terminator();
        ArrayList<NativeLocalReferenceNormalEdge> result =
                new ArrayList<>();
        if (terminator.kind() == IrTerminatorKind.GOTO) {
            result.add(new NativeLocalReferenceNormalEdge(
                    source.name(),
                    0,
                    terminator.target().orElseThrow()));
        } else if (terminator.kind() == IrTerminatorKind.BRANCH) {
            result.add(new NativeLocalReferenceNormalEdge(
                    source.name(),
                    0,
                    terminator.trueTarget().orElseThrow()));
            result.add(new NativeLocalReferenceNormalEdge(
                    source.name(),
                    1,
                    terminator.falseTarget().orElseThrow()));
        } else if (terminator.kind() == IrTerminatorKind.SWITCH) {
            result.add(new NativeLocalReferenceNormalEdge(
                    source.name(),
                    0,
                    terminator.defaultTarget().orElseThrow()));
            for (int index = 0;
                    index < terminator.switchCases().size();
                    index++) {
                result.add(new NativeLocalReferenceNormalEdge(
                        source.name(),
                        index + 1,
                        terminator.switchCases().get(index).target()));
            }
        }
        return List.copyOf(result);
    }

    private String uniqueName(String preferred, Set<String> used) {
        String candidate = preferred;
        int suffix = 1;
        while (!used.add(candidate)) {
            candidate = preferred + "." + suffix++;
        }
        return candidate;
    }

    private String stableHash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder();
            for (int index = 0; index < 8; index++) {
                result.append(String.format("%02x", digest[index]));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(
                    "SHA-256 digest is unavailable",
                    exception);
        }
    }

    record EdgeResult(
            List<LlvmBasicBlock> blocks,
            Map<NativeLocalReferenceNormalEdge, String>
                    predecessorByEdge) {
        EdgeResult {
            blocks = List.copyOf(Objects.requireNonNull(
                    blocks,
                    "blocks"));
            predecessorByEdge = Map.copyOf(Objects.requireNonNull(
                    predecessorByEdge,
                    "predecessorByEdge"));
        }
    }
}
