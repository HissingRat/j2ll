package xyz.melodysky.ir.pass.protection;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class IrDirectCallFacts {
    private final List<IrDirectCallFact> facts;
    private final Map<IrCallSiteId, IrDirectCallFact> bySite;

    public IrDirectCallFacts(List<IrDirectCallFact> facts) {
        this.facts = facts.stream()
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(IrDirectCallFact::siteId))
                .toList();
        LinkedHashMap<IrCallSiteId, IrDirectCallFact> indexed = new LinkedHashMap<>();
        for (IrDirectCallFact fact : this.facts) {
            if (indexed.put(fact.siteId(), fact) != null) {
                throw new IllegalArgumentException("duplicate direct-call fact for " + fact.siteId().stableKey());
            }
        }
        bySite = Map.copyOf(indexed);
    }

    public static IrDirectCallFacts empty() {
        return new IrDirectCallFacts(List.of());
    }

    public List<IrDirectCallFact> facts() {
        return facts;
    }

    public Optional<IrDirectCallFact> factFor(IrCallSiteId siteId) {
        return Optional.ofNullable(bySite.get(siteId));
    }
}
