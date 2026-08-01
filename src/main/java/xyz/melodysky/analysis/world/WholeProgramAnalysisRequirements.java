package xyz.melodysky.analysis.world;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import xyz.melodysky.analysis.hierarchy.AnalysisWorld;
import xyz.melodysky.config.ResolvedConfig;

/** Collects execution-time confirmations for whole-program-dependent features. */
public final class WholeProgramAnalysisRequirements {
    public List<WholeProgramAnalysisRequirement> forConfig(ResolvedConfig config) {
        Objects.requireNonNull(config, "config");
        if (config.worldModel() == AnalysisWorld.CLOSED_WORLD) {
            return List.of();
        }

        ArrayList<WholeProgramAnalysisRequirement> requirements = new ArrayList<>();
        if (fieldInternalizationEnabled(config)) {
            requirements.add(requirement(WholeProgramAnalysisFeature.FIELD_INTERNALIZATION));
        }
        if (methodInternalizationEnabled(config)) {
            requirements.add(requirement(WholeProgramAnalysisFeature.METHOD_INTERNALIZATION));
        }
        return List.copyOf(requirements);
    }

    public List<WholeProgramAnalysisRequirement> unmet(
            ResolvedConfig config,
            WholeProgramAnalysisPolicy policy) {
        Objects.requireNonNull(policy, "policy");
        return forConfig(config).stream()
                .filter(requirement -> !policy.scopeFor(
                                requirement.feature(),
                                config.worldModel())
                        .permitsWholeProgramTransform())
                .toList();
    }

    private WholeProgramAnalysisRequirement requirement(WholeProgramAnalysisFeature feature) {
        return new WholeProgramAnalysisRequirement(
                feature,
                feature.diagnosticCode(),
                feature.currentJarOnlyPrompt(),
                feature.currentJarOnlyWarning());
    }

    private boolean fieldInternalizationEnabled(ResolvedConfig config) {
        return config.protection().enabled()
                && config.protection().ir().enabled()
                && config.protection().ir().fieldInternalization();
    }

    private boolean methodInternalizationEnabled(ResolvedConfig config) {
        return config.protection().enabled()
                && config.protection().ir().enabled()
                && config.protection().ir().methodInternalization();
    }
}
