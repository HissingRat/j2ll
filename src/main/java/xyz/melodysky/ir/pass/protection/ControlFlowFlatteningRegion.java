package xyz.melodysky.ir.pass.protection;

import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.stream.IntStream;

/**
 * One bounded single-entry control-flow region selected for flattening.
 *
 * <p>The entry block is listed first. States are a dense permutation of
 * {@code [0, memberCount)} so the rewriter does not need a lookup table or an
 * expanded dispatcher state space.</p>
 */
public record ControlFlowFlatteningRegion(
        String regionId,
        String entryBlock,
        List<String> memberBlocks,
        Map<String, Integer> stateByBlock) {
    public static final int MAX_MEMBER_BLOCKS = 32;

    public ControlFlowFlatteningRegion {
        Objects.requireNonNull(regionId, "regionId");
        Objects.requireNonNull(entryBlock, "entryBlock");
        if (regionId.isBlank() || entryBlock.isBlank()) {
            throw new IllegalArgumentException("CFF region identities must not be blank");
        }
        memberBlocks = List.copyOf(Objects.requireNonNull(memberBlocks, "memberBlocks"));
        if (memberBlocks.size() < 2 || memberBlocks.size() > MAX_MEMBER_BLOCKS) {
            throw new IllegalArgumentException(
                    "CFF region must contain between 2 and " + MAX_MEMBER_BLOCKS + " blocks");
        }
        if (!memberBlocks.get(0).equals(entryBlock)) {
            throw new IllegalArgumentException("CFF region entry must be the first member block");
        }
        if (memberBlocks.stream().anyMatch(String::isBlank)
                || new HashSet<>(memberBlocks).size() != memberBlocks.size()) {
            throw new IllegalArgumentException("CFF region member blocks must be distinct and non-blank");
        }

        LinkedHashMap<String, Integer> stableStates = new LinkedHashMap<>(
                Objects.requireNonNull(stateByBlock, "stateByBlock"));
        if (!stableStates.keySet().equals(new java.util.LinkedHashSet<>(memberBlocks))) {
            throw new IllegalArgumentException("CFF region state map must cover exactly the member blocks");
        }
        HashSet<Integer> actualStates = new HashSet<>(stableStates.values());
        HashSet<Integer> expectedStates = IntStream.range(0, memberBlocks.size())
                .boxed()
                .collect(java.util.stream.Collectors.toCollection(HashSet::new));
        if (!actualStates.equals(expectedStates)) {
            throw new IllegalArgumentException("CFF region states must be a dense permutation");
        }
        stateByBlock = Collections.unmodifiableMap(stableStates);
    }

    public boolean contains(String blockName) {
        return stateByBlock.containsKey(blockName);
    }

    public OptionalInt state(String blockName) {
        Integer state = stateByBlock.get(blockName);
        return state == null ? OptionalInt.empty() : OptionalInt.of(state);
    }
}
