package xyz.melodysky.ir.pass.protection;

import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import xyz.melodysky.ir.model.IrCallIndirectionMode;
import xyz.melodysky.ir.model.IrCallIndirectionRef;

/**
 * Immutable typed mapping from protected SSA call sites to table/dispatcher
 * entries.
 */
public final class IrCallIndirectionPlan {
    private final String planId;
    private final IrCallIndirectionMode mode;
    private final List<IrCallIndirectionGroup> groups;
    private final List<IrCallIndirectionSite> sites;
    private final Map<String, IrCallIndirectionGroup> groupsById;
    private final Map<String, IrCallIndirectionTarget> targetsByEntryId;
    private final Map<IrCallSiteId, IrCallIndirectionSite> sitesById;

    public IrCallIndirectionPlan(
            String planId,
            IrCallIndirectionMode mode,
            List<IrCallIndirectionGroup> groups,
            List<IrCallIndirectionSite> sites) {
        Objects.requireNonNull(planId, "planId");
        if (planId.isBlank()) {
            throw new IllegalArgumentException("planId must not be blank");
        }
        this.planId = planId;
        this.mode = Objects.requireNonNull(mode, "mode");
        this.groups = groups.stream()
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(IrCallIndirectionGroup::groupId))
                .toList();
        this.sites = sites.stream()
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(IrCallIndirectionSite::siteId))
                .toList();

        HashMap<String, IrCallIndirectionGroup> groupIndex = new HashMap<>();
        HashMap<String, IrCallIndirectionTarget> targetIndex = new HashMap<>();
        for (IrCallIndirectionGroup group : this.groups) {
            if (groupIndex.put(group.groupId(), group) != null) {
                throw new IllegalArgumentException("duplicate call-indirection group id " + group.groupId());
            }
            for (IrCallIndirectionTarget target : group.targets()) {
                if (targetIndex.put(target.entryId(), target) != null) {
                    throw new IllegalArgumentException(
                            "duplicate call-indirection entry id " + target.entryId());
                }
            }
        }
        HashMap<IrCallSiteId, IrCallIndirectionSite> siteIndex = new HashMap<>();
        for (IrCallIndirectionSite site : this.sites) {
            if (siteIndex.put(site.siteId(), site) != null) {
                throw new IllegalArgumentException(
                        "duplicate call-indirection site " + site.siteId().stableKey());
            }
            validateReference(site.reference(), groupIndex, targetIndex);
        }
        if (!new HashSet<>(groupIndex.keySet()).containsAll(
                this.sites.stream().map(site -> site.reference().groupId()).toList())) {
            throw new IllegalArgumentException("call-indirection site references an unknown group");
        }
        groupsById = Map.copyOf(groupIndex);
        targetsByEntryId = Map.copyOf(targetIndex);
        sitesById = Map.copyOf(siteIndex);
    }

    public String planId() {
        return planId;
    }

    public IrCallIndirectionMode mode() {
        return mode;
    }

    public List<IrCallIndirectionGroup> groups() {
        return groups;
    }

    public List<IrCallIndirectionSite> sites() {
        return sites;
    }

    public Optional<IrCallIndirectionSite> site(IrCallSiteId siteId) {
        return Optional.ofNullable(sitesById.get(siteId));
    }

    public Optional<IrCallIndirectionGroup> group(String groupId) {
        return Optional.ofNullable(groupsById.get(groupId));
    }

    public Optional<IrCallIndirectionTarget> target(String entryId) {
        return Optional.ofNullable(targetsByEntryId.get(entryId));
    }

    private void validateReference(
            IrCallIndirectionRef reference,
            Map<String, IrCallIndirectionGroup> groupIndex,
            Map<String, IrCallIndirectionTarget> targetIndex) {
        if (!reference.planId().equals(planId)) {
            throw new IllegalArgumentException("call-indirection reference points at another plan");
        }
        if (reference.mode() != mode) {
            throw new IllegalArgumentException("call-indirection reference mode does not match plan");
        }
        IrCallIndirectionGroup group = groupIndex.get(reference.groupId());
        if (group == null || !group.signature().equals(reference.signature())) {
            throw new IllegalArgumentException("call-indirection reference group/signature mismatch");
        }
        IrCallIndirectionTarget target = targetIndex.get(reference.entryId());
        if (target == null || group.targets().stream().noneMatch(candidate -> candidate.equals(target))) {
            throw new IllegalArgumentException("call-indirection reference entry is outside its signature group");
        }
    }
}
