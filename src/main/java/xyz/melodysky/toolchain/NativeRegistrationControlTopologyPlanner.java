package xyz.melodysky.toolchain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import xyz.melodysky.toolchain.nativetext.NativeTextBuildKey;

/** Freezes the bounded forward-only registration control topology. */
final class NativeRegistrationControlTopologyPlanner {
    NativeRegistrationControlTopologyPlan plan(
            List<NativeRegistrationTextPlan.Owner> physicalOwners,
            NativeTextBuildKey buildKey) {
        List<NativeRegistrationTextPlan.Owner> sources = List.copyOf(
                Objects.requireNonNull(
                        physicalOwners,
                        "physicalOwners"));
        NativeRegistrationControlSymbolDeriver symbols =
                new NativeRegistrationControlSymbolDeriver(buildKey);
        ArrayList<NativeRegistrationControlTopologyPlan.Owner> owners =
                new ArrayList<>();
        for (int index = 0; index < sources.size(); index++) {
            NativeRegistrationTextPlan.Owner owner = sources.get(index);
            owners.add(new NativeRegistrationControlTopologyPlan.Owner(
                    index,
                    owner,
                    symbols.ownerSymbol(owner.owner())));
        }
        List<NativeRegistrationControlTopologyPlan.Owner> frozenOwners =
                List.copyOf(owners);
        List<NativeRegistrationControlTopologyPlan.Chunk> chunks =
                chunks(frozenOwners, symbols);
        NativeRegistrationControlTopologyPlan.FailureSymbols failures =
                new NativeRegistrationControlTopologyPlan.FailureSymbols(
                        symbols.failureLeafSymbol("owner-rollback"),
                        symbols.failureLeafSymbol(
                                "owner-exception-restore"),
                        symbols.failureLeafSymbol("aggregate-rollback"),
                        symbols.failureLeafSymbol(
                                "aggregate-exception-restore"));
        return new NativeRegistrationControlTopologyPlan(
                symbols.aggregateSymbol(),
                frozenOwners,
                chunks,
                failures);
    }

    private List<NativeRegistrationControlTopologyPlan.Chunk> chunks(
            List<NativeRegistrationControlTopologyPlan.Owner> owners,
            NativeRegistrationControlSymbolDeriver symbols) {
        int count = NativeRegistrationControlTopologyPlan
                .expectedChunkCount(owners.size());
        if (count == 0) {
            return List.of();
        }
        int baseSize = owners.size() / count;
        int remainder = owners.size() % count;
        Set<Integer> enlarged = enlargedChunks(
                count,
                remainder,
                symbols);
        ArrayList<NativeRegistrationControlTopologyPlan.Chunk> chunks =
                new ArrayList<>();
        int start = 0;
        for (int ordinal = 0; ordinal < count; ordinal++) {
            int size = baseSize + (enlarged.contains(ordinal) ? 1 : 0);
            int end = Math.addExact(start, size);
            List<NativeRegistrationControlTopologyPlan.Owner> members =
                    List.copyOf(owners.subList(start, end));
            chunks.add(new NativeRegistrationControlTopologyPlan.Chunk(
                    ordinal,
                    start,
                    end,
                    symbols.chunkSymbol(
                            ordinal,
                            members.stream()
                                    .map(member -> member.source().owner())
                                    .toList()),
                    members));
            start = end;
        }
        return List.copyOf(chunks);
    }

    private Set<Integer> enlargedChunks(
            int chunkCount,
            int remainder,
            NativeRegistrationControlSymbolDeriver symbols) {
        if (remainder == 0) {
            return Set.of();
        }
        List<Integer> ranked = java.util.stream.IntStream
                .range(0, chunkCount)
                .boxed()
                .sorted(Comparator
                        .comparing((Integer ordinal) ->
                                symbols.chunkRemainderRank(ordinal))
                        .thenComparingInt(Integer::intValue))
                .toList();
        return Set.copyOf(new HashSet<>(
                ranked.subList(0, remainder)));
    }
}
