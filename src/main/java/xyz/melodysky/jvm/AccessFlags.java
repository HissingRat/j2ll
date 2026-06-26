package xyz.melodysky.jvm;

import java.util.ArrayList;
import java.util.List;

public record AccessFlags(int value) {
    public static final int PUBLIC = 0x0001;
    public static final int PRIVATE = 0x0002;
    public static final int PROTECTED = 0x0004;
    public static final int STATIC = 0x0008;
    public static final int FINAL = 0x0010;
    public static final int SYNCHRONIZED = 0x0020;
    public static final int SUPER = 0x0020;
    public static final int VOLATILE = 0x0040;
    public static final int BRIDGE = 0x0040;
    public static final int TRANSIENT = 0x0080;
    public static final int VARARGS = 0x0080;
    public static final int NATIVE = 0x0100;
    public static final int INTERFACE = 0x0200;
    public static final int ABSTRACT = 0x0400;
    public static final int STRICT = 0x0800;
    public static final int SYNTHETIC = 0x1000;
    public static final int ANNOTATION = 0x2000;
    public static final int ENUM = 0x4000;
    public static final int MODULE = 0x8000;

    public boolean has(int mask) {
        return (value & mask) != 0;
    }

    public boolean isPublic() {
        return has(PUBLIC);
    }

    public boolean isPrivate() {
        return has(PRIVATE);
    }

    public boolean isProtected() {
        return has(PROTECTED);
    }

    public boolean isStatic() {
        return has(STATIC);
    }

    public boolean isSynchronized() {
        return has(SYNCHRONIZED);
    }

    public boolean isFinal() {
        return has(FINAL);
    }

    public boolean isVolatile() {
        return has(VOLATILE);
    }

    public boolean isNative() {
        return has(NATIVE);
    }

    public boolean isInterface() {
        return has(INTERFACE);
    }

    public boolean isAbstract() {
        return has(ABSTRACT);
    }

    public boolean isSynthetic() {
        return has(SYNTHETIC);
    }

    public boolean isAnnotation() {
        return has(ANNOTATION);
    }

    public List<String> names() {
        ArrayList<String> names = new ArrayList<>();
        addIf(names, PUBLIC, "public");
        addIf(names, PRIVATE, "private");
        addIf(names, PROTECTED, "protected");
        addIf(names, STATIC, "static");
        addIf(names, FINAL, "final");
        addIf(names, NATIVE, "native");
        addIf(names, INTERFACE, "interface");
        addIf(names, ABSTRACT, "abstract");
        addIf(names, SYNTHETIC, "synthetic");
        addIf(names, ANNOTATION, "annotation");
        addIf(names, ENUM, "enum");
        addIf(names, MODULE, "module");
        return List.copyOf(names);
    }

    private void addIf(List<String> names, int mask, String name) {
        if (has(mask)) {
            names.add(name);
        }
    }
}
