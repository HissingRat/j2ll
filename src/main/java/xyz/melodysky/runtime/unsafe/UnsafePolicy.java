package xyz.melodysky.runtime.unsafe;

import xyz.melodysky.runtime.RuntimeHelperKind;

public final class UnsafePolicy {
    public UnsafePlan plan(String owner, String name, String descriptor) {
        if (owner.equals("java/lang/invoke/VarHandle")) {
            return varHandlePlan(name, descriptor);
        }
        if (!owner.equals("sun/misc/Unsafe") && !owner.equals("jdk/internal/misc/Unsafe")) {
            return UnsafePlan.notUnsafe();
        }
        return switch (name) {
            case "objectFieldOffset" -> UnsafePlan.supported(
                    UnsafeOperationKind.OBJECT_FIELD_OFFSET,
                    RuntimeHelperKind.UNSAFE_OBJECT_FIELD_OFFSET,
                    false,
                    false,
                    "object field offset helper-backed");
            case "staticFieldOffset" -> UnsafePlan.supported(
                    UnsafeOperationKind.STATIC_FIELD_OFFSET,
                    RuntimeHelperKind.UNSAFE_STATIC_FIELD_OFFSET,
                    false,
                    false,
                    "static field offset helper-backed");
            case "arrayBaseOffset" -> UnsafePlan.supported(
                    UnsafeOperationKind.ARRAY_BASE_OFFSET,
                    RuntimeHelperKind.UNSAFE_ARRAY_BASE_OFFSET,
                    false,
                    false,
                    "array base offset helper-backed");
            case "arrayIndexScale" -> UnsafePlan.supported(
                    UnsafeOperationKind.ARRAY_INDEX_SCALE,
                    RuntimeHelperKind.UNSAFE_ARRAY_INDEX_SCALE,
                    false,
                    false,
                    "array index scale helper-backed");
            case "getInt" -> UnsafePlan.supported(
                    UnsafeOperationKind.GET,
                    RuntimeHelperKind.UNSAFE_GET_INT,
                    false,
                    false,
                    "unsafe getInt helper-backed");
            case "getLong", "getObject", "getBoolean" -> UnsafePlan.supported(
                    UnsafeOperationKind.GET,
                    RuntimeHelperKind.UNSAFE_GET,
                    false,
                    false,
                    "unsafe get helper-backed");
            case "putInt" -> UnsafePlan.supported(
                    UnsafeOperationKind.PUT,
                    RuntimeHelperKind.UNSAFE_PUT_INT,
                    false,
                    false,
                    "unsafe putInt helper-backed");
            case "putLong", "putObject", "putBoolean" -> UnsafePlan.supported(
                    UnsafeOperationKind.PUT,
                    RuntimeHelperKind.UNSAFE_PUT,
                    false,
                    false,
                    "unsafe put helper-backed");
            case "getIntVolatile", "getLongVolatile", "getObjectVolatile", "getBooleanVolatile" -> UnsafePlan.supported(
                    UnsafeOperationKind.GET_VOLATILE,
                    RuntimeHelperKind.UNSAFE_GET_VOLATILE,
                    true,
                    false,
                    "unsafe volatile get helper-backed");
            case "putIntVolatile", "putLongVolatile", "putObjectVolatile", "putBooleanVolatile" -> UnsafePlan.supported(
                    UnsafeOperationKind.PUT_VOLATILE,
                    RuntimeHelperKind.UNSAFE_PUT_VOLATILE,
                    true,
                    false,
                    "unsafe volatile put helper-backed");
            case "compareAndSwapInt", "compareAndSetInt" -> UnsafePlan.supported(
                    UnsafeOperationKind.COMPARE_AND_SWAP,
                    RuntimeHelperKind.UNSAFE_COMPARE_AND_SWAP_INT,
                    true,
                    true,
                    "unsafe CAS int helper-backed");
            case "compareAndSwapLong", "compareAndSwapObject",
                    "compareAndSetLong", "compareAndSetReference" -> UnsafePlan.supported(
                    UnsafeOperationKind.COMPARE_AND_SWAP,
                    RuntimeHelperKind.UNSAFE_COMPARE_AND_SWAP,
                    true,
                    true,
                    "unsafe CAS helper-backed");
            case "allocateInstance" -> UnsafePlan.supported(
                    UnsafeOperationKind.ALLOCATE_INSTANCE,
                    RuntimeHelperKind.UNSAFE_ALLOCATE_INSTANCE,
                    false,
                    false,
                    "unsafe allocateInstance guarded helper");
            default -> UnsafePlan.unsupported("unsupported Unsafe API " + owner + "#" + name + "!" + descriptor);
        };
    }

    private UnsafePlan varHandlePlan(String name, String descriptor) {
        return switch (name) {
            case "get" -> UnsafePlan.supported(
                    UnsafeOperationKind.VAR_HANDLE_GET,
                    descriptor.endsWith(")I") ? RuntimeHelperKind.VAR_HANDLE_GET_INT : RuntimeHelperKind.UNSAFE_GET,
                    false,
                    false,
                    "VarHandle get helper-backed");
            case "set" -> UnsafePlan.supported(
                    UnsafeOperationKind.VAR_HANDLE_SET,
                    descriptor.endsWith("I)V") ? RuntimeHelperKind.VAR_HANDLE_SET_INT : RuntimeHelperKind.UNSAFE_PUT,
                    false,
                    false,
                    "VarHandle set helper-backed");
            case "getVolatile" -> UnsafePlan.supported(
                    UnsafeOperationKind.VAR_HANDLE_GET_VOLATILE,
                    descriptor.endsWith(")I") ? RuntimeHelperKind.VAR_HANDLE_GET_VOLATILE_INT : RuntimeHelperKind.UNSAFE_GET_VOLATILE,
                    true,
                    false,
                    "VarHandle volatile get helper-backed");
            case "setVolatile" -> UnsafePlan.supported(
                    UnsafeOperationKind.VAR_HANDLE_SET_VOLATILE,
                    descriptor.endsWith("I)V") ? RuntimeHelperKind.VAR_HANDLE_SET_VOLATILE_INT : RuntimeHelperKind.UNSAFE_PUT_VOLATILE,
                    true,
                    false,
                    "VarHandle volatile set helper-backed");
            case "compareAndSet" -> UnsafePlan.supported(
                    UnsafeOperationKind.VAR_HANDLE_COMPARE_AND_SET,
                    descriptor.endsWith("II)Z") ? RuntimeHelperKind.VAR_HANDLE_COMPARE_AND_SET_INT : RuntimeHelperKind.UNSAFE_COMPARE_AND_SWAP,
                    true,
                    true,
                    "VarHandle compareAndSet helper-backed");
            default -> UnsafePlan.unsupported("unsupported VarHandle API java/lang/invoke/VarHandle#" + name);
        };
    }
}
