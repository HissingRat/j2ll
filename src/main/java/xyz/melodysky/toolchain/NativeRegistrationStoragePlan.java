package xyz.melodysky.toolchain;

/**
 * Bounds owner-local registration scratch so ordinary owners avoid heap
 * allocation without permitting unbounded native stack growth.
 */
record NativeRegistrationStoragePlan(
        Kind kind,
        int bindingCount,
        int textBytes) {
    static final int MAX_STACK_BINDINGS = 64;
    static final int MAX_STACK_TEXT_BYTES = 16 * 1024;

    NativeRegistrationStoragePlan {
        if (kind == null) {
            throw new IllegalArgumentException(
                    "registration storage kind must not be null");
        }
        if (bindingCount <= 0) {
            throw new IllegalArgumentException(
                    "registration storage requires at least one binding");
        }
        if (textBytes <= 0) {
            throw new IllegalArgumentException(
                    "registration text scratch must not be empty");
        }
    }

    static NativeRegistrationStoragePlan plan(
            int bindingCount,
            int textBytes) {
        Kind kind = bindingCount <= MAX_STACK_BINDINGS
                        && textBytes <= MAX_STACK_TEXT_BYTES
                ? Kind.BOUNDED_STACK
                : Kind.HEAP;
        return new NativeRegistrationStoragePlan(
                kind,
                bindingCount,
                textBytes);
    }

    boolean usesStack() {
        return kind == Kind.BOUNDED_STACK;
    }

    enum Kind {
        BOUNDED_STACK,
        HEAP
    }
}
