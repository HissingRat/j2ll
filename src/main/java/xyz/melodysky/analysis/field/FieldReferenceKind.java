package xyz.melodysky.analysis.field;

public enum FieldReferenceKind {
    BYTECODE_INSTANCE_READ(false, false),
    BYTECODE_INSTANCE_WRITE(false, false),
    BYTECODE_STATIC_READ(false, true),
    BYTECODE_STATIC_WRITE(false, true),
    METHOD_HANDLE_INSTANCE_READ(true, false),
    METHOD_HANDLE_INSTANCE_WRITE(true, false),
    METHOD_HANDLE_STATIC_READ(true, true),
    METHOD_HANDLE_STATIC_WRITE(true, true);

    private final boolean methodHandle;
    private final boolean staticAccess;

    FieldReferenceKind(boolean methodHandle, boolean staticAccess) {
        this.methodHandle = methodHandle;
        this.staticAccess = staticAccess;
    }

    public boolean methodHandle() {
        return methodHandle;
    }

    public boolean staticAccess() {
        return staticAccess;
    }
}
