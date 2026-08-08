package xyz.melodysky.analysis.field;

import java.util.Optional;
import java.util.Set;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;

/** Fail-closed gate for code that a JVM bootstrap may execute with caller lookup privileges. */
final class FieldBootstrapObserverGate {
    private static final Set<Target> SAFE_JDK_BOOTSTRAPS = Set.of(
            target(
                    "java/lang/invoke/LambdaMetafactory",
                    "metafactory",
                    "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;"
                            + "Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;"
                            + "Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)"
                            + "Ljava/lang/invoke/CallSite;"),
            target(
                    "java/lang/invoke/LambdaMetafactory",
                    "altMetafactory",
                    "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;"
                            + "Ljava/lang/invoke/MethodType;[Ljava/lang/Object;)"
                            + "Ljava/lang/invoke/CallSite;"),
            target(
                    "java/lang/invoke/StringConcatFactory",
                    "makeConcat",
                    "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;"
                            + "Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;"),
            target(
                    "java/lang/invoke/StringConcatFactory",
                    "makeConcatWithConstants",
                    "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;"
                            + "Ljava/lang/invoke/MethodType;Ljava/lang/String;[Ljava/lang/Object;)"
                            + "Ljava/lang/invoke/CallSite;"),
            target(
                    "java/lang/runtime/ObjectMethods",
                    "bootstrap",
                    "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;"
                            + "Ljava/lang/invoke/TypeDescriptor;Ljava/lang/Class;Ljava/lang/String;"
                            + "[Ljava/lang/invoke/MethodHandle;)Ljava/lang/Object;"),
            target(
                    "java/lang/runtime/SwitchBootstraps",
                    "typeSwitch",
                    "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;"
                            + "Ljava/lang/invoke/MethodType;[Ljava/lang/Object;)"
                            + "Ljava/lang/invoke/CallSite;"),
            target(
                    "java/lang/runtime/SwitchBootstraps",
                    "enumSwitch",
                    "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;"
                            + "Ljava/lang/invoke/MethodType;[Ljava/lang/Object;)"
                            + "Ljava/lang/invoke/CallSite;"),
            constantBootstrap(
                    "nullConstant",
                    "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/Class;)"
                            + "Ljava/lang/Object;"),
            constantBootstrap(
                    "primitiveClass",
                    "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/Class;)"
                            + "Ljava/lang/Class;"),
            constantBootstrap(
                    "enumConstant",
                    "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/Class;)"
                            + "Ljava/lang/Enum;"),
            constantBootstrap(
                    "getStaticFinal",
                    "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/Class;)"
                            + "Ljava/lang/Object;"),
            constantBootstrap(
                    "getStaticFinal",
                    "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/Class;"
                            + "Ljava/lang/Class;)Ljava/lang/Object;"),
            constantBootstrap(
                    "invoke",
                    "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/Class;"
                            + "Ljava/lang/invoke/MethodHandle;[Ljava/lang/Object;)Ljava/lang/Object;"),
            constantBootstrap(
                    "fieldVarHandle",
                    "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/Class;"
                            + "Ljava/lang/Class;Ljava/lang/Class;)Ljava/lang/invoke/VarHandle;"),
            constantBootstrap(
                    "staticFieldVarHandle",
                    "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/Class;"
                            + "Ljava/lang/Class;Ljava/lang/Class;)Ljava/lang/invoke/VarHandle;"),
            constantBootstrap(
                    "arrayVarHandle",
                    "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/Class;"
                            + "Ljava/lang/Class;)Ljava/lang/invoke/VarHandle;"),
            constantBootstrap(
                    "explicitCast",
                    "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/Class;"
                            + "Ljava/lang/Object;)Ljava/lang/Object;"));

    private final IndirectFieldObserverClassifier classifier =
            new IndirectFieldObserverClassifier();

    Optional<FieldDynamicBoundaryKind> unsafeBootstrapTarget(
            Handle handle) {
        Optional<FieldDynamicBoundaryKind> knownObserver = classifier.classify(
                handle.getOwner(),
                handle.getName());
        if (knownObserver.isPresent()) {
            return knownObserver;
        }
        if (handle.getTag() == Opcodes.H_INVOKESTATIC
                && SAFE_JDK_BOOTSTRAPS.contains(target(handle))) {
            return Optional.empty();
        }
        return Optional.of(FieldDynamicBoundaryKind.METHOD_HANDLE);
    }

    Optional<FieldDynamicBoundaryKind> unsafeBootstrapArgument(
            Handle handle,
            FieldObserverDeclarationIndex declarations) {
        Optional<FieldDynamicBoundaryKind> knownObserver = classifier.classify(
                handle.getOwner(),
                handle.getName());
        if (knownObserver.isPresent()) {
            return knownObserver;
        }
        FieldObserverDeclarationIndex.HandleTarget parsedTarget =
                new FieldObserverDeclarationIndex.HandleTarget(
                        handle.getTag(),
                        handle.getOwner(),
                        handle.getName(),
                        handle.getDesc());
        if (declarations.hasScannedMethodBody(parsedTarget)
                || handle.getTag() == Opcodes.H_INVOKESTATIC
                        && SAFE_JDK_BOOTSTRAPS.contains(target(handle))) {
            return Optional.empty();
        }
        return Optional.of(FieldDynamicBoundaryKind.METHOD_HANDLE);
    }

    private static Target constantBootstrap(String name, String descriptor) {
        return target("java/lang/invoke/ConstantBootstraps", name, descriptor);
    }

    private static Target target(Handle handle) {
        return target(handle.getOwner(), handle.getName(), handle.getDesc());
    }

    private static Target target(String owner, String name, String descriptor) {
        return new Target(owner, name, descriptor);
    }

    private record Target(String owner, String name, String descriptor) {}
}
