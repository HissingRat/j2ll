package xyz.melodysky.runtime.unsafe;

import java.util.Objects;
import java.util.Optional;
import xyz.melodysky.runtime.RuntimeHelperKind;

public record UnsafePlan(
        boolean unsafeOrVarHandleCall,
        boolean supported,
        UnsafeOperationKind kind,
        Optional<RuntimeHelperKind> helperKind,
        boolean volatileAccess,
        boolean compareAndSwap,
        String reason) {
    public UnsafePlan {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(helperKind, "helperKind");
        Objects.requireNonNull(reason, "reason");
    }

    public static UnsafePlan notUnsafe() {
        return new UnsafePlan(false, false, UnsafeOperationKind.UNSUPPORTED, Optional.empty(), false, false, "not unsafe");
    }

    public static UnsafePlan supported(
            UnsafeOperationKind kind,
            RuntimeHelperKind helperKind,
            boolean volatileAccess,
            boolean compareAndSwap,
            String reason) {
        return new UnsafePlan(true, true, kind, Optional.of(helperKind), volatileAccess, compareAndSwap, reason);
    }

    public static UnsafePlan unsupported(String reason) {
        return new UnsafePlan(true, false, UnsafeOperationKind.UNSUPPORTED, Optional.empty(), false, false, reason);
    }
}
