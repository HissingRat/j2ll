package xyz.melodysky.analysis.callgraph;

import java.util.Objects;
import java.util.Optional;
import xyz.melodysky.jvm.MethodSignature;

public record CallTarget(
        Optional<String> owner,
        Optional<MethodSignature> signature,
        boolean unknownExternal,
        String reason) implements Comparable<CallTarget> {
    public CallTarget {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(signature, "signature");
        if (!unknownExternal && (owner.isEmpty() || signature.isEmpty())) {
            throw new IllegalArgumentException("known call target requires owner and signature");
        }
    }

    public static CallTarget known(String owner, MethodSignature signature) {
        return new CallTarget(Optional.of(owner), Optional.of(signature), false, null);
    }

    public static CallTarget unknownExternal(String reason) {
        return new CallTarget(Optional.empty(), Optional.empty(), true, reason);
    }

    public String displayName() {
        if (unknownExternal) {
            return "<unknown-external:" + reason + ">";
        }
        return owner.orElseThrow() + "#" + signature.orElseThrow();
    }

    @Override
    public int compareTo(CallTarget other) {
        return displayName().compareTo(other.displayName());
    }
}
