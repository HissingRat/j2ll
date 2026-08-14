package xyz.melodysky.backend.llvm.protection;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import xyz.melodysky.backend.llvm.model.LlvmBasicBlock;
import xyz.melodysky.backend.llvm.model.LlvmFunction;
import xyz.melodysky.backend.llvm.model.LlvmInstruction;
import xyz.melodysky.backend.llvm.model.LlvmModule;
import xyz.melodysky.backend.llvm.model.LlvmModuleValidator;
import xyz.melodysky.backend.llvm.model.LlvmTerminator;
import xyz.melodysky.backend.llvm.model.LlvmTerminatorKind;
import xyz.melodysky.ir.pass.protection.ProtectionRandom;

/**
 * Strengthens existing conditional branches with a deterministic, side-effect-free predicate
 * that is true under LLVM integer semantics.
 */
public final class LlvmOpaquePredicatePass implements LlvmModulePass {
    private final LlvmModuleValidator validator;

    public LlvmOpaquePredicatePass() {
        this(new LlvmModuleValidator());
    }

    LlvmOpaquePredicatePass(LlvmModuleValidator validator) {
        this.validator = validator;
    }

    @Override
    public String name() {
        return "llvmOpaquePredicates";
    }

    @Override
    public LlvmModule run(LlvmModule module, LlvmProtectionConfig config) {
        return runDetailed(module, config).module();
    }

    public LlvmOpaquePredicateResult runDetailed(
            LlvmModule module,
            LlvmProtectionConfig config) {
        List<String> inputIssues = validator.validate(module);
        if (!inputIssues.isEmpty()) {
            return new LlvmOpaquePredicateResult(module, List.of(), inputIssues);
        }
        if (!config.enabled() || !config.opaquePredicates()) {
            return new LlvmOpaquePredicateResult(module, List.of(), List.of());
        }

        ProtectionRandom random = new ProtectionRandom(config.seed());
        ArrayList<LlvmFunction> functions = new ArrayList<>();
        ArrayList<String> affected = new ArrayList<>();
        for (LlvmFunction function : module.functions()) {
            LlvmFunction rewritten = rewriteFunction(module.identifier(), function, random);
            functions.add(rewritten);
            if (!rewritten.equals(function)) {
                affected.add(function.name());
            }
        }
        if (affected.isEmpty()) {
            return new LlvmOpaquePredicateResult(module, List.of(), List.of());
        }

        LlvmModule candidate =
                new LlvmModule(module.identifier(), module.declarations(), module.globals(), functions);
        List<String> outputIssues = validator.validate(candidate);
        if (!outputIssues.isEmpty()) {
            return new LlvmOpaquePredicateResult(module, List.of(), outputIssues);
        }
        return new LlvmOpaquePredicateResult(candidate, affected, List.of());
    }

    private LlvmFunction rewriteFunction(
            String moduleIdentifier,
            LlvmFunction function,
            ProtectionRandom random) {
        Set<String> usedValues = collectValueNames(function);
        ArrayList<LlvmBasicBlock> blocks = new ArrayList<>();
        boolean changed = false;
        for (LlvmBasicBlock block : function.blocks()) {
            if (block.terminator().kind() != LlvmTerminatorKind.BRANCH) {
                blocks.add(block);
                continue;
            }
            String site = moduleIdentifier + ":" + function.name() + ":" + block.name();
            String token = random.token(name(), site, 20);
            String prefix = uniquePrefix("%j2ll_opq_" + token, usedValues);
            int constant = predicateConstant(random.token(name() + "Constant", site, 8));

            ArrayList<LlvmInstruction> instructions = new ArrayList<>(block.instructions());
            instructions.add(LlvmInstruction.rawProvenNoNativeUnwind(
                    Optional.of(prefix + "_mix"),
                    "xor i32 " + constant + ", " + constant));
            instructions.add(LlvmInstruction.rawProvenNoNativeUnwind(
                    Optional.of(prefix + "_true"),
                    "icmp eq i32 " + prefix + "_mix, 0"));
            instructions.add(LlvmInstruction.rawProvenNoNativeUnwind(
                    Optional.of(prefix + "_condition"),
                    "and i1 "
                            + block.terminator().condition().orElseThrow()
                            + ", "
                            + prefix
                            + "_true"));

            LlvmTerminator terminator = LlvmTerminator.branch(
                    prefix + "_condition",
                    block.terminator().trueTarget().orElseThrow(),
                    block.terminator().falseTarget().orElseThrow());
            blocks.add(new LlvmBasicBlock(block.name(), instructions, terminator));
            changed = true;
        }
        if (!changed) {
            return function;
        }
        return new LlvmFunction(
                function.name(),
                function.linkage(),
                function.visibility(),
                function.returnType(),
                function.parameters(),
                blocks,
                function.nativeUnwindSemantics(),
                function.attributes());
    }

    private Set<String> collectValueNames(LlvmFunction function) {
        HashSet<String> values = new HashSet<>();
        function.parameters().forEach(parameter -> values.add(parameter.name()));
        function.blocks().stream()
                .flatMap(block -> block.instructions().stream())
                .flatMap(instruction -> instruction.result().stream())
                .forEach(values::add);
        return values;
    }

    private String uniquePrefix(String candidate, Set<String> usedValues) {
        String prefix = candidate;
        int suffix = 0;
        while (usedValues.contains(prefix + "_mix")
                || usedValues.contains(prefix + "_true")
                || usedValues.contains(prefix + "_condition")) {
            suffix++;
            prefix = candidate + "_" + suffix;
        }
        usedValues.add(prefix + "_mix");
        usedValues.add(prefix + "_true");
        usedValues.add(prefix + "_condition");
        return prefix;
    }

    private int predicateConstant(String token) {
        long unsigned = Long.parseUnsignedLong(token, 16);
        int value = (int) unsigned;
        return value == 0 ? 1 : value;
    }
}
