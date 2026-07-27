package xyz.melodysky.runtime.jdk;

import java.util.Objects;
import java.util.Optional;
import xyz.melodysky.runtime.RuntimeHelperKind;

public record JdkIntrinsic(
        JdkMethodId method,
        JdkMethodPolicy policy,
        Optional<RuntimeHelperKind> helperKind,
        String reason) {
    public JdkIntrinsic {
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(helperKind, "helperKind");
        Objects.requireNonNull(reason, "reason");
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
                "jdk runtime helper");
    }

    public static JdkIntrinsic direct(String owner, String name, String descriptor) {
        return new JdkIntrinsic(
                new JdkMethodId(owner, name, descriptor),
                JdkMethodPolicy.DIRECT_NATIVE_LOWERING,
                Optional.empty(),
                "direct native lowering");
    }

    public static JdkIntrinsic unsupported(String owner, String name, String descriptor, String reason) {
        return new JdkIntrinsic(
                new JdkMethodId(owner, name, descriptor),
                JdkMethodPolicy.JVM_HELPER_UNSUPPORTED,
                Optional.empty(),
                reason);
    }

    public static JdkIntrinsic bridge(String owner, String name, String descriptor, String reason) {
        return new JdkIntrinsic(
                new JdkMethodId(owner, name, descriptor),
                JdkMethodPolicy.JVM_HELPER_BRIDGE,
                Optional.empty(),
                reason);
    }
}
