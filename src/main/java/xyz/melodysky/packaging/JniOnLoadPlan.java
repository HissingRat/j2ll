package xyz.melodysky.packaging;

import java.util.List;
import java.util.Objects;
import xyz.melodysky.runtime.jni.JniPendingExceptionPolicy;

public record JniOnLoadPlan(
        String onLoadSymbol,
        String aggregateRegisterSymbol,
        String minimumJniVersion,
        JniPendingExceptionPolicy pendingExceptionPolicy,
        List<BootstrapWrapperPlan> bootstrapWrappers) {
    public JniOnLoadPlan {
        Objects.requireNonNull(onLoadSymbol, "onLoadSymbol");
        Objects.requireNonNull(aggregateRegisterSymbol, "aggregateRegisterSymbol");
        Objects.requireNonNull(minimumJniVersion, "minimumJniVersion");
        Objects.requireNonNull(pendingExceptionPolicy, "pendingExceptionPolicy");
        bootstrapWrappers = bootstrapWrappers.stream().filter(Objects::nonNull).sorted().toList();
    }
}
