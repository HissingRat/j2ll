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
        NativeRegistrationControlRoutePlan routePlan = sources.isEmpty()
                ? NativeRegistrationControlRoutePlan.disabled()
                : entryRoutes(symbols);
        return new NativeRegistrationControlTopologyPlan(
                symbols.aggregateSymbol(),
                frozenOwners,
                chunks,
                routePlan,
                failures);
    }

    private NativeRegistrationControlRoutePlan entryRoutes(
            NativeRegistrationControlSymbolDeriver symbols) {
        List<List<NativeRegistrationControlRoutePlan.Parameter>> orders =
                parameterOrders(symbols);
        List<NativeRegistrationPostCallRecipe> recipes =
                java.util.Arrays.stream(
                                NativeRegistrationPostCallRecipe.values())
                        .sorted(Comparator.comparing(
                                symbols::routeRecipeRank))
                        .toList();
        if (recipes.size()
                != NativeRegistrationControlRoutePlan.ROUTE_COUNT) {
            throw new IllegalStateException(
                    "registration route recipe family is not closed");
        }
        ArrayList<NativeRegistrationControlRoutePlan.Route> routes =
                new ArrayList<>();
        for (int ordinal = 0;
                ordinal < NativeRegistrationControlRoutePlan.ROUTE_COUNT;
                ordinal++) {
            routes.add(new NativeRegistrationControlRoutePlan.Route(
                    ordinal,
                    symbols.routeSymbol(ordinal),
                    orders.get(ordinal),
                    ordinal == 1
                            ? NativeRegistrationControlRoutePlan.TargetKind.ROUTE
                            : NativeRegistrationControlRoutePlan.TargetKind.AGGREGATE,
                    ordinal == 1 ? 2 : -1,
                    recipes.get(ordinal),
                    symbols.routeMaterial(ordinal, "witness"),
                    symbols.routeMaterial(ordinal, "post-call")));
        }
        return new NativeRegistrationControlRoutePlan(
                routes,
                symbols.rootMaterial("guard"),
                symbols.rootMaterial("selector"),
                symbols.rootMaterial("post-call"),
                symbols.rootSelectorShift());
    }

    private List<List<NativeRegistrationControlRoutePlan.Parameter>>
            parameterOrders(
                    NativeRegistrationControlSymbolDeriver symbols) {
        NativeRegistrationControlRoutePlan.Parameter vm =
                NativeRegistrationControlRoutePlan.Parameter.VM;
        NativeRegistrationControlRoutePlan.Parameter reserved =
                NativeRegistrationControlRoutePlan.Parameter.RESERVED;
        NativeRegistrationControlRoutePlan.Parameter guard =
                NativeRegistrationControlRoutePlan.Parameter.GUARD;
        return java.util.stream.Stream.of(
                        List.of(vm, reserved, guard),
                        List.of(vm, guard, reserved),
                        List.of(reserved, vm, guard),
                        List.of(reserved, guard, vm),
                        List.of(guard, vm, reserved),
                        List.of(guard, reserved, vm))
                .sorted(Comparator.comparing(
                        symbols::routeParameterOrderRank))
                .limit(NativeRegistrationControlRoutePlan.ROUTE_COUNT)
                .toList();
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
        List<NativeRegistrationChunkPostCallVariant> variants =
                java.util.Arrays.stream(
                                NativeRegistrationChunkPostCallVariant.values())
                        .sorted(Comparator.comparing(
                                symbols::chunkPostCallVariantRank))
                        .toList();
        if (variants.size()
                != NativeRegistrationControlTopologyPlan.MAX_CHUNKS) {
            throw new IllegalStateException(
                    "registration chunk post-call family is not closed");
        }
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
                    members,
                    variants.get(ordinal),
                    symbols.chunkMaterial(ordinal, "witness"),
                    symbols.chunkMaterial(ordinal, "post-call")));
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
