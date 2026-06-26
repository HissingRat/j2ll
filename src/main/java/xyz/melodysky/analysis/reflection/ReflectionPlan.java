package xyz.melodysky.analysis.reflection;

import java.util.List;
import java.util.Objects;

public record ReflectionPlan(
        List<ReflectionClassTarget> resolvedClasses,
        List<ReflectionMethodTarget> resolvedMethods,
        List<ReflectionFieldTarget> resolvedFields,
        List<ReflectionFallbackSite> fallbackSites) {
    public ReflectionPlan {
        resolvedClasses = resolvedClasses.stream().filter(Objects::nonNull).sorted().distinct().toList();
        resolvedMethods = resolvedMethods.stream().filter(Objects::nonNull).sorted().distinct().toList();
        resolvedFields = resolvedFields.stream().filter(Objects::nonNull).sorted().distinct().toList();
        fallbackSites = fallbackSites.stream().filter(Objects::nonNull).sorted().distinct().toList();
    }

    public List<ReflectionMethodTarget> reachableMethods() {
        return resolvedMethods.stream()
                .filter(target -> target.kind() == ReflectionMethodKind.REFLECTIVE_INVOKE
                        || target.kind() == ReflectionMethodKind.REFLECTIVE_NEW_INSTANCE)
                .toList();
    }

    public boolean hasFallbacks() {
        return !fallbackSites.isEmpty();
    }
}
