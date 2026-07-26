package xyz.melodysky.pipeline;

import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import xyz.melodysky.backend.llvm.LlvmNameMangler;
import xyz.melodysky.backend.llvm.protection.LlvmGlobalLayoutResult;
import xyz.melodysky.backend.llvm.protection.LlvmIrCallIndirectionResult;
import xyz.melodysky.diagnostic.Diagnostic;
import xyz.melodysky.diagnostic.DiagnosticBag;
import xyz.melodysky.diagnostic.DiagnosticCode;
import xyz.melodysky.diagnostic.DiagnosticSeverity;
import xyz.melodysky.diagnostic.DiagnosticStage;
import xyz.melodysky.ir.model.IrMethod;
import xyz.melodysky.packaging.MethodTableHidingPlan;
import xyz.melodysky.report.ProtectionPassReport;
import xyz.melodysky.toolchain.NativeImplementationPlan;
import xyz.melodysky.toolchain.NativeMethodImplementation;

/**
 * Turns validated protection-stage results into stable report and diagnostic
 * evidence. Keeping this translation outside {@link MainlinePipeline} leaves
 * the pipeline responsible for stage ordering rather than wire-format policy.
 */
final class ProtectionEvidenceAssembler {
    ProtectionPassReport irCallIndirectionBackend(
            List<IrMethod> methods,
            LlvmIrCallIndirectionResult result,
            LlvmNameMangler llvmNameMangler,
            long seed) {
        List<String> affectedMethods = methods.stream()
                .filter(method -> result.affectedFunctions().contains(
                        llvmNameMangler.functionName(method)))
                .map(IrMethod::methodKey)
                .toList();
        if (!result.validationIssues().isEmpty()) {
            return new ProtectionPassReport(
                    "IR_CALL_INDIRECTION_BACKEND",
                    "LLVM",
                    "FAILED",
                    "IR_CALL_INDIRECTION_BACKEND_VALIDATION_FAILED",
                    methods.stream().map(IrMethod::methodKey).toList(),
                    List.of(),
                    Long.toString(seed));
        }
        return new ProtectionPassReport(
                "IR_CALL_INDIRECTION_BACKEND",
                "LLVM",
                result.changed() ? "RAN" : "SKIPPED",
                result.changed()
                        ? "IR_CALL_INDIRECTION_TABLE_EMITTED"
                        : "IR_CALL_INDIRECTION_BACKEND_NO_CANDIDATE",
                affectedMethods,
                result.tableSymbols(),
                Long.toString(seed));
    }

    ProtectionPassReport methodTableHiding(
            boolean enabled,
            NativeImplementationPlan implementationPlan,
            MethodTableHidingPlan plan,
            long seed) {
        List<String> affectedMethods = implementationPlan.implementations().stream()
                .map(NativeMethodImplementation::methodKey)
                .sorted()
                .toList();
        if (!enabled) {
            return new ProtectionPassReport(
                    "METHOD_TABLE_HIDING",
                    "PACKAGING_NATIVE_REGISTRATION",
                    "SKIPPED",
                    "PROTECTION_PASS_DISABLED",
                    affectedMethods,
                    List.of(),
                    Long.toString(seed));
        }
        if (!plan.changed()) {
            return new ProtectionPassReport(
                    "METHOD_TABLE_HIDING",
                    "PACKAGING_NATIVE_REGISTRATION",
                    "SKIPPED",
                    "METHOD_TABLE_HIDING_NO_CANDIDATE",
                    affectedMethods,
                    List.of(),
                    Long.toString(seed));
        }
        List<String> opaqueEvidence = Stream.concat(
                        Stream.of(plan.planId()),
                        plan.owners().stream()
                                .flatMap(owner -> owner.metadataOrder().stream())
                                .map(entry -> "mth_" + String.format(
                                        Locale.ROOT,
                                        "%016x",
                                        entry.token())))
                .sorted()
                .toList();
        return new ProtectionPassReport(
                "METHOD_TABLE_HIDING",
                "PACKAGING_NATIVE_REGISTRATION",
                "RAN",
                "METHOD_TABLE_HIDING_SPLIT_TOKEN_TABLE",
                affectedMethods,
                opaqueEvidence,
                Long.toString(seed));
    }

    ProtectionPassReport fieldInternalization(
            FieldInternalizationPipelineResult result,
            List<Diagnostic> finalPlanDiagnostics,
            long seed) {
        List<Diagnostic> failures = Stream.concat(
                        result.diagnostics().stream(),
                        finalPlanDiagnostics.stream())
                .filter(diagnostic -> diagnostic.severity() == DiagnosticSeverity.ERROR)
                .sorted()
                .toList();
        if (failures.isEmpty()) {
            return result.protectionReport();
        }
        return new ProtectionPassReport(
                "FIELD_INTERNALIZATION",
                "PROGRAM_IR",
                "FAILED",
                failures.get(0).code().value(),
                result.plan().internalizedFields().stream()
                        .flatMap(decision -> decision.accesses().stream())
                        .map(access -> access.methodKey())
                        .toList(),
                result.plan().internalizedFields().stream()
                        .flatMap(decision -> decision.nativeSlotId().stream())
                        .toList(),
                Long.toString(seed));
    }

    ProtectionPassReport llvmGlobalLayout(
            boolean enabled,
            List<IrMethod> methods,
            LlvmGlobalLayoutResult result,
            long seed) {
        if (!enabled) {
            return new ProtectionPassReport(
                    "LLVM_GLOBAL_LAYOUT",
                    "LLVM",
                    "SKIPPED",
                    "PROTECTION_PASS_DISABLED",
                    methods.stream().map(IrMethod::methodKey).toList(),
                    List.of(),
                    Long.toString(seed));
        }
        if (!result.validationIssues().isEmpty()) {
            return new ProtectionPassReport(
                    "LLVM_GLOBAL_LAYOUT",
                    "LLVM",
                    "FAILED",
                    "LLVM_MODEL_VALIDATION_FAILED",
                    methods.stream().map(IrMethod::methodKey).toList(),
                    List.of(),
                    Long.toString(seed));
        }
        return new ProtectionPassReport(
                "LLVM_GLOBAL_LAYOUT",
                "LLVM",
                result.affectedGlobals().isEmpty() ? "SKIPPED" : "RAN",
                result.affectedGlobals().isEmpty()
                        ? "LLVM_GLOBAL_LAYOUT_NO_CANDIDATE"
                        : "LLVM_GLOBAL_LAYOUT",
                result.affectedGlobals().isEmpty()
                        ? List.of()
                        : methods.stream().map(IrMethod::methodKey).toList(),
                result.affectedGlobals(),
                Long.toString(seed));
    }

    ProtectionPassReport llvmModel(
            String passName,
            boolean enabled,
            String ranReason,
            String noCandidateReason,
            List<IrMethod> methods,
            List<String> affectedFunctions,
            List<String> validationIssues,
            LlvmNameMangler llvmNameMangler,
            long seed) {
        if (!enabled) {
            return new ProtectionPassReport(
                    passName,
                    "LLVM",
                    "SKIPPED",
                    "PROTECTION_PASS_DISABLED",
                    methods.stream().map(IrMethod::methodKey).toList(),
                    List.of(),
                    Long.toString(seed));
        }
        if (!validationIssues.isEmpty()) {
            return new ProtectionPassReport(
                    passName,
                    "LLVM",
                    "FAILED",
                    "LLVM_MODEL_VALIDATION_FAILED",
                    methods.stream().map(IrMethod::methodKey).toList(),
                    List.of(),
                    Long.toString(seed));
        }
        List<String> affectedMethods = methods.stream()
                .filter(method -> affectedFunctions.contains(llvmNameMangler.functionName(method)))
                .map(IrMethod::methodKey)
                .toList();
        return new ProtectionPassReport(
                passName,
                "LLVM",
                affectedFunctions.isEmpty() ? "SKIPPED" : "RAN",
                affectedFunctions.isEmpty() ? noCandidateReason : ranReason,
                affectedMethods,
                affectedFunctions,
                Long.toString(seed));
    }

    void reportLlvmValidationFailure(
            DiagnosticBag diagnostics,
            String passName,
            List<String> validationIssues) {
        if (validationIssues.isEmpty()) {
            return;
        }
        diagnostics.add(Diagnostic.error(
                DiagnosticStage.LLVM_PROTECTION,
                DiagnosticCode.of("LLVM_MODEL_VALIDATION_FAILED"),
                passName + " rejected an invalid LLVM module: "
                        + String.join("; ", validationIssues)));
    }
}
