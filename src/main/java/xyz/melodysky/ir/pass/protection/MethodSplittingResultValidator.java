package xyz.melodysky.ir.pass.protection;

import java.util.ArrayList;
import java.util.List;
import xyz.melodysky.ir.model.IrInstruction;
import xyz.melodysky.ir.model.IrOpcode;
import xyz.melodysky.ir.model.IrTerminatorKind;
import xyz.melodysky.ir.validate.IrMethodValidator;

final class MethodSplittingResultValidator {
    List<String> validate(MethodSplittingResult result) {
        ArrayList<String> errors = new ArrayList<>();
        new IrMethodValidator().validate(result.caller()).forEach(diagnostic ->
                errors.add("caller:" + diagnostic.code().value()));
        for (OutlinedMethodHelper helper : result.helpers()) {
            new IrMethodValidator().validate(helper.body()).forEach(diagnostic ->
                    errors.add("helper:" + diagnostic.code().value()));
            validateHelperContract(result, helper, errors);
        }
        return List.copyOf(errors);
    }

    private void validateHelperContract(
            MethodSplittingResult result,
            OutlinedMethodHelper helper,
            List<String> errors) {
        MethodSplitPlan plan = helper.plan();
        if (!plan.nativeSymbol().matches("j2ll_oh_[0-9a-f]{24}")) {
            errors.add("helper:native-symbol-not-hash-only");
        }
        if (helper.body().blocks().size() != 1
                || helper.body().blocks().get(0).terminator().kind() != IrTerminatorKind.RETURN
                || helper.body().blocks().get(0).terminator().value().stream()
                        .noneMatch(plan.liveOut()::equals)) {
            errors.add("helper:invalid-return-contract");
        }

        List<IrInstruction> calls = result.caller().blocks().stream()
                .flatMap(block -> block.instructions().stream())
                .filter(instruction -> instruction.opcode() == IrOpcode.CALL_STATIC)
                .filter(instruction -> instruction.symbol().stream().anyMatch(helper.methodKey()::equals))
                .toList();
        if (calls.size() != 1) {
            errors.add("caller:outlined-call-count");
            return;
        }
        IrInstruction call = calls.get(0);
        if (!call.operands().equals(plan.liveIns())
                || call.result().stream().noneMatch(plan.liveOut()::equals)) {
            errors.add("caller:outlined-call-abi-mismatch");
        }
    }
}
