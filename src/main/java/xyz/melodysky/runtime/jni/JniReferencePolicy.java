package xyz.melodysky.runtime.jni;

import java.util.Objects;

public record JniReferencePolicy(
        JniReferenceKind kind,
        String scope,
        boolean releaseRequired,
        String releaseFunction) implements Comparable<JniReferencePolicy> {
    public JniReferencePolicy {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(releaseFunction, "releaseFunction");
    }

    public static JniReferencePolicy localFrame() {
        return new JniReferencePolicy(JniReferenceKind.LOCAL, "localFrame", true, "DeleteLocalRef");
    }

    public static JniReferencePolicy global() {
        return new JniReferencePolicy(JniReferenceKind.GLOBAL, "process", true, "DeleteGlobalRef");
    }

    public static JniReferencePolicy weakGlobal() {
        return new JniReferencePolicy(JniReferenceKind.WEAK_GLOBAL, "processWeak", true, "DeleteWeakGlobalRef");
    }

    @Override
    public int compareTo(JniReferencePolicy other) {
        int byKind = kind.compareTo(other.kind);
        if (byKind != 0) {
            return byKind;
        }
        return scope.compareTo(other.scope);
    }
}
