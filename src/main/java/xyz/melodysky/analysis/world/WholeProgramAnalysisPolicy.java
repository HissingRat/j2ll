package xyz.melodysky.analysis.world;

import java.util.Collection;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import xyz.melodysky.analysis.hierarchy.AnalysisWorld;

/**
 * Per-invocation authorization for analyses that normally require a declared
 * closed world. This policy never changes the configured world model.
 */
public final class WholeProgramAnalysisPolicy {
    private final Set<WholeProgramAnalysisFeature> currentJarOnlyApprovals;

    private WholeProgramAnalysisPolicy(Collection<WholeProgramAnalysisFeature> approvals) {
        EnumSet<WholeProgramAnalysisFeature> copy =
                EnumSet.noneOf(WholeProgramAnalysisFeature.class);
        copy.addAll(Objects.requireNonNull(approvals, "approvals"));
        currentJarOnlyApprovals = Set.copyOf(copy);
    }

    public static WholeProgramAnalysisPolicy strict() {
        return new WholeProgramAnalysisPolicy(Set.of());
    }

    public static WholeProgramAnalysisPolicy currentJarOnly(
            Collection<WholeProgramAnalysisFeature> approvals) {
        return new WholeProgramAnalysisPolicy(approvals);
    }

    public WholeProgramAnalysisScope scopeFor(
            WholeProgramAnalysisFeature feature,
            AnalysisWorld configuredWorld) {
        Objects.requireNonNull(feature, "feature");
        Objects.requireNonNull(configuredWorld, "configuredWorld");
        if (configuredWorld == AnalysisWorld.CLOSED_WORLD) {
            return WholeProgramAnalysisScope.DECLARED_CLOSED_WORLD;
        }
        return feature.currentJarOnlySupported()
                        && currentJarOnlyApprovals.contains(feature)
                ? WholeProgramAnalysisScope.CURRENT_JAR_ONLY_USER_APPROVED
                : WholeProgramAnalysisScope.UNAVAILABLE;
    }
}
