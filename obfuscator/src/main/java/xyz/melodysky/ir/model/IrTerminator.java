package xyz.melodysky.ir.model;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public sealed interface IrTerminator permits
        IrTerminator.Branch,
        IrTerminator.Goto,
        IrTerminator.Return,
        IrTerminator.ReturnVoid,
        IrTerminator.Switch,
        IrTerminator.Throw,
        IrTerminator.Unreachable {

    record Goto(String targetBlock) implements IrTerminator {
        public Goto {
            validateLabel(targetBlock);
        }
    }

    record Branch(IrValue condition, String trueTarget, String falseTarget) implements IrTerminator {
        public Branch {
            Objects.requireNonNull(condition, "condition");
            validateLabel(trueTarget);
            validateLabel(falseTarget);
        }
    }

    record Switch(IrValue selector, Map<Integer, String> targetByKey, String defaultTarget) implements IrTerminator {
        public Switch {
            Objects.requireNonNull(selector, "selector");
            Objects.requireNonNull(targetByKey, "targetByKey");
            validateLabel(defaultTarget);
            LinkedHashMap<Integer, String> normalized = new LinkedHashMap<>();
            for (Map.Entry<Integer, String> entry : targetByKey.entrySet()) {
                Objects.requireNonNull(entry.getKey(), "targetByKey key");
                validateLabel(entry.getValue());
                normalized.put(entry.getKey(), entry.getValue());
            }
            targetByKey = Map.copyOf(normalized);
        }
    }

    record Return(IrValue value) implements IrTerminator {
        public Return {
            Objects.requireNonNull(value, "value");
        }
    }

    record ReturnVoid() implements IrTerminator {
    }

    record Throw(IrValue exceptionValue) implements IrTerminator {
        public Throw {
            Objects.requireNonNull(exceptionValue, "exceptionValue");
        }
    }

    record Unreachable() implements IrTerminator {
    }

    private static void validateLabel(String label) {
        Objects.requireNonNull(label, "label");
        if (label.isBlank()) {
            throw new IllegalArgumentException("block label must not be blank");
        }
    }
}
