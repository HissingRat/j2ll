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
        NativeMethodRetentionMode retentionMode,
        boolean javaMethodPresent,
        boolean registrationPresent,
        List<String> accessFlags,
        List<String> compilerFlags,
        String nativeSymbol,
        String registrationOwner,
        String nativeImplementationPath,
        String coalescedInto,
        List<HelperBackedSiteReport> helperBackedSites,
        String reasonCode,
        String reason) {
    public LoweringReportMethod {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(descriptor, "descriptor");
        Objects.requireNonNull(methodId, "methodId");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(retentionMode, "retentionMode");
        accessFlags = List.copyOf(Objects.requireNonNull(accessFlags, "accessFlags"));
        compilerFlags = List.copyOf(Objects.requireNonNull(compilerFlags, "compilerFlags"));
        helperBackedSites = List.copyOf(Objects.requireNonNull(helperBackedSites, "helperBackedSites"));
        if (retentionMode == NativeMethodRetentionMode.COALESCED_NATIVE_ONLY
                && (coalescedInto == null || coalescedInto.isBlank())) {
            throw new IllegalArgumentException(
                    "coalesced native-only report method requires its caller identity");
        }
    }

    public LoweringReportMethod(
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
            String reasonCode,
            String reason) {
        this(
                owner,
                name,
                descriptor,
                methodId,
                status,
                rewriteStrategy,
                rewriteStrategy
                                == MethodRewriteStrategy
                                        .INTERNAL_NATIVE_ONLY
                        ? NativeMethodRetentionMode
                                .INTERNAL_NATIVE_ONLY
                        : status == LoweringStatus.NATIVE_LOWERED
                                ? NativeMethodRetentionMode
                                        .REGISTERED_NATIVE
                                : NativeMethodRetentionMode
                                        .JAVA_BYTECODE,
                rewriteStrategy
                        != MethodRewriteStrategy.INTERNAL_NATIVE_ONLY,
                nativeSymbol != null,
                accessFlags,
                compilerFlags,
                nativeSymbol,
                registrationOwner,
                nativeImplementationPath,
                null,
                helperBackedSites,
                reasonCode,
                reason);
    }
}
