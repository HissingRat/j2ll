package xyz.melodysky.ir.model;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

public sealed interface IrInstruction permits
        IrInstruction.Binary,
        IrInstruction.CallHelper,
        IrInstruction.CallHelperVoid,
        IrInstruction.Compare,
        IrInstruction.Const,
        IrInstruction.Convert,
        IrInstruction.Invoke,
        IrInstruction.LoadField,
        IrInstruction.LoadStaticField,
        IrInstruction.LoadLocal,
        IrInstruction.NewObject,
        IrInstruction.StoreField,
        IrInstruction.StoreStaticField,
        IrInstruction.StoreLocal {

    Optional<IrValue> producedValue();

    record Const(IrValue result, Object value) implements IrInstruction {
        public Const {
            Objects.requireNonNull(result, "result");
        }

        @Override
        public Optional<IrValue> producedValue() {
            return Optional.of(result);
        }
    }

    record LoadLocal(IrValue result, int slot) implements IrInstruction {
        public LoadLocal {
            Objects.requireNonNull(result, "result");
            if (slot < 0) {
                throw new IllegalArgumentException("slot must be non-negative");
            }
        }

        @Override
        public Optional<IrValue> producedValue() {
            return Optional.of(result);
        }
    }

    record StoreLocal(int slot, IrValue value) implements IrInstruction {
        public StoreLocal {
            if (slot < 0) {
                throw new IllegalArgumentException("slot must be non-negative");
            }
            Objects.requireNonNull(value, "value");
        }

        @Override
        public Optional<IrValue> producedValue() {
            return Optional.empty();
        }
    }

    record Binary(IrValue result, IrBinaryOpcode opcode, IrValue left, IrValue right) implements IrInstruction {
        public Binary {
            Objects.requireNonNull(result, "result");
            Objects.requireNonNull(opcode, "opcode");
            Objects.requireNonNull(left, "left");
            Objects.requireNonNull(right, "right");
        }

        @Override
        public Optional<IrValue> producedValue() {
            return Optional.of(result);
        }
    }

    record Compare(IrValue result, IrCompareOpcode opcode, IrValue left, IrValue right) implements IrInstruction {
        public Compare {
            Objects.requireNonNull(result, "result");
            Objects.requireNonNull(opcode, "opcode");
            Objects.requireNonNull(left, "left");
            Objects.requireNonNull(right, "right");
            if (result.type() != IrType.BOOLEAN) {
                throw new IllegalArgumentException("compare result must have boolean type");
            }
        }

        @Override
        public Optional<IrValue> producedValue() {
            return Optional.of(result);
        }
    }

    record Convert(IrValue result, IrValue value) implements IrInstruction {
        public Convert {
            Objects.requireNonNull(result, "result");
            Objects.requireNonNull(value, "value");
        }

        @Override
        public Optional<IrValue> producedValue() {
            return Optional.of(result);
        }
    }

    record LoadField(IrValue result, IrFieldRef field, IrValue owner) implements IrInstruction {
        public LoadField {
            Objects.requireNonNull(result, "result");
            Objects.requireNonNull(field, "field");
            Objects.requireNonNull(owner, "owner");
        }

        @Override
        public Optional<IrValue> producedValue() {
            return Optional.of(result);
        }
    }

    record LoadStaticField(IrValue result, IrFieldRef field) implements IrInstruction {
        public LoadStaticField {
            Objects.requireNonNull(result, "result");
            Objects.requireNonNull(field, "field");
            if (!field.isStatic()) {
                throw new IllegalArgumentException("load_static_field requires a static field reference");
            }
        }

        @Override
        public Optional<IrValue> producedValue() {
            return Optional.of(result);
        }
    }

    record NewObject(IrValue result, IrClassRef classRef) implements IrInstruction {
        public NewObject {
            Objects.requireNonNull(result, "result");
            Objects.requireNonNull(classRef, "classRef");
            if (result.type().isPrimitive() || result.type() == IrType.VOID) {
                throw new IllegalArgumentException("new_object result must be reference-like");
            }
        }

        @Override
        public Optional<IrValue> producedValue() {
            return Optional.of(result);
        }
    }

    record StoreField(IrFieldRef field, IrValue owner, IrValue value) implements IrInstruction {
        public StoreField {
            Objects.requireNonNull(field, "field");
            Objects.requireNonNull(owner, "owner");
            Objects.requireNonNull(value, "value");
        }

        @Override
        public Optional<IrValue> producedValue() {
            return Optional.empty();
        }
    }

    record StoreStaticField(IrFieldRef field, IrValue value) implements IrInstruction {
        public StoreStaticField {
            Objects.requireNonNull(field, "field");
            Objects.requireNonNull(value, "value");
            if (!field.isStatic()) {
                throw new IllegalArgumentException("store_static_field requires a static field reference");
            }
        }

        @Override
        public Optional<IrValue> producedValue() {
            return Optional.empty();
        }
    }

    record Invoke(IrValue result, IrMethodRef method, List<IrValue> arguments) implements IrInstruction {
        public Invoke {
            Objects.requireNonNull(result, "result");
            Objects.requireNonNull(method, "method");
            Objects.requireNonNull(arguments, "arguments");
            arguments = List.copyOf(arguments);
        }

        @Override
        public Optional<IrValue> producedValue() {
            return Optional.of(result);
        }
    }

    record CallHelper(IrValue result, String helperName, List<IrValue> arguments) implements IrInstruction {
        public CallHelper {
            Objects.requireNonNull(result, "result");
            Objects.requireNonNull(helperName, "helperName");
            Objects.requireNonNull(arguments, "arguments");
            if (helperName.isBlank()) {
                throw new IllegalArgumentException("helperName must not be blank");
            }
            arguments = List.copyOf(arguments);
        }

        @Override
        public Optional<IrValue> producedValue() {
            return Optional.of(result);
        }
    }

    record CallHelperVoid(String helperName, List<IrValue> arguments) implements IrInstruction {
        public CallHelperVoid {
            Objects.requireNonNull(helperName, "helperName");
            Objects.requireNonNull(arguments, "arguments");
            if (helperName.isBlank()) {
                throw new IllegalArgumentException("helperName must not be blank");
            }
            arguments = List.copyOf(arguments);
        }

        @Override
        public Optional<IrValue> producedValue() {
            return Optional.empty();
        }
    }
}
