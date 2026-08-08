package xyz.melodysky.toolchain;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import xyz.melodysky.backend.llvm.model.LlvmModule;
import xyz.melodysky.backend.llvm.model.LlvmModuleEmissionPlan;
import xyz.melodysky.backend.llvm.protection.LlvmBlockLayoutPerturbationResult;
import xyz.melodysky.backend.llvm.protection.LlvmCallIndirectionResult;
import xyz.melodysky.backend.llvm.protection.LlvmGlobalLayoutResult;
import xyz.melodysky.backend.llvm.protection.LlvmIrCallIndirectionResult;
import xyz.melodysky.backend.llvm.protection.LlvmOpaquePredicateResult;
import xyz.melodysky.ir.model.IrMethod;

/**
 * One final LLVM module and the pass evidence produced while compiling it.
 *
 * <p>The same instance is consumed by reports, intermediate dumps, and the
 * Zig source writer so those three views cannot drift apart.</p>
 */
public record NativeLlvmModuleCompilation(
        String owner,
        List<IrMethod> registeredMethods,
        List<IrMethod> userMethods,
        List<IrMethod> compiledMethods,
        LlvmBlockLayoutPerturbationResult blockLayout,
        LlvmOpaquePredicateResult opaquePredicates,
        LlvmIrCallIndirectionResult irCallIndirection,
        LlvmCallIndirectionResult llvmCallIndirection,
        LlvmGlobalLayoutResult globalLayout,
        LlvmModuleEmissionPlan emissionPlan,
        String llvmText,
        Optional<String> llvmTextWithoutUnwind) {
    public NativeLlvmModuleCompilation {
        Objects.requireNonNull(owner, "owner");
        registeredMethods = List.copyOf(
                Objects.requireNonNull(registeredMethods, "registeredMethods"));
        userMethods = List.copyOf(
                Objects.requireNonNull(userMethods, "userMethods"));
        compiledMethods = List.copyOf(Objects.requireNonNull(compiledMethods, "compiledMethods"));
        Objects.requireNonNull(blockLayout, "blockLayout");
        Objects.requireNonNull(opaquePredicates, "opaquePredicates");
        Objects.requireNonNull(irCallIndirection, "irCallIndirection");
        Objects.requireNonNull(llvmCallIndirection, "llvmCallIndirection");
        Objects.requireNonNull(globalLayout, "globalLayout");
        Objects.requireNonNull(emissionPlan, "emissionPlan");
        Objects.requireNonNull(llvmText, "llvmText");
        Objects.requireNonNull(llvmTextWithoutUnwind, "llvmTextWithoutUnwind");
        if (emissionPlan.module() != globalLayout.module()) {
            throw new IllegalArgumentException(
                    "LLVM emission plan must bind the final global-layout module instance");
        }
        if (emissionPlan.proof().omissionSafe() != llvmTextWithoutUnwind.isPresent()) {
            throw new IllegalArgumentException(
                    "LLVM omission text must match the final module proof");
        }
    }

    public NativeLlvmModuleCompilation(
            String owner,
            List<IrMethod> registeredMethods,
            List<IrMethod> compiledMethods,
            LlvmBlockLayoutPerturbationResult blockLayout,
            LlvmOpaquePredicateResult opaquePredicates,
            LlvmIrCallIndirectionResult irCallIndirection,
            LlvmCallIndirectionResult llvmCallIndirection,
            LlvmGlobalLayoutResult globalLayout,
            String llvmText) {
        this(
                owner,
                registeredMethods,
                registeredMethods,
                compiledMethods,
                blockLayout,
                opaquePredicates,
                irCallIndirection,
                llvmCallIndirection,
                globalLayout,
                emissionPlan(globalLayout),
                llvmText,
                omissionText(globalLayout));
    }

    public LlvmModule module() {
        return globalLayout.module();
    }

    private static LlvmModuleEmissionPlan emissionPlan(
            LlvmGlobalLayoutResult globalLayout) {
        return LlvmModuleEmissionPlan.create(globalLayout.module());
    }

    private static Optional<String> omissionText(
            LlvmGlobalLayoutResult globalLayout) {
        LlvmModuleEmissionPlan plan = emissionPlan(globalLayout);
        return plan.proof().omissionSafe()
                ? Optional.of(new xyz.melodysky.backend.llvm.model.LlvmTextEmitter()
                        .emit(
                                plan,
                                xyz.melodysky.backend.llvm.model.LlvmUnwindEmissionMode
                                        .OMIT_PROVEN))
                : Optional.empty();
    }
}
