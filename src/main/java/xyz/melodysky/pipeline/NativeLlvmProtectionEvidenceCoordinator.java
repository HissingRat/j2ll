package xyz.melodysky.pipeline;

import java.util.ArrayList;
import java.util.List;
import xyz.melodysky.backend.llvm.LlvmNameMangler;
import xyz.melodysky.backend.llvm.protection.LlvmBlockLayoutPerturbationResult;
import xyz.melodysky.backend.llvm.protection.LlvmCallIndirectionResult;
import xyz.melodysky.backend.llvm.protection.LlvmGlobalLayoutResult;
import xyz.melodysky.backend.llvm.protection.LlvmIrCallIndirectionResult;
import xyz.melodysky.backend.llvm.protection.LlvmOpaquePredicateResult;
import xyz.melodysky.diagnostic.Diagnostic;
import xyz.melodysky.diagnostic.DiagnosticBag;
import xyz.melodysky.ir.model.IrMethod;
import xyz.melodysky.report.ProtectionPassReport;
import xyz.melodysky.toolchain.NativeLlvmCompilation;
import xyz.melodysky.toolchain.NativeLlvmModuleCompilation;

/** Converts validated final LLVM pass results into reports and diagnostics. */
final class NativeLlvmProtectionEvidenceCoordinator {
    private final ProtectionEvidenceAssembler evidenceAssembler;

    NativeLlvmProtectionEvidenceCoordinator(
            ProtectionEvidenceAssembler evidenceAssembler) {
        this.evidenceAssembler = java.util.Objects.requireNonNull(
                evidenceAssembler,
                "evidenceAssembler");
    }

    Result assemble(
            NativeLlvmCompilation compilation,
            Settings settings,
            LlvmNameMangler nameMangler,
            long protectionSeed) {
        java.util.Objects.requireNonNull(compilation, "compilation");
        java.util.Objects.requireNonNull(settings, "settings");
        java.util.Objects.requireNonNull(nameMangler, "nameMangler");
        ArrayList<ProtectionPassReport> reports = new ArrayList<>();
        DiagnosticBag diagnostics = new DiagnosticBag();
        for (NativeLlvmModuleCompilation module : compilation.modules()) {
            appendModule(
                    module,
                    settings,
                    nameMangler,
                    protectionSeed,
                    reports,
                    diagnostics);
        }
        return new Result(reports, diagnostics.diagnostics());
    }

    private void appendModule(
            NativeLlvmModuleCompilation compiledModule,
            Settings settings,
            LlvmNameMangler nameMangler,
            long protectionSeed,
            List<ProtectionPassReport> reports,
            DiagnosticBag diagnostics) {
        List<IrMethod> reportMethods = compiledModule.userMethods();
        LlvmBlockLayoutPerturbationResult blockLayout =
                compiledModule.blockLayout();
        reports.add(evidenceAssembler.llvmModel(
                "LLVM_BLOCK_LAYOUT_PERTURBATION",
                settings.blockLayoutPerturbationEnabled(),
                "LLVM_BLOCK_LAYOUT_PERTURBATION",
                "LLVM_BLOCK_LAYOUT_NO_CANDIDATE",
                reportMethods,
                blockLayout.affectedFunctions(),
                blockLayout.validationIssues(),
                nameMangler,
                protectionSeed));
        evidenceAssembler.reportLlvmValidationFailure(
                diagnostics,
                "LLVM_BLOCK_LAYOUT_PERTURBATION",
                blockLayout.validationIssues());

        LlvmOpaquePredicateResult opaquePredicates =
                compiledModule.opaquePredicates();
        reports.add(evidenceAssembler.llvmModel(
                "LLVM_OPAQUE_PREDICATES",
                settings.opaquePredicatesEnabled(),
                "LLVM_OPAQUE_PREDICATES",
                "LLVM_OPAQUE_PREDICATES_NO_CANDIDATE",
                reportMethods,
                opaquePredicates.affectedFunctions(),
                opaquePredicates.validationIssues(),
                nameMangler,
                protectionSeed));
        evidenceAssembler.reportLlvmValidationFailure(
                diagnostics,
                "LLVM_OPAQUE_PREDICATES",
                opaquePredicates.validationIssues());

        LlvmIrCallIndirectionResult irCallIndirection =
                compiledModule.irCallIndirection();
        if (settings.irCallIndirectionEnabled()) {
            reports.add(evidenceAssembler.irCallIndirectionBackend(
                    reportMethods,
                    irCallIndirection,
                    nameMangler,
                    protectionSeed));
        }
        evidenceAssembler.reportLlvmValidationFailure(
                diagnostics,
                "IR_CALL_INDIRECTION_BACKEND",
                irCallIndirection.validationIssues());

        LlvmCallIndirectionResult callIndirection =
                compiledModule.llvmCallIndirection();
        reports.add(evidenceAssembler.llvmCallIndirection(
                settings.callIndirectionEnabled(),
                reportMethods,
                callIndirection,
                nameMangler,
                protectionSeed));
        LlvmGlobalLayoutResult globalLayout = compiledModule.globalLayout();
        reports.add(evidenceAssembler.llvmGlobalLayout(
                settings.globalLayoutEnabled(),
                reportMethods,
                globalLayout,
                protectionSeed));
        evidenceAssembler.reportLlvmValidationFailure(
                diagnostics,
                "LLVM_GLOBAL_LAYOUT",
                globalLayout.validationIssues());
        reports.add(evidenceAssembler.llvmNameObfuscation(
                settings.nameObfuscationEnabled(),
                reportMethods,
                compiledModule.module().functions().stream()
                        .map(function -> function.name())
                        .toList(),
                settings.nameSeed()));
    }

    record Settings(
            boolean nameObfuscationEnabled,
            boolean callIndirectionEnabled,
            boolean blockLayoutPerturbationEnabled,
            boolean opaquePredicatesEnabled,
            boolean globalLayoutEnabled,
            boolean irCallIndirectionEnabled,
            long nameSeed) {}

    record Result(
            List<ProtectionPassReport> reports,
            List<Diagnostic> diagnostics) {
        Result {
            reports = List.copyOf(reports);
            diagnostics = List.copyOf(diagnostics);
        }
    }
}
