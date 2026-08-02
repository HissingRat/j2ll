package xyz.melodysky.pipeline;

import java.util.ArrayList;
import java.util.List;
import xyz.melodysky.analysis.field.NativeFieldInternalizationPlan;
import xyz.melodysky.diagnostic.Diagnostic;
import xyz.melodysky.diagnostic.DiagnosticCode;
import xyz.melodysky.diagnostic.DiagnosticStage;
import xyz.melodysky.ir.model.NativeFieldSlotRef;
import xyz.melodysky.toolchain.NativeImplementationPath;
import xyz.melodysky.toolchain.NativeImplementationPlan;

/**
 * Rechecks the immutable field plan against the final implementation paths.
 *
 * <p>The initial plan is necessarily built before native-slot opcodes exist.
 * This boundary prevents a later planner decision from leaving a field
 * removed while one of its accessors no longer has an LLVM slot
 * implementation.</p>
 */
public final class FieldInternalizationFinalPlanValidator {
    private static final DiagnosticCode FINAL_PLAN_MISMATCH =
            DiagnosticCode.of("FIELD_INTERNALIZATION_FINAL_PLAN_MISMATCH");

    public List<Diagnostic> validate(
            NativeFieldInternalizationPlan fieldPlan,
            NativeImplementationPlan implementationPlan) {
        ArrayList<Diagnostic> diagnostics = new ArrayList<>();
        for (var decision : fieldPlan.nativeStoredFields()) {
            String slot = decision.nativeSlotId().orElseThrow();
            String slotMarker = "native-slot:" + new NativeFieldSlotRef(
                            fieldPlan.storageKind(decision),
                            slot,
                            fieldPlan.referenceIndex(decision))
                    .encoded();
            for (String methodKey : decision.accesses().stream()
                    .map(access -> access.methodKey())
                    .distinct()
                    .sorted()
                    .toList()) {
                var implementation = implementationPlan.implementationFor(methodKey);
                if (implementation.isEmpty()) {
                    diagnostics.add(error(
                            "final native implementation is missing for "
                                    + methodKey + " (slot " + slot + ")"));
                    continue;
                }
                var resolved = implementation.orElseThrow();
                boolean llvmPath = resolved.path()
                        == NativeImplementationPath.LLVM_NATIVE_PATH;
                if (!llvmPath) {
                    diagnostics.add(error(
                            "final native implementation is not LLVM_NATIVE_PATH for "
                                    + methodKey + " (slot " + slot + ")"));
                    continue;
                }
                if (!resolved.fieldKeys().contains(slotMarker)) {
                    diagnostics.add(error(
                            "final native implementation does not consume "
                                    + slotMarker + " in " + methodKey));
                }
                if (resolved.fieldKeys().contains(decision.field().fieldKey())) {
                    diagnostics.add(error(
                            "raw JVM field access survived final planning in "
                                    + methodKey + " (slot " + slot + ")"));
                }
            }
        }
        for (var decision : fieldPlan.constantFoldedFields()) {
            for (String methodKey : decision.accesses().stream()
                    .map(access -> access.methodKey())
                    .distinct()
                    .sorted()
                    .toList()) {
                var implementation = implementationPlan.implementationFor(methodKey);
                if (implementation.isEmpty()) {
                    diagnostics.add(error(
                            "final native implementation is missing for constant-folded field accessor "
                                    + methodKey));
                    continue;
                }
                var resolved = implementation.orElseThrow();
                if (resolved.path() != NativeImplementationPath.LLVM_NATIVE_PATH) {
                    diagnostics.add(error(
                            "final native implementation is not LLVM_NATIVE_PATH for "
                                    + "constant-folded field accessor " + methodKey));
                    continue;
                }
                if (resolved.fieldKeys().contains(decision.field().fieldKey())) {
                    diagnostics.add(error(
                            "raw JVM constant field access survived final planning in "
                                    + methodKey));
                }
            }
        }
        return List.copyOf(diagnostics);
    }

    private Diagnostic error(String message) {
        return Diagnostic.error(
                DiagnosticStage.PROTECTION,
                FINAL_PLAN_MISMATCH,
                message);
    }
}
