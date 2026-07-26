package xyz.melodysky.ir.pass.protection;

import java.util.HashSet;
import java.util.Set;
import xyz.melodysky.ir.model.IrMethod;
import xyz.melodysky.ir.model.IrType;
import xyz.melodysky.ir.model.IrValue;

final class MethodInliningNames {
    private final Set<String> valueNames = new HashSet<>();
    private final Set<String> blockNames = new HashSet<>();
    private final String prefix;
    private int nextValue;
    private int nextBlock;

    MethodInliningNames(IrMethod caller, String token) {
        caller.parameters().stream().map(IrValue::name).forEach(valueNames::add);
        caller.blocks().forEach(block -> {
            blockNames.add(block.name());
            block.parameters().stream().map(IrValue::name).forEach(valueNames::add);
            block.instructions().stream()
                    .flatMap(instruction -> instruction.result().stream())
                    .map(IrValue::name)
                    .forEach(valueNames::add);
        });
        prefix = "inl_" + token;
    }

    IrValue nextValue(IrType type) {
        String candidate;
        do {
            candidate = "%" + prefix + "_v" + nextValue++;
        } while (!valueNames.add(candidate));
        return new IrValue(candidate, type);
    }

    String nextBlock() {
        String candidate;
        do {
            candidate = prefix + "_b" + nextBlock++;
        } while (!blockNames.add(candidate));
        return candidate;
    }

    String continuationBlock() {
        String candidate = prefix + "_continue";
        int suffix = 0;
        while (!blockNames.add(candidate)) {
            candidate = prefix + "_continue_" + ++suffix;
        }
        return candidate;
    }
}
