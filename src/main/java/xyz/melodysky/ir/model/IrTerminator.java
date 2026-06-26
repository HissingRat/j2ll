package xyz.melodysky.ir.model;

import java.util.Objects;
import java.util.List;
import java.util.Optional;

public record IrTerminator(
        IrTerminatorKind kind,
        Optional<IrValue> value,
        Optional<IrValue> condition,
        Optional<String> target,
        Optional<String> trueTarget,
        Optional<String> falseTarget,
        List<IrValue> targetArguments,
        List<IrValue> trueTargetArguments,
        List<IrValue> falseTargetArguments,
        Optional<IrValue> switchValue,
        Optional<String> defaultTarget,
        List<IrValue> defaultTargetArguments,
        List<IrSwitchCase> switchCases) {
    public IrTerminator {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(condition, "condition");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(trueTarget, "trueTarget");
        Objects.requireNonNull(falseTarget, "falseTarget");
        targetArguments = List.copyOf(Objects.requireNonNull(targetArguments, "targetArguments"));
        trueTargetArguments = List.copyOf(Objects.requireNonNull(trueTargetArguments, "trueTargetArguments"));
        falseTargetArguments = List.copyOf(Objects.requireNonNull(falseTargetArguments, "falseTargetArguments"));
        Objects.requireNonNull(switchValue, "switchValue");
        Objects.requireNonNull(defaultTarget, "defaultTarget");
        defaultTargetArguments = List.copyOf(Objects.requireNonNull(defaultTargetArguments, "defaultTargetArguments"));
        switchCases = List.copyOf(Objects.requireNonNull(switchCases, "switchCases"));
    }

    public IrTerminator(IrTerminatorKind kind, Optional<IrValue> value) {
        this(
                kind,
                value,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                List.of(),
                List.of(),
                List.of(),
                Optional.empty(),
                Optional.empty(),
                List.of(),
                List.of());
    }

    public static IrTerminator returnVoid() {
        return new IrTerminator(IrTerminatorKind.RETURN, Optional.empty());
    }

    public static IrTerminator returnValue(IrValue value) {
        return new IrTerminator(IrTerminatorKind.RETURN, Optional.of(value));
    }

    public static IrTerminator throwValue(IrValue value) {
        return new IrTerminator(IrTerminatorKind.THROW, Optional.of(value));
    }

    public static IrTerminator gotoBlock(String target) {
        return gotoBlock(target, List.of());
    }

    public static IrTerminator gotoBlock(String target, List<IrValue> arguments) {
        return new IrTerminator(
                IrTerminatorKind.GOTO,
                Optional.empty(),
                Optional.empty(),
                Optional.of(target),
                Optional.empty(),
                Optional.empty(),
                arguments,
                List.of(),
                List.of(),
                Optional.empty(),
                Optional.empty(),
                List.of(),
                List.of());
    }

    public static IrTerminator branch(IrValue condition, String trueTarget, String falseTarget) {
        return branch(condition, trueTarget, List.of(), falseTarget, List.of());
    }

    public static IrTerminator branch(
            IrValue condition,
            String trueTarget,
            List<IrValue> trueArguments,
            String falseTarget,
            List<IrValue> falseArguments) {
        return new IrTerminator(
                IrTerminatorKind.BRANCH,
                Optional.empty(),
                Optional.of(condition),
                Optional.empty(),
                Optional.of(trueTarget),
                Optional.of(falseTarget),
                List.of(),
                trueArguments,
                falseArguments,
                Optional.empty(),
                Optional.empty(),
                List.of(),
                List.of());
    }

    public static IrTerminator switchOn(IrValue value, String defaultTarget, List<IrSwitchCase> cases) {
        return switchOn(value, defaultTarget, List.of(), cases);
    }

    public static IrTerminator switchOn(
            IrValue value,
            String defaultTarget,
            List<IrValue> defaultArguments,
            List<IrSwitchCase> cases) {
        return new IrTerminator(
                IrTerminatorKind.SWITCH,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                List.of(),
                List.of(),
                List.of(),
                Optional.of(value),
                Optional.of(defaultTarget),
                defaultArguments,
                cases.stream().sorted().toList());
    }
}
