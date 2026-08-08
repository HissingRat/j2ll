package xyz.melodysky.analysis.field;

import java.util.Objects;
import java.util.Optional;
import org.objectweb.asm.ConstantDynamic;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

/** Shared parser for JDK ConstantBootstraps field-bearing constants. */
public final class ConstantDynamicFieldReferenceResolver {
    public Resolution resolve(String currentOwner, ConstantDynamic constantDynamic) {
        Objects.requireNonNull(currentOwner, "currentOwner");
        Objects.requireNonNull(constantDynamic, "constantDynamic");
        Handle bootstrap = constantDynamic.getBootstrapMethod();
        if (!bootstrap.getOwner().equals("java/lang/invoke/ConstantBootstraps")) {
            return Resolution.notFieldBootstrap();
        }
        FieldDynamicBoundaryKind observerKind;
        boolean staticField;
        Optional<FieldId> target;
        switch (bootstrap.getName()) {
            case "getStaticFinal" -> {
                observerKind = FieldDynamicBoundaryKind.METHOD_HANDLE;
                staticField = true;
                target = getStaticFinalTarget(currentOwner, constantDynamic);
            }
            case "fieldVarHandle" -> {
                observerKind = FieldDynamicBoundaryKind.VAR_HANDLE;
                staticField = false;
                target = varHandleTarget(constantDynamic);
            }
            case "staticFieldVarHandle" -> {
                observerKind = FieldDynamicBoundaryKind.VAR_HANDLE;
                staticField = true;
                target = varHandleTarget(constantDynamic);
            }
            default -> {
                return Resolution.notFieldBootstrap();
            }
        }
        if (bootstrap.getTag() != Opcodes.H_INVOKESTATIC) {
            target = Optional.empty();
        }
        return new Resolution(true, target, staticField, observerKind);
    }

    private Optional<FieldId> getStaticFinalTarget(
            String currentOwner,
            ConstantDynamic constantDynamic) {
        int argumentCount = constantDynamic.getBootstrapMethodArgumentCount();
        if (argumentCount == 0) {
            return Optional.of(new FieldId(
                    currentOwner,
                    constantDynamic.getName(),
                    constantDynamic.getDescriptor()));
        }
        if (argumentCount == 1
                && constantDynamic.getBootstrapMethodArgument(0) instanceof Type declaringType) {
            return classOwner(declaringType).map(owner -> new FieldId(
                    owner,
                    constantDynamic.getName(),
                    constantDynamic.getDescriptor()));
        }
        return Optional.empty();
    }

    private Optional<FieldId> varHandleTarget(ConstantDynamic constantDynamic) {
        if (constantDynamic.getBootstrapMethodArgumentCount() != 2
                || !(constantDynamic.getBootstrapMethodArgument(0) instanceof Type declaringType)
                || !(constantDynamic.getBootstrapMethodArgument(1) instanceof Type fieldType)) {
            return Optional.empty();
        }
        return classOwner(declaringType).map(owner -> new FieldId(
                owner,
                constantDynamic.getName(),
                fieldDescriptor(fieldType)));
    }

    private Optional<String> classOwner(Type type) {
        return switch (type.getSort()) {
            case Type.OBJECT -> primitiveClassToken(type.getInternalName())
                    ? Optional.empty()
                    : Optional.of(type.getInternalName());
            case Type.ARRAY -> Optional.of(type.getDescriptor());
            default -> Optional.empty();
        };
    }

    private String fieldDescriptor(Type type) {
        if (type.getSort() == Type.OBJECT
                && primitiveClassToken(type.getInternalName())) {
            return type.getInternalName();
        }
        return type.getDescriptor();
    }

    private boolean primitiveClassToken(String internalName) {
        return internalName.length() == 1
                && "ZBCSIJFDV".indexOf(internalName.charAt(0)) >= 0;
    }

    public record Resolution(
            boolean fieldBootstrap,
            Optional<FieldId> target,
            boolean staticField,
            FieldDynamicBoundaryKind observerKind) {
        public Resolution {
            target = Objects.requireNonNull(target, "target");
            Objects.requireNonNull(observerKind, "observerKind");
            if (!fieldBootstrap && target.isPresent()) {
                throw new IllegalArgumentException(
                        "non-field bootstrap cannot have a target");
            }
        }

        static Resolution notFieldBootstrap() {
            return new Resolution(
                    false,
                    Optional.empty(),
                    false,
                    FieldDynamicBoundaryKind.METHOD_HANDLE);
        }
    }
}
