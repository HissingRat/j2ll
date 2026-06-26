package xyz.melodysky.backend.llvm.model;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record LlvmTerminator(
        LlvmTerminatorKind kind,
        LlvmType returnType,
        Optional<String> returnValue,
        Optional<String> condition,
        Optional<String> target,
        Optional<String> trueTarget,
        Optional<String> falseTarget,
        Optional<String> switchValue,
        Optional<String> defaultTarget,
        List<LlvmSwitchCase> switchCases) {
    public LlvmTerminator {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(returnType, "returnType");
        Objects.requireNonNull(returnValue, "returnValue");
        Objects.requireNonNull(condition, "condition");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(trueTarget, "trueTarget");
        Objects.requireNonNull(falseTarget, "falseTarget");
        Objects.requireNonNull(switchValue, "switchValue");
        Objects.requireNonNull(defaultTarget, "defaultTarget");
        switchCases = List.copyOf(Objects.requireNonNull(switchCases, "switchCases"));
    }

    public LlvmTerminator(LlvmType returnType, Optional<String> returnValue) {
        this(
                LlvmTerminatorKind.RETURN,
                returnType,
                returnValue,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                List.of());
    }

    public static LlvmTerminator gotoBlock(String target) {
        return new LlvmTerminator(
                LlvmTerminatorKind.GOTO,
                LlvmType.VOID,
                Optional.empty(),
                Optional.empty(),
                Optional.of(target),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                List.of());
    }

    public static LlvmTerminator throwValue(String value) {
        return new LlvmTerminator(
                LlvmTerminatorKind.THROW,
                LlvmType.VOID,
                Optional.of(value),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                List.of());
    }

    public static LlvmTerminator branch(String condition, String trueTarget, String falseTarget) {
        return new LlvmTerminator(
                LlvmTerminatorKind.BRANCH,
                LlvmType.VOID,
                Optional.empty(),
                Optional.of(condition),
                Optional.empty(),
                Optional.of(trueTarget),
                Optional.of(falseTarget),
                Optional.empty(),
                Optional.empty(),
                List.of());
    }

    public static LlvmTerminator switchOn(String value, String defaultTarget, List<LlvmSwitchCase> cases) {
        return new LlvmTerminator(
                LlvmTerminatorKind.SWITCH,
                LlvmType.VOID,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.of(value),
                Optional.of(defaultTarget),
                cases);
    }
}
