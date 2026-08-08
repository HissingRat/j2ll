package xyz.melodysky.analysis.field;

import java.util.Optional;

/** Closed classifier for indirect calls that can observe JVM field metadata. */
final class IndirectFieldObserverClassifier {
    Optional<FieldDynamicBoundaryKind> classify(String owner, String name) {
        if (isBytecodeDefinition(owner, name)) {
            return Optional.of(FieldDynamicBoundaryKind.DYNAMIC_CLASS_LOADING);
        }
        if (owner.equals("java/lang/Class") && isClassFieldLookup(name)
                || owner.equals("java/lang/reflect/Field")
                || owner.equals("java/lang/reflect/Method") && name.equals("invoke")) {
            return Optional.of(FieldDynamicBoundaryKind.REFLECTION);
        }
        if (owner.equals("java/lang/invoke/MethodHandles$Lookup")
                && isLookupFieldApi(name)) {
            return Optional.of(name.contains("VarHandle")
                    ? FieldDynamicBoundaryKind.VAR_HANDLE
                    : FieldDynamicBoundaryKind.METHOD_HANDLE);
        }
        if (owner.equals("java/lang/invoke/VarHandle")) {
            return Optional.of(FieldDynamicBoundaryKind.VAR_HANDLE);
        }
        if (owner.equals("sun/misc/Unsafe")
                || owner.equals("jdk/internal/misc/Unsafe")) {
            return Optional.of(FieldDynamicBoundaryKind.UNSAFE);
        }
        if (owner.equals("java/lang/System")
                        && (name.equals("load") || name.equals("loadLibrary"))
                || owner.equals("java/lang/Runtime")
                        && (name.equals("load") || name.equals("loadLibrary"))) {
            return Optional.of(FieldDynamicBoundaryKind.NATIVE_JNI);
        }
        if (owner.startsWith("java/lang/instrument/")
                || owner.startsWith("java/lang/management/Instrumentation")) {
            return Optional.of(FieldDynamicBoundaryKind.AGENT_INSTRUMENTATION);
        }
        return Optional.empty();
    }

    boolean isBytecodeDefinition(String owner, String name) {
        // An unparsed owner may itself be a ClassLoader subtype. The method
        // name is intentionally fail-closed because a missed definition can
        // introduce caller/nestmate bytecode after the analysis world closes.
        if (name.equals("defineClass")) {
            return true;
        }
        if (owner.equals("java/lang/invoke/MethodHandles$Lookup")) {
            return name.equals("defineHiddenClass")
                    || name.equals("defineHiddenClassWithClassData");
        }
        if ((owner.equals("sun/misc/Unsafe")
                        || owner.equals("jdk/internal/misc/Unsafe"))
                && name.startsWith("define")) {
            return true;
        }
        return false;
    }

    private boolean isClassFieldLookup(String name) {
        return name.equals("getField")
                || name.equals("getFields")
                || name.equals("getDeclaredField")
                || name.equals("getDeclaredFields");
    }

    private boolean isLookupFieldApi(String name) {
        return name.equals("findGetter")
                || name.equals("findSetter")
                || name.equals("findStaticGetter")
                || name.equals("findStaticSetter")
                || name.equals("findVarHandle")
                || name.equals("findStaticVarHandle")
                || name.equals("unreflectGetter")
                || name.equals("unreflectSetter")
                || name.equals("unreflectVarHandle");
    }
}
