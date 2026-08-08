package xyz.melodysky.backend.llvm.protection;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import xyz.melodysky.backend.llvm.model.LlvmBasicBlock;
import xyz.melodysky.backend.llvm.model.LlvmFunction;
import xyz.melodysky.backend.llvm.model.LlvmModule;
import xyz.melodysky.backend.llvm.model.LlvmModuleValidator;
import xyz.melodysky.ir.pass.protection.ProtectionRandom;

/**
 * Changes only the stable emission order of non-entry LLVM basic blocks.
 */
public final class LlvmBlockLayoutPerturbationPass implements LlvmModulePass {
    private final LlvmModuleValidator validator;

    public LlvmBlockLayoutPerturbationPass() {
        this(new LlvmModuleValidator());
    }

    LlvmBlockLayoutPerturbationPass(LlvmModuleValidator validator) {
        this.validator = validator;
    }

    @Override
    public String name() {
        return "llvmBlockLayoutPerturbation";
    }

    @Override
    public LlvmModule run(LlvmModule module, LlvmProtectionConfig config) {
        return runDetailed(module, config).module();
    }

    public LlvmBlockLayoutPerturbationResult runDetailed(
            LlvmModule module,
            LlvmProtectionConfig config) {
        List<String> inputIssues = validator.validate(module);
        if (!inputIssues.isEmpty()) {
            return new LlvmBlockLayoutPerturbationResult(module, List.of(), inputIssues);
        }
        if (!config.enabled() || !config.blockLayoutPerturbation()) {
            return new LlvmBlockLayoutPerturbationResult(module, List.of(), List.of());
        }

        ProtectionRandom random = new ProtectionRandom(config.seed());
        ArrayList<LlvmFunction> rewrittenFunctions = new ArrayList<>();
        ArrayList<String> affectedFunctions = new ArrayList<>();
        for (LlvmFunction function : module.functions()) {
            LlvmFunction rewritten = perturb(function, module.identifier(), random);
            rewrittenFunctions.add(rewritten);
            if (!rewritten.blocks().equals(function.blocks())) {
                affectedFunctions.add(function.name());
            }
        }
        LlvmModule candidate =
                new LlvmModule(module.identifier(), module.declarations(), module.globals(), rewrittenFunctions);
        List<String> outputIssues = validator.validate(candidate);
        if (!outputIssues.isEmpty()) {
            return new LlvmBlockLayoutPerturbationResult(module, List.of(), outputIssues);
        }
        return new LlvmBlockLayoutPerturbationResult(candidate, affectedFunctions, List.of());
    }

    private LlvmFunction perturb(
            LlvmFunction function,
            String moduleIdentifier,
            ProtectionRandom random) {
        if (function.blocks().size() < 3) {
            return function;
        }
        LlvmBasicBlock entry = function.blocks().get(0);
        ArrayList<LlvmBasicBlock> tail =
                new ArrayList<>(function.blocks().subList(1, function.blocks().size()));
        tail.sort(Comparator.comparing((LlvmBasicBlock block) -> random.token(
                        name(),
                        moduleIdentifier + ":" + function.name() + ":" + block.name(),
                        32))
                .thenComparing(LlvmBasicBlock::name));

        ArrayList<LlvmBasicBlock> reordered = new ArrayList<>(function.blocks().size());
        reordered.add(entry);
        reordered.addAll(tail);
        if (reordered.equals(function.blocks())) {
            rotateTail(reordered);
        }
        return new LlvmFunction(
                function.name(),
                function.linkage(),
                function.visibility(),
                function.returnType(),
                function.parameters(),
                reordered,
                function.nativeUnwindSemantics());
    }

    private void rotateTail(List<LlvmBasicBlock> blocks) {
        if (blocks.size() <= 2) {
            return;
        }
        LlvmBasicBlock firstTail = blocks.remove(1);
        blocks.add(firstTail);
    }
}
