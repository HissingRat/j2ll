package xyz.melodysky.toolchain;

import java.util.List;
import java.util.Objects;
import xyz.melodysky.backend.llvm.model.LlvmModule;
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
        List<IrMethod> compiledMethods,
        LlvmBlockLayoutPerturbationResult blockLayout,
        LlvmOpaquePredicateResult opaquePredicates,
        LlvmIrCallIndirectionResult irCallIndirection,
        LlvmCallIndirectionResult llvmCallIndirection,
        LlvmGlobalLayoutResult globalLayout,
        String llvmText) {
    public NativeLlvmModuleCompilation {
        Objects.requireNonNull(owner, "owner");
        registeredMethods = List.copyOf(
                Objects.requireNonNull(registeredMethods, "registeredMethods"));
        compiledMethods = List.copyOf(Objects.requireNonNull(compiledMethods, "compiledMethods"));
        Objects.requireNonNull(blockLayout, "blockLayout");
        Objects.requireNonNull(opaquePredicates, "opaquePredicates");
        Objects.requireNonNull(irCallIndirection, "irCallIndirection");
        Objects.requireNonNull(llvmCallIndirection, "llvmCallIndirection");
        Objects.requireNonNull(globalLayout, "globalLayout");
        Objects.requireNonNull(llvmText, "llvmText");
    }

    public LlvmModule module() {
        return globalLayout.module();
    }
}
