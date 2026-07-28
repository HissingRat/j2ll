package xyz.melodysky.protection.audit;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/**
 * Executes a caller-supplied fake JavaVM/JNIEnv fixture through JNI_OnLoad and
 * captures only the RegisterNatives calls made during that invocation.
 *
 * <p>This is dynamic evidence. It deliberately does not parse generated C or a
 * final binary to guess registration mappings.
 */
public final class FakeJniRegistrationProbe {
    public static final String CAPTURED_VIA_JNI_ONLOAD =
            "REGISTRATION_BINDINGS_CAPTURED_VIA_JNI_ONLOAD";
    public static final String NO_BINDINGS_OBSERVED =
            "REGISTRATION_BINDINGS_NOT_OBSERVED";
    public static final String JNI_ONLOAD_EXPORT_MISSING =
            "JNI_ONLOAD_EXPORT_MISSING";
    public static final String STABLE_DIRECT_EXPORT_PRESENT =
            "STABLE_DIRECT_REGISTRATION_EXPORT_PRESENT";
    public static final String JNI_ONLOAD_FAILED =
            "JNI_ONLOAD_FAILED";
    public static final String JNI_ONLOAD_UNEXPECTED_VERSION =
            "JNI_ONLOAD_UNEXPECTED_VERSION";
    private static final String JNI_ONLOAD = "JNI_OnLoad";
    private static final int JNI_VERSION_1_8 = 0x00010008;

    public RegistrationCaptureMetric observe(
            List<String> dynamicExports,
            FakeJniOnLoadInvocation invocation) {
        Objects.requireNonNull(dynamicExports, "dynamicExports");
        Objects.requireNonNull(invocation, "invocation");
        List<String> exports = dynamicExports.stream()
                .filter(Objects::nonNull)
                .sorted()
                .distinct()
                .toList();
        ArrayList<RegistrationBindingHash> captured = new ArrayList<>();
        HashSet<String> owners = new HashSet<>();
        boolean[] active = {true};
        FakeJniRegistrationObserver observer = (owner, bindings) -> {
            if (!active[0]) {
                throw new IllegalStateException(
                        "fake JNI registration observer is outside JNI_OnLoad");
            }
            Objects.requireNonNull(owner, "ownerInternalName");
            Objects.requireNonNull(bindings, "bindings");
            if (owner.isBlank()) {
                throw new IllegalArgumentException(
                        "registration owner must not be blank");
            }
            String ownerHash =
                    HashOnlyEvidence.sha256("registration-owner", owner);
            owners.add(ownerHash);
            for (ObservedNativeBinding binding : bindings) {
                Objects.requireNonNull(binding, "binding");
                captured.add(new RegistrationBindingHash(
                        ownerHash,
                        HashOnlyEvidence.sha256(
                                "registration-method-name",
                                binding.methodName()),
                        HashOnlyEvidence.sha256(
                                "registration-descriptor",
                                binding.descriptor()),
                        HashOnlyEvidence.sha256(
                                "registration-function-identity",
                                binding.functionIdentity())));
            }
        };

        int result;
        try {
            result = invocation.invoke(observer);
        } finally {
            active[0] = false;
        }
        List<RegistrationBindingHash> bindings = captured.stream()
                .sorted()
                .distinct()
                .toList();
        boolean onLoadExport = exports.contains(JNI_ONLOAD);
        boolean stableDirectExport = exports.stream()
                .anyMatch(export -> export.equals("j2ll_register")
                        || export.startsWith("j2ll_register_"));
        boolean dynamicOnly = onLoadExport
                && !stableDirectExport
                && !bindings.isEmpty();
        boolean passed = dynamicOnly && result == JNI_VERSION_1_8;
        String reasonCode;
        if (!onLoadExport) {
            reasonCode = JNI_ONLOAD_EXPORT_MISSING;
        } else if (stableDirectExport) {
            reasonCode = STABLE_DIRECT_EXPORT_PRESENT;
        } else if (result <= 0) {
            reasonCode = JNI_ONLOAD_FAILED;
        } else if (result != JNI_VERSION_1_8) {
            reasonCode = JNI_ONLOAD_UNEXPECTED_VERSION;
        } else if (bindings.isEmpty()) {
            reasonCode = NO_BINDINGS_OBSERVED;
        } else {
            reasonCode = CAPTURED_VIA_JNI_ONLOAD;
        }
        return new RegistrationCaptureMetric(
                RegistrationCaptureMetric.CHANNEL,
                result,
                onLoadExport,
                stableDirectExport,
                dynamicOnly,
                owners.size(),
                bindings.size(),
                bindings,
                exports,
                passed,
                reasonCode);
    }
}
