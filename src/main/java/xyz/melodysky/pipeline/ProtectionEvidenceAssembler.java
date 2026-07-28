package xyz.melodysky.pipeline;

import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import xyz.melodysky.backend.llvm.LlvmNameMangler;
import xyz.melodysky.backend.llvm.protection.LlvmCallIndirectionResult;
import xyz.melodysky.backend.llvm.protection.LlvmGlobalLayoutResult;
import xyz.melodysky.backend.llvm.protection.LlvmIrCallIndirectionResult;
import xyz.melodysky.diagnostic.Diagnostic;
import xyz.melodysky.diagnostic.DiagnosticBag;
import xyz.melodysky.diagnostic.DiagnosticCode;
import xyz.melodysky.diagnostic.DiagnosticSeverity;
import xyz.melodysky.diagnostic.DiagnosticStage;
import xyz.melodysky.ir.model.IrMethod;
import xyz.melodysky.packaging.MethodTableHidingPlan;
import xyz.melodysky.protection.audit.ProtectionApplicability;
import xyz.melodysky.protection.audit.ProtectionPassCoverageFact;
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
                    Long.toString(seed),
                    List.of(),
                    llvmMethodFacts(
                            "IR_CALL_INDIRECTION_BACKEND",
                            true,
                            methods,
                            List.of(),
                            true,
                            llvmNameMangler,
                            "IR_CALL_INDIRECTION_TABLE_EMITTED",
                            "IR_CALL_INDIRECTION_BACKEND_NO_CANDIDATE",
                            "IR_CALL_INDIRECTION_BACKEND_VALIDATION_FAILED"));
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
                Long.toString(seed),
                List.of(),
                llvmMethodFacts(
                        "IR_CALL_INDIRECTION_BACKEND",
                        true,
                        methods,
                        result.affectedFunctions(),
                        false,
                        llvmNameMangler,
                        "IR_CALL_INDIRECTION_TABLE_EMITTED",
                        "IR_CALL_INDIRECTION_BACKEND_NO_CANDIDATE",
                        "IR_CALL_INDIRECTION_BACKEND_VALIDATION_FAILED"));
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
                                .flatMap(owner -> owner.registrationOrder().stream())
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
                "METHOD_TABLE_HIDING_TRANSIENT_OWNER_LAYOUT",
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
        String status;
        String reasonCode;
        ProtectionApplicability applicability;
        boolean affected = false;
        if (!enabled) {
            status = "SKIPPED";
            reasonCode = "PROTECTION_PASS_DISABLED";
            applicability = ProtectionApplicability.UNKNOWN;
        } else if (!result.validationIssues().isEmpty()) {
            status = "FAILED";
            reasonCode = "LLVM_MODEL_VALIDATION_FAILED";
            applicability = ProtectionApplicability.UNKNOWN;
        } else {
            affected = !result.affectedGlobals().isEmpty();
            status = affected ? "RAN" : "SKIPPED";
            reasonCode = affected
                    ? "LLVM_GLOBAL_LAYOUT"
                    : "LLVM_GLOBAL_LAYOUT_NO_CANDIDATE";
            applicability = affected
                    ? ProtectionApplicability.APPLICABLE
                    : ProtectionApplicability.NOT_APPLICABLE;
        }
        return new ProtectionPassReport(
                "LLVM_GLOBAL_LAYOUT",
                "LLVM",
                status,
                reasonCode,
                List.of(),
                affected ? result.affectedGlobals() : List.of(),
                Long.toString(seed),
                List.of(),
                List.of(ProtectionCoverageFacts.subject(
                        "LLVM",
                        "LLVM_GLOBAL_LAYOUT",
                        "protection-report-llvm-module-subject",
                        methods.stream()
                                .map(IrMethod::methodKey)
                                .sorted()
                                .collect(java.util.stream.Collectors.joining("\0")),
                        enabled,
                        applicability,
                        affected,
                        status,
                        reasonCode)));
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
                    Long.toString(seed),
                    List.of(),
                    llvmMethodFacts(
                            passName,
                            false,
                            methods,
                            List.of(),
                            false,
                            llvmNameMangler,
                            ranReason,
                            noCandidateReason,
                            "LLVM_MODEL_VALIDATION_FAILED"));
        }
        if (!validationIssues.isEmpty()) {
            return new ProtectionPassReport(
                    passName,
                    "LLVM",
                    "FAILED",
                    "LLVM_MODEL_VALIDATION_FAILED",
                    methods.stream().map(IrMethod::methodKey).toList(),
                    List.of(),
                    Long.toString(seed),
                    List.of(),
                    llvmMethodFacts(
                            passName,
                            true,
                            methods,
                            List.of(),
                            true,
                            llvmNameMangler,
                            ranReason,
                            noCandidateReason,
                            "LLVM_MODEL_VALIDATION_FAILED"));
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
                Long.toString(seed),
                List.of(),
                llvmMethodFacts(
                        passName,
                        true,
                        methods,
                        affectedFunctions,
                        false,
                        llvmNameMangler,
                        ranReason,
                        noCandidateReason,
                        "LLVM_MODEL_VALIDATION_FAILED"));
    }

    ProtectionPassReport llvmCallIndirection(
            boolean enabled,
            List<IrMethod> methods,
            LlvmCallIndirectionResult result,
            LlvmNameMangler llvmNameMangler,
            long seed) {
        List<String> affectedMethods = methods.stream()
                .filter(method -> result.affectedFunctions().contains(
                        llvmNameMangler.functionName(method)))
                .map(IrMethod::methodKey)
                .toList();
        String ranReason = result.reasonCode();
        String noCandidateReason = result.reasonCode();
        return new ProtectionPassReport(
                "CALL_INDIRECTION",
                "LLVM",
                enabled && result.changed() ? "RAN" : "SKIPPED",
                enabled
                        ? result.reasonCode()
                        : "PROTECTION_PASS_DISABLED",
                enabled ? affectedMethods
                        : methods.stream().map(IrMethod::methodKey).toList(),
                enabled ? result.dispatcherSymbols() : List.of(),
                Long.toString(seed),
                List.of(),
                llvmMethodFacts(
                        "CALL_INDIRECTION",
                        enabled,
                        methods,
                        enabled ? result.affectedFunctions() : List.of(),
                        false,
                        llvmNameMangler,
                        ranReason,
                        noCandidateReason,
                        "LLVM_MODEL_VALIDATION_FAILED"));
    }

    ProtectionPassReport llvmNameObfuscation(
            boolean enabled,
            List<IrMethod> methods,
            List<String> affectedSymbols,
            long seed) {
        return new ProtectionPassReport(
                "LLVM_NAME_OBFUSCATION",
                "LLVM",
                enabled ? "RAN" : "SKIPPED",
                enabled ? "OK" : "PROTECTION_PASS_DISABLED",
                methods.stream().map(IrMethod::methodKey).toList(),
                enabled ? affectedSymbols : List.of(),
                Long.toString(seed),
                List.of(),
                uniformLlvmFacts(
                        "LLVM_NAME_OBFUSCATION",
                        methods,
                        enabled,
                        enabled
                                ? ProtectionApplicability.APPLICABLE
                                : ProtectionApplicability.UNKNOWN,
                        enabled,
                        enabled ? "RAN" : "SKIPPED",
                        enabled ? "OK" : "PROTECTION_PASS_DISABLED"));
    }

    private List<ProtectionPassCoverageFact> llvmMethodFacts(
            String passName,
            boolean enabled,
            List<IrMethod> methods,
            List<String> affectedFunctions,
            boolean validationFailed,
            LlvmNameMangler llvmNameMangler,
            String ranReason,
            String noCandidateReason,
            String validationReason) {
        if (!enabled) {
            return uniformLlvmFacts(
                    passName,
                    methods,
                    false,
                    ProtectionApplicability.UNKNOWN,
                    false,
                    "SKIPPED",
                    "PROTECTION_PASS_DISABLED");
        }
        if (validationFailed) {
            return uniformLlvmFacts(
                    passName,
                    methods,
                    true,
                    ProtectionApplicability.UNKNOWN,
                    false,
                    "FAILED",
                    validationReason);
        }
        return methods.stream()
                .map(method -> {
                    boolean affected = affectedFunctions.contains(
                            llvmNameMangler.functionName(method));
                    return ProtectionCoverageFacts.method(
                            "LLVM",
                            passName,
                            method.methodKey(),
                            true,
                            affected
                                    ? ProtectionApplicability.APPLICABLE
                                    : ProtectionApplicability.NOT_APPLICABLE,
                            affected,
                            affected ? "RAN" : "SKIPPED",
                            affected ? ranReason : noCandidateReason);
                })
                .toList();
    }

    private List<ProtectionPassCoverageFact> uniformLlvmFacts(
            String passName,
            List<IrMethod> methods,
            boolean requested,
            ProtectionApplicability applicability,
            boolean affected,
            String status,
            String reasonCode) {
        return ProtectionCoverageFacts.uniformMethods(
                "LLVM",
                passName,
                methods.stream().map(IrMethod::methodKey).toList(),
                requested,
                applicability,
                affected,
                status,
                reasonCode);
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
