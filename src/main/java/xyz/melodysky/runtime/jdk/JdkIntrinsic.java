package xyz.melodysky.runtime.jdk;

import java.util.Objects;
import java.util.Optional;
import xyz.melodysky.diagnostic.DiagnosticCode;
import xyz.melodysky.runtime.RuntimeHelperKind;

public record JdkIntrinsic(
        JdkMethodId method,
        JdkMethodPolicy policy,
        Optional<RuntimeHelperKind> helperKind,
        Optional<DiagnosticCode> unsupportedDiagnosticCode,
        String reason) {
    public JdkIntrinsic {
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(helperKind, "helperKind");
        Objects.requireNonNull(unsupportedDiagnosticCode, "unsupportedDiagnosticCode");
        Objects.requireNonNull(reason, "reason");
        if (policy == JdkMethodPolicy.JVM_HELPER_UNSUPPORTED && unsupportedDiagnosticCode.isEmpty()) {
            throw new IllegalArgumentException("unsupported JDK intrinsic requires a diagnostic code");
        }
        if (policy != JdkMethodPolicy.JVM_HELPER_UNSUPPORTED && unsupportedDiagnosticCode.isPresent()) {
            throw new IllegalArgumentException("supported JDK intrinsic cannot carry an unsupported diagnostic code");
        }
    }

    public static JdkIntrinsic runtimeHelper(
            String owner,
            String name,
            String descriptor,
            RuntimeHelperKind helperKind) {
        return new JdkIntrinsic(
                new JdkMethodId(owner, name, descriptor),
                JdkMethodPolicy.RUNTIME_HELPER,
                Optional.of(helperKind),
                Optional.empty(),
                "jdk runtime helper");
    }

    public static JdkIntrinsic direct(String owner, String name, String descriptor) {
        return new JdkIntrinsic(
                new JdkMethodId(owner, name, descriptor),
                JdkMethodPolicy.DIRECT_NATIVE_LOWERING,
                Optional.empty(),
                Optional.empty(),
                "direct native lowering");
    }

    public static JdkIntrinsic unsupported(String owner, String name, String descriptor, String reason) {
        return unsupported(
                owner,
                name,
                descriptor,
                DiagnosticCode.JVM_HELPER_UNSUPPORTED,
                reason);
    }

    public static JdkIntrinsic unsupported(
            String owner,
            String name,
            String descriptor,
            DiagnosticCode diagnosticCode,
            String reason) {
        return new JdkIntrinsic(
                new JdkMethodId(owner, name, descriptor),
                JdkMethodPolicy.JVM_HELPER_UNSUPPORTED,
                Optional.empty(),
                Optional.of(Objects.requireNonNull(diagnosticCode, "diagnosticCode")),
                reason);
    }

    public static JdkIntrinsic bridge(String owner, String name, String descriptor, String reason) {
        return new JdkIntrinsic(
                new JdkMethodId(owner, name, descriptor),
                JdkMethodPolicy.JVM_HELPER_BRIDGE,
                Optional.empty(),
                Optional.empty(),
                reason);
    }
}
