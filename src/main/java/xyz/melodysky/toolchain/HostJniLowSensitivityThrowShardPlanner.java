package xyz.melodysky.toolchain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Freezes collected logical throw sites into bounded physical leaf shards. */
final class HostJniLowSensitivityThrowShardPlanner {
    private final HostJniLowSensitivityThrowShardDeriver deriver;

    HostJniLowSensitivityThrowShardPlanner(
            HostJniLowSensitivityThrowShardDeriver deriver) {
        this.deriver = Objects.requireNonNull(deriver, "deriver");
    }

    HostJniLowSensitivityThrowShardPlan plan(
            String declarationAnchor,
            List<HostJniLowSensitivityThrowShardPlan.Site> sites) {
        Objects.requireNonNull(sites, "sites");
        LinkedHashMap<String, ArrayList<HostJniLowSensitivityThrowShardPlan.Site>>
                sitesByLeaf = new LinkedHashMap<>();
        for (HostJniLowSensitivityThrowShardPlan.Site site : sites) {
            sitesByLeaf.computeIfAbsent(
                            site.leafIdentity(),
                            ignored -> new ArrayList<>())
                    .add(site);
        }

        ArrayList<HostJniLowSensitivityThrowShardPlan.Shard> shards =
                new ArrayList<>();
        for (Map.Entry<String, ArrayList<
                HostJniLowSensitivityThrowShardPlan.Site>> entry
                : sitesByLeaf.entrySet()) {
            String leafIdentity = entry.getKey();
            ArrayList<HostJniLowSensitivityThrowShardPlan.Site> ordered =
                    new ArrayList<>(entry.getValue());
            ordered.sort(Comparator
                    .comparing((HostJniLowSensitivityThrowShardPlan.Site site) ->
                            deriver.siteLayoutRank(
                                    leafIdentity,
                                    site.identity()))
                    .thenComparing(
                            HostJniLowSensitivityThrowShardPlan.Site::identity));
            int shardCount = (ordered.size() - 1)
                    / HostJniLowSensitivityThrowShardPlan
                            .MAX_DIRECT_CALL_SITES_PER_SHARD
                    + 1;
            ArrayList<ArrayList<HostJniLowSensitivityThrowShardPlan.Site>>
                    buckets = new ArrayList<>(shardCount);
            for (int index = 0; index < shardCount; index++) {
                buckets.add(new ArrayList<>());
            }
            for (int index = 0; index < ordered.size(); index++) {
                buckets.get(index % shardCount).add(ordered.get(index));
            }
            for (int shardOrdinal = 0;
                    shardOrdinal < buckets.size();
                    shardOrdinal++) {
                List<HostJniLowSensitivityThrowShardPlan.Site> bucket =
                        List.copyOf(buckets.get(shardOrdinal));
                HostJniLowSensitivityThrowShardPlan.Site first =
                        bucket.get(0);
                shards.add(new HostJniLowSensitivityThrowShardPlan.Shard(
                        deriver.shardSymbol(
                                leafIdentity,
                                shardOrdinal,
                                bucket),
                        shardOrdinal,
                        leafIdentity,
                        first.exceptionClass(),
                        first.message(),
                        bucket));
            }
        }
        shards.sort(Comparator
                .comparing((HostJniLowSensitivityThrowShardPlan.Shard shard) ->
                        deriver.shardLayoutRank(shard.symbol()))
                .thenComparing(
                        HostJniLowSensitivityThrowShardPlan.Shard::symbol));
        return new HostJniLowSensitivityThrowShardPlan(
                declarationAnchor,
                sites,
                shards);
    }
}
