package xyz.melodysky.protection.audit;

import java.util.List;
import java.util.Objects;

/** Machine-readable result of a dynamic fake-JNI JNI_OnLoad observation. */
public record RegistrationCaptureMetric(
        String observationChannel,
        int jniOnLoadResult,
        boolean jniOnLoadExportPresent,
        boolean stableDirectRegistrationExportPresent,
        boolean mappingAvailableOnlyAfterJniOnLoadObservation,
        int capturedOwnerCount,
        int capturedBindingCount,
        List<RegistrationBindingHash> bindings,
        List<String> dynamicExports,
        boolean passed,
        String reasonCode) {
    public static final String CHANNEL = "dynamicFakeJniOnLoad";

    public RegistrationCaptureMetric {
        Objects.requireNonNull(observationChannel, "observationChannel");
        Objects.requireNonNull(reasonCode, "reasonCode");
        if (!CHANNEL.equals(observationChannel)
                || capturedOwnerCount < 0
                || capturedBindingCount < 0
                || reasonCode.isBlank()) {
            throw new IllegalArgumentException(
                    "registration capture metric is invalid");
        }
        bindings = Objects.requireNonNull(bindings, "bindings")
                .stream()
                .sorted()
                .distinct()
                .toList();
        dynamicExports = Objects.requireNonNull(dynamicExports, "dynamicExports")
                .stream()
                .filter(Objects::nonNull)
                .sorted()
                .distinct()
                .toList();
        if (bindings.size() != capturedBindingCount) {
            throw new IllegalArgumentException(
                    "capturedBindingCount must equal unique binding evidence");
        }
    }
}
