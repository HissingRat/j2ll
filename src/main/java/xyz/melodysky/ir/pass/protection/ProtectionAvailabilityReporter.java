package xyz.melodysky.ir.pass.protection;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import xyz.melodysky.config.PassConfig;
import xyz.melodysky.diagnostic.Diagnostic;
import xyz.melodysky.diagnostic.DiagnosticStage;
import xyz.melodysky.ir.pass.PassDiagnostics;

public final class ProtectionAvailabilityReporter {
    private final Set<String> implementedIrPasses;
    private final Set<String> implementedLlvmPasses;

    public ProtectionAvailabilityReporter(Set<String> implementedIrPasses, Set<String> implementedLlvmPasses) {
        this.implementedIrPasses = Set.copyOf(implementedIrPasses);
        this.implementedLlvmPasses = Set.copyOf(implementedLlvmPasses);
    }

    public static ProtectionAvailabilityReporter currentImplementation() {
        return new ProtectionAvailabilityReporter(
                Set.of("controlFlowFlattening", "fakeBranches", "basicBlockSplitting", "constantEncryption", "stringEncryption"),
                Set.of("nameObfuscation", "indirectCalls", "visibilityHardening"));
    }

    public List<Diagnostic> report(xyz.melodysky.config.ProtectionConfig config) {
        ArrayList<Diagnostic> diagnostics = new ArrayList<>();
        if (!config.enabled()) {
            return diagnostics;
        }
        if (config.ir().enabled()) {
            warnIfMissing(diagnostics, "controlFlowFlattening", config.ir().controlFlowFlattening(), implementedIrPasses);
            warnIfMissing(diagnostics, "fakeBranches", config.ir().fakeBranches(), implementedIrPasses);
            warnIfMissing(diagnostics, "basicBlockSplitting", config.ir().basicBlockSplitting(), implementedIrPasses);
            warnIfMissing(diagnostics, "constantEncryption", config.ir().constantEncryption(), implementedIrPasses);
            warnIfMissing(diagnostics, "stringEncryption", config.ir().stringEncryption().enabled(), implementedIrPasses);
            warnIfMissing(diagnostics, "methodInlining", config.ir().methodInlining(), implementedIrPasses);
            warnIfMissing(diagnostics, "methodSplitting", config.ir().methodSplitting(), implementedIrPasses);
            warnIfMissing(diagnostics, "callIndirection", config.ir().callIndirection(), implementedIrPasses);
            warnIfMissing(diagnostics, "methodTableHiding", config.ir().methodTableHiding(), implementedIrPasses);
        }
        if (config.llvm().enabled()) {
            warnIfMissing(diagnostics, "nameObfuscation", config.llvm().nameObfuscation(), implementedLlvmPasses);
            warnIfMissing(diagnostics, "opaquePredicates", config.llvm().opaquePredicates(), implementedLlvmPasses);
            warnIfMissing(diagnostics, "blockLayoutPerturbation", config.llvm().blockLayoutPerturbation(), implementedLlvmPasses);
            warnIfMissing(diagnostics, "indirectCalls", config.llvm().indirectCalls(), implementedLlvmPasses);
            warnIfMissing(diagnostics, "globalLayout", config.llvm().globalLayout(), implementedLlvmPasses);
            warnIfMissing(diagnostics, "visibilityHardening", config.llvm().visibilityHardening().enabled(), implementedLlvmPasses);
        }
        return List.copyOf(diagnostics);
    }

    private void warnIfMissing(
            List<Diagnostic> diagnostics,
            String passName,
            PassConfig passConfig,
            Set<String> implementedPasses) {
        warnIfMissing(diagnostics, passName, passConfig.enabled(), implementedPasses);
    }

    private void warnIfMissing(
            List<Diagnostic> diagnostics,
            String passName,
            boolean enabled,
            Set<String> implementedPasses) {
        if (enabled && !implementedPasses.contains(passName)) {
            diagnostics.add(Diagnostic.warning(
                    DiagnosticStage.PROTECTION,
                    PassDiagnostics.PROTECTION_PASS_NOT_IMPLEMENTED,
                    "protection pass enabled but not implemented yet: " + passName));
        }
    }
}
