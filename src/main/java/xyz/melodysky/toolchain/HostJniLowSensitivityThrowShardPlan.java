package xyz.melodysky.toolchain;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Immutable physical-leaf plan frozen after all generated-C fragments. */
final class HostJniLowSensitivityThrowShardPlan {
    static final int MAX_DIRECT_CALL_SITES_PER_SHARD = 32;

    private final String declarationAnchor;
    private final List<Site> sites;
    private final List<Shard> shards;
    private final Map<String, String> symbolByPlaceholder;

    HostJniLowSensitivityThrowShardPlan(
            String declarationAnchor,
            List<Site> sites,
            List<Shard> shards) {
        this.declarationAnchor = Objects.requireNonNull(
                declarationAnchor,
                "declarationAnchor");
        this.sites = List.copyOf(Objects.requireNonNull(sites, "sites"));
        this.shards = List.copyOf(Objects.requireNonNull(shards, "shards"));
        this.symbolByPlaceholder = validateAndIndex();
    }

    String declarationAnchor() {
        return declarationAnchor;
    }

    List<Site> sites() {
        return sites;
    }

    List<Shard> shards() {
        return shards;
    }

    int siteCount() {
        return sites.size();
    }

    boolean isEmpty() {
        return sites.isEmpty();
    }

    String symbolForPlaceholder(String placeholder) {
        return symbolByPlaceholder.get(placeholder);
    }

    Set<String> placeholders() {
        return symbolByPlaceholder.keySet();
    }

    String declarations() {
        StringBuilder source = new StringBuilder();
        for (Shard shard : shards) {
            source.append("static void ")
                    .append(shard.symbol())
                    .append("(JNIEnv* env) __attribute__((noinline, cold));\n");
        }
        return source.toString();
    }

    private Map<String, String> validateAndIndex() {
        if (!declarationAnchor.matches(
                "j2ll_low_throw_declarations_[a-p]{32}")) {
            throw new IllegalArgumentException(
                    "invalid low-sensitivity declaration anchor");
        }
        LinkedHashMap<String, Site> siteByPlaceholder =
                new LinkedHashMap<>();
        HashSet<String> siteIdentities = new HashSet<>();
        for (Site site : sites) {
            Site previous = siteByPlaceholder.putIfAbsent(
                    site.placeholder(),
                    site);
            if (previous != null) {
                throw new IllegalArgumentException(
                        "duplicate low-sensitivity site placeholder");
            }
            if (!siteIdentities.add(site.identity())) {
                throw new IllegalArgumentException(
                        "duplicate low-sensitivity site identity");
            }
        }
        if (sites.isEmpty() != shards.isEmpty()) {
            throw new IllegalArgumentException(
                    "low-sensitivity site/shard emptiness mismatch");
        }

        LinkedHashMap<String, String> replacements = new LinkedHashMap<>();
        HashSet<String> symbols = new HashSet<>();
        HashSet<String> assignedPlaceholders = new HashSet<>();
        Map<String, ArrayList<Integer>> sizesByLeaf = new HashMap<>();
        Map<String, HashSet<Integer>> ordinalsByLeaf = new HashMap<>();
        Map<String, Integer> usesByLeaf = new HashMap<>();
        int assigned = 0;
        for (Shard shard : shards) {
            if (!symbols.add(shard.symbol())) {
                throw new IllegalArgumentException(
                        "low-sensitivity physical leaf symbol collision");
            }
            if (shard.sites().isEmpty()
                    || shard.sites().size()
                            > MAX_DIRECT_CALL_SITES_PER_SHARD) {
                throw new IllegalArgumentException(
                        "low-sensitivity physical leaf fanout is outside 1..32");
            }
            sizesByLeaf.computeIfAbsent(
                            shard.leafIdentity(),
                            ignored -> new ArrayList<>())
                    .add(shard.sites().size());
            if (!ordinalsByLeaf.computeIfAbsent(
                            shard.leafIdentity(),
                            ignored -> new HashSet<>())
                    .add(shard.ordinal())) {
                throw new IllegalArgumentException(
                        "duplicate low-sensitivity shard ordinal");
            }
            usesByLeaf.merge(
                    shard.leafIdentity(),
                    shard.sites().size(),
                    Integer::sum);
            for (Site site : shard.sites()) {
                if (!site.leafIdentity().equals(shard.leafIdentity())
                        || !site.exceptionClass().equals(
                                shard.exceptionClass())
                        || !site.message().equals(shard.message())) {
                    throw new IllegalArgumentException(
                            "low-sensitivity shard mixes logical leaves");
                }
                if (!site.equals(siteByPlaceholder.get(site.placeholder()))) {
                    throw new IllegalArgumentException(
                            "low-sensitivity shard references an unknown or changed site");
                }
                if (!assignedPlaceholders.add(site.placeholder())) {
                    throw new IllegalArgumentException(
                            "low-sensitivity site is assigned more than once");
                }
                replacements.put(site.placeholder(), shard.symbol());
                assigned++;
            }
        }
        if (assigned != sites.size()
                || assignedPlaceholders.size() != sites.size()) {
            throw new IllegalArgumentException(
                    "low-sensitivity site conservation failed");
        }
        for (Map.Entry<String, ArrayList<Integer>> entry
                : sizesByLeaf.entrySet()) {
            int uses = usesByLeaf.get(entry.getKey());
            int expectedShards = (uses - 1)
                    / MAX_DIRECT_CALL_SITES_PER_SHARD + 1;
            if (entry.getValue().size() != expectedShards) {
                throw new IllegalArgumentException(
                        "low-sensitivity shard count is not minimal");
            }
            int minimum = entry.getValue().stream()
                    .mapToInt(Integer::intValue)
                    .min()
                    .orElseThrow();
            int maximum = entry.getValue().stream()
                    .mapToInt(Integer::intValue)
                    .max()
                    .orElseThrow();
            if (maximum - minimum > 1) {
                throw new IllegalArgumentException(
                        "low-sensitivity shard distribution is not balanced");
            }
            Set<Integer> ordinals = ordinalsByLeaf.get(entry.getKey());
            for (int ordinal = 0; ordinal < expectedShards; ordinal++) {
                if (!ordinals.contains(ordinal)) {
                    throw new IllegalArgumentException(
                            "low-sensitivity shard ordinals are not contiguous");
                }
            }
        }
        for (String placeholder : replacements.keySet()) {
            if (symbols.contains(placeholder)) {
                throw new IllegalArgumentException(
                        "placeholder and physical-leaf symbol sets overlap");
            }
        }
        return Map.copyOf(replacements);
    }

    record Site(
            String placeholder,
            String identity,
            String scope,
            int leafLocalOrdinal,
            String leafIdentity,
            String exceptionClass,
            String message) {
        Site {
            Objects.requireNonNull(placeholder, "placeholder");
            Objects.requireNonNull(identity, "identity");
            Objects.requireNonNull(scope, "scope");
            Objects.requireNonNull(leafIdentity, "leafIdentity");
            Objects.requireNonNull(exceptionClass, "exceptionClass");
            Objects.requireNonNull(message, "message");
            if (!placeholder.matches(
                    "j2ll_low_throw_site_[a-p]{32}")) {
                throw new IllegalArgumentException(
                        "invalid low-sensitivity site placeholder");
            }
            if (scope.isBlank() || leafLocalOrdinal < 0) {
                throw new IllegalArgumentException(
                        "invalid low-sensitivity site identity");
            }
            if (!identity.equals(scope
                    + '\0'
                    + leafIdentity
                    + '\0'
                    + leafLocalOrdinal)) {
                throw new IllegalArgumentException(
                        "low-sensitivity site identity is not canonical");
            }
            if (!leafIdentity.equals(exceptionClass + '\0' + message)) {
                throw new IllegalArgumentException(
                        "low-sensitivity logical leaf identity is not canonical");
            }
            if (HostJniLowSensitivityThrowLeaf.find(
                    exceptionClass,
                    message) == null) {
                throw new IllegalArgumentException(
                        "low-sensitivity site is outside the closed allowlist");
            }
        }
    }

    record Shard(
            String symbol,
            int ordinal,
            String leafIdentity,
            String exceptionClass,
            String message,
            List<Site> sites) {
        Shard {
            Objects.requireNonNull(symbol, "symbol");
            Objects.requireNonNull(leafIdentity, "leafIdentity");
            Objects.requireNonNull(exceptionClass, "exceptionClass");
            Objects.requireNonNull(message, "message");
            sites = List.copyOf(Objects.requireNonNull(sites, "sites"));
            if (!symbol.matches("[a-p]{32}") || ordinal < 0) {
                throw new IllegalArgumentException(
                        "invalid low-sensitivity physical leaf identity");
            }
            if (!leafIdentity.equals(exceptionClass + '\0' + message)) {
                throw new IllegalArgumentException(
                        "low-sensitivity shard identity is not canonical");
            }
            if (HostJniLowSensitivityThrowLeaf.find(
                    exceptionClass,
                    message) == null) {
                throw new IllegalArgumentException(
                        "low-sensitivity shard is outside the closed allowlist");
            }
        }
    }
}
