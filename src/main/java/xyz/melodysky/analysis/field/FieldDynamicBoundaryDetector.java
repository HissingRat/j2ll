package xyz.melodysky.analysis.field;

import java.util.EnumSet;
import java.util.Set;

final class FieldDynamicBoundaryDetector {
    Set<FieldDynamicBoundaryKind> detectMemberReference(String owner, String name) {
        EnumSet<FieldDynamicBoundaryKind> boundaries = EnumSet.noneOf(FieldDynamicBoundaryKind.class);
        if (owner.equals("java/lang/Class") && isFieldReflectionMethod(name)
                || owner.equals("java/lang/reflect/Field")
                || owner.equals("java/lang/reflect/AccessibleObject")) {
            boundaries.add(FieldDynamicBoundaryKind.REFLECTION);
        }
        if (owner.equals("sun/misc/Unsafe") || owner.equals("jdk/internal/misc/Unsafe")) {
            boundaries.add(FieldDynamicBoundaryKind.UNSAFE);
        }
        if (owner.equals("java/lang/invoke/VarHandle")) {
            boundaries.add(FieldDynamicBoundaryKind.VAR_HANDLE);
        }
        if (owner.equals("java/lang/invoke/MethodHandles$Lookup")
                && isFieldLookupMethod(name)) {
            boundaries.add(FieldDynamicBoundaryKind.METHOD_HANDLE);
        }
        if (isNativeLoadingMethod(owner, name)) {
            boundaries.add(FieldDynamicBoundaryKind.NATIVE_JNI);
        }
        if (owner.startsWith("java/io/ObjectInput")
                || owner.startsWith("java/io/ObjectOutput")
                || owner.equals("java/io/ObjectStreamClass")) {
            boundaries.add(FieldDynamicBoundaryKind.SERIALIZATION);
        }
        if (owner.startsWith("java/lang/instrument/")
                || owner.startsWith("java/lang/management/Instrumentation")) {
            boundaries.add(FieldDynamicBoundaryKind.AGENT_INSTRUMENTATION);
        }
        if (isDynamicClassLoadingMethod(owner, name)) {
            boundaries.add(FieldDynamicBoundaryKind.DYNAMIC_CLASS_LOADING);
        }
        return Set.copyOf(boundaries);
    }

    private boolean isFieldReflectionMethod(String name) {
        return name.equals("getField")
                || name.equals("getFields")
                || name.equals("getDeclaredField")
                || name.equals("getDeclaredFields");
    }

    private boolean isFieldLookupMethod(String name) {
        return name.equals("findGetter")
                || name.equals("findSetter")
                || name.equals("findStaticGetter")
                || name.equals("findStaticSetter")
                || name.equals("unreflectGetter")
                || name.equals("unreflectSetter");
    }

    private boolean isNativeLoadingMethod(String owner, String name) {
        return owner.equals("java/lang/System") && (name.equals("load") || name.equals("loadLibrary"))
                || owner.equals("java/lang/Runtime") && (name.equals("load") || name.equals("loadLibrary"));
    }

    private boolean isDynamicClassLoadingMethod(String owner, String name) {
        return owner.equals("java/lang/Class") && name.equals("forName")
                || owner.equals("java/lang/ClassLoader")
                        && (name.equals("loadClass") || name.equals("defineClass"))
                || owner.equals("java/lang/invoke/MethodHandles$Lookup")
                        && (name.equals("defineClass") || name.equals("defineHiddenClass"));
    }
}
