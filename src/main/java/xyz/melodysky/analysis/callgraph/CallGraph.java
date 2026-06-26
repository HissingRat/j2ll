package xyz.melodysky.analysis.callgraph;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

public record CallGraph(List<CallResolution> resolutions) {
    public CallGraph {
        resolutions = resolutions.stream()
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(CallResolution::callSite))
                .toList();
    }

    public List<CallSite> callSites() {
        return resolutions.stream().map(CallResolution::callSite).toList();
    }
}
