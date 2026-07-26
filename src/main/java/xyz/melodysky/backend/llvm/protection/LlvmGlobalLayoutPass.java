package xyz.melodysky.backend.llvm.protection;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import xyz.melodysky.backend.llvm.model.LlvmGlobal;
import xyz.melodysky.backend.llvm.model.LlvmModule;
import xyz.melodysky.backend.llvm.model.LlvmModuleValidator;
import xyz.melodysky.ir.pass.protection.ProtectionRandom;

/**
 * Perturbs only the emission slots occupied by private/internal LLVM globals.
 *
 * <p>The pass deliberately keeps each {@link LlvmGlobal} intact. Its name, definition,
 * initializer, alignment, section, mutability and retention-related references therefore remain
 * byte-for-byte unchanged. Non-local globals and roots such as {@code llvm.used} stay at their
 * original indices.
 */
public final class LlvmGlobalLayoutPass implements LlvmModulePass {
    private final LlvmModuleValidator validator;
    private final OrderKeyFactory orderKeyFactory;

    public LlvmGlobalLayoutPass() {
        this(new LlvmModuleValidator(), LlvmGlobalLayoutPass::seededOrderKey);
    }

    LlvmGlobalLayoutPass(
            LlvmModuleValidator validator,
            OrderKeyFactory orderKeyFactory) {
        this.validator = Objects.requireNonNull(validator, "validator");
        this.orderKeyFactory = Objects.requireNonNull(orderKeyFactory, "orderKeyFactory");
    }

    @Override
    public String name() {
        return "llvmGlobalLayout";
    }

    @Override
    public LlvmModule run(LlvmModule module, LlvmProtectionConfig config) {
        return runDetailed(module, config).module();
    }

    public LlvmGlobalLayoutResult runDetailed(
            LlvmModule module,
            LlvmProtectionConfig config) {
        Objects.requireNonNull(module, "module");
        Objects.requireNonNull(config, "config");

        List<String> inputIssues = validator.validate(module);
        if (!inputIssues.isEmpty()) {
            return new LlvmGlobalLayoutResult(module, List.of(), inputIssues);
        }
        if (!config.enabled() || !config.globalLayout()) {
            return new LlvmGlobalLayoutResult(module, List.of(), List.of());
        }

        List<Integer> candidateSlots = candidateSlots(module.globals());
        if (candidateSlots.size() < 2) {
            return new LlvmGlobalLayoutResult(module, List.of(), List.of());
        }

        ArrayList<LlvmGlobal> reorderedCandidates = new ArrayList<>(candidateSlots.size());
        for (int slot : candidateSlots) {
            reorderedCandidates.add(module.globals().get(slot));
        }
        reorderedCandidates.sort(Comparator
                .comparing((LlvmGlobal global) -> orderKeyFactory.key(
                        config.seed(), module.identifier(), global))
                .thenComparing(LlvmGlobal::name));
        if (sameSlotOrder(module.globals(), candidateSlots, reorderedCandidates)) {
            rotate(reorderedCandidates);
        }

        ArrayList<LlvmGlobal> rewrittenGlobals = new ArrayList<>(module.globals());
        ArrayList<String> affectedGlobals = new ArrayList<>();
        for (int candidateIndex = 0; candidateIndex < candidateSlots.size(); candidateIndex++) {
            int slot = candidateSlots.get(candidateIndex);
            LlvmGlobal previous = module.globals().get(slot);
            LlvmGlobal replacement = reorderedCandidates.get(candidateIndex);
            rewrittenGlobals.set(slot, replacement);
            if (!previous.equals(replacement)) {
                affectedGlobals.add(previous.name());
                affectedGlobals.add(replacement.name());
            }
        }

        LlvmModule candidate = new LlvmModule(
                module.identifier(),
                module.declarations(),
                rewrittenGlobals,
                module.functions());
        List<String> outputIssues = validator.validate(candidate);
        if (!outputIssues.isEmpty()) {
            return new LlvmGlobalLayoutResult(module, List.of(), outputIssues);
        }
        return new LlvmGlobalLayoutResult(
                candidate,
                affectedGlobals.stream().distinct().sorted().toList(),
                List.of());
    }

    private List<Integer> candidateSlots(List<LlvmGlobal> globals) {
        ArrayList<Integer> result = new ArrayList<>();
        for (int index = 0; index < globals.size(); index++) {
            if (globals.get(index).hasModuleLocalLinkage()) {
                result.add(index);
            }
        }
        return List.copyOf(result);
    }

    private boolean sameSlotOrder(
            List<LlvmGlobal> original,
            List<Integer> slots,
            List<LlvmGlobal> candidates) {
        for (int index = 0; index < slots.size(); index++) {
            if (!original.get(slots.get(index)).equals(candidates.get(index))) {
                return false;
            }
        }
        return true;
    }

    private void rotate(List<LlvmGlobal> globals) {
        LlvmGlobal first = globals.remove(0);
        globals.add(first);
    }

    private static String seededOrderKey(
            long seed,
            String moduleIdentifier,
            LlvmGlobal global) {
        return new ProtectionRandom(seed).token(
                "LLVM_GLOBAL_LAYOUT",
                moduleIdentifier + ":" + global.name(),
                64);
    }

    @FunctionalInterface
    interface OrderKeyFactory {
        String key(long seed, String moduleIdentifier, LlvmGlobal global);
    }
}
