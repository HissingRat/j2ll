package xyz.melodysky.report;

import java.util.List;
import java.util.Objects;
import xyz.melodysky.packaging.MethodRewriteStrategy;
import xyz.melodysky.pipeline.LoweringStatus;

public record LoweringReportMethod(
        String owner,
        String name,
        String descriptor,
        String methodId,
        LoweringStatus status,
        MethodRewriteStrategy rewriteStrategy,
        List<String> accessFlags,
        List<String> compilerFlags,
        String nativeSymbol,
        String registrationOwner,
        String nativeImplementationPath,
        List<HelperBackedSiteReport> helperBackedSites,
        List<FallbackSiteReport> fallbackSites,
        String reasonCode,
        String reason) {
    public LoweringReportMethod {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(descriptor, "descriptor");
        Objects.requireNonNull(methodId, "methodId");
        Objects.requireNonNull(status, "status");
        accessFlags = List.copyOf(Objects.requireNonNull(accessFlags, "accessFlags"));
        compilerFlags = List.copyOf(Objects.requireNonNull(compilerFlags, "compilerFlags"));
        helperBackedSites = List.copyOf(Objects.requireNonNull(helperBackedSites, "helperBackedSites"));
        fallbackSites = List.copyOf(Objects.requireNonNull(fallbackSites, "fallbackSites"));
    }
}
