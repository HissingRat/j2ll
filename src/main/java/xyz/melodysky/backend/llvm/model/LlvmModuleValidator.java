package xyz.melodysky.backend.llvm.model;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Structural validation for the LLVM subset represented by {@link LlvmModule}.
 *
 * <p>This validator intentionally does not parse {@link LlvmInstruction#rawText() raw LLVM text}.
 * Passes that cannot preserve opaque raw instructions must reject those shapes themselves.
 */
public final class LlvmModuleValidator {
    public List<String> validate(LlvmModule module) {
        ArrayList<String> issues = new ArrayList<>();
        HashSet<String> globalNames = new HashSet<>();
        for (LlvmGlobal global : module.globals()) {
            if (!globalNames.add(global.name())) {
                issues.add("duplicate global name: " + global.name());
            }
        }
        HashSet<String> functionNames = new HashSet<>();
        for (LlvmFunction function : module.functions()) {
            if (!functionNames.add(function.name())) {
                issues.add("duplicate function name: " + function.name());
            }
            if (globalNames.contains(function.name())) {
                issues.add("global/function symbol collision: " + function.name());
            }
            validateFunction(function, issues);
        }
        return List.copyOf(issues);
    }

    private void validateFunction(LlvmFunction function, List<String> issues) {
        if (function.blocks().isEmpty()) {
            issues.add("function has no entry block: " + function.name());
            return;
        }
        HashSet<String> valueNames = new HashSet<>();
        for (LlvmParameter parameter : function.parameters()) {
            if (!valueNames.add(parameter.name())) {
                issues.add("duplicate parameter/value name in "
                        + function.name()
                        + ": "
                        + parameter.name());
            }
        }
        HashSet<String> blockNames = new HashSet<>();
        for (LlvmBasicBlock block : function.blocks()) {
            if (block.name().isBlank()) {
                issues.add("function has blank block name: " + function.name());
            } else if (!blockNames.add(block.name())) {
                issues.add("duplicate block name in " + function.name() + ": " + block.name());
            }
        }
        for (LlvmBasicBlock block : function.blocks()) {
            validateTargets(function.name(), block, blockNames, issues);
            validateValues(function.name(), block, valueNames, issues);
        }
    }

    private void validateTargets(
            String functionName,
            LlvmBasicBlock block,
            Set<String> blockNames,
            List<String> issues) {
        LlvmTerminator terminator = block.terminator();
        switch (terminator.kind()) {
            case RETURN, THROW -> {
                // No block target.
            }
            case GOTO -> validateTarget(
                    functionName, block.name(), terminator.target().orElse(null), blockNames, issues);
            case BRANCH -> {
                if (terminator.condition().isEmpty()) {
                    issues.add("branch has no condition in " + functionName + ":" + block.name());
                }
                validateTarget(
                        functionName, block.name(), terminator.trueTarget().orElse(null), blockNames, issues);
                validateTarget(
                        functionName, block.name(), terminator.falseTarget().orElse(null), blockNames, issues);
            }
            case SWITCH -> {
                if (terminator.switchValue().isEmpty()) {
                    issues.add("switch has no value in " + functionName + ":" + block.name());
                }
                validateTarget(
                        functionName, block.name(), terminator.defaultTarget().orElse(null), blockNames, issues);
                HashSet<Integer> keys = new HashSet<>();
                for (LlvmSwitchCase switchCase : terminator.switchCases()) {
                    if (!keys.add(switchCase.key())) {
                        issues.add("duplicate switch key in "
                                + functionName
                                + ":"
                                + block.name()
                                + ": "
                                + switchCase.key());
                    }
                    validateTarget(
                            functionName, block.name(), switchCase.target(), blockNames, issues);
                }
            }
        }
    }

    private void validateTarget(
            String functionName,
            String blockName,
            String target,
            Set<String> blockNames,
            List<String> issues) {
        if (target == null || !blockNames.contains(target)) {
            issues.add("unknown block target in "
                    + functionName
                    + ":"
                    + blockName
                    + ": "
                    + String.valueOf(target));
        }
    }

    private void validateValues(
            String functionName,
            LlvmBasicBlock block,
            Set<String> valueNames,
            List<String> issues) {
        for (LlvmInstruction instruction : block.instructions()) {
            instruction.result().ifPresent(result -> {
                if (result.isBlank()) {
                    issues.add("blank instruction result in " + functionName + ":" + block.name());
                } else if (!valueNames.add(result)) {
                    issues.add("duplicate instruction result in "
                            + functionName
                            + ":"
                            + block.name()
                            + ": "
                            + result);
                }
            });
        }
    }
}
