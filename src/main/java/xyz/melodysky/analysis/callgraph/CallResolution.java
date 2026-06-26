package xyz.melodysky.analysis.callgraph;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

public record CallResolution(
        CallSite callSite,
        List<CallTarget> targets,
        boolean conservative,
        String reason) {
    public CallResolution {
        Objects.requireNonNull(callSite, "callSite");
        targets = targets.stream()
                .filter(Objects::nonNull)
                .distinct()
                .sorted(Comparator.naturalOrder())
                .toList();
    }

    public boolean hasUnknownTarget() {
        return targets.stream().anyMatch(CallTarget::unknownExternal);
    }
}
