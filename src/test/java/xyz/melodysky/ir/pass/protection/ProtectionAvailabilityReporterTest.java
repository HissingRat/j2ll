package xyz.melodysky.ir.pass.protection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import xyz.melodysky.config.BinaryProtectionConfig;
import xyz.melodysky.config.IrProtectionConfig;
import xyz.melodysky.config.LlvmProtectionConfig;
import xyz.melodysky.ir.pass.PassDiagnostics;

class ProtectionAvailabilityReporterTest {
    @Test
    void warnsForEnabledPassesThatAreNotImplemented() {
        var diagnostics = new ProtectionAvailabilityReporter(
                        Set.of("basicBlockSplitting"),
                        Set.of("nameObfuscation"))
                .report(config());

        assertTrue(diagnostics.stream()
                .anyMatch(diagnostic -> diagnostic.code().equals(PassDiagnostics.PROTECTION_PASS_NOT_IMPLEMENTED)
                        && diagnostic.message().contains("controlFlowFlattening")));
        assertTrue(diagnostics.stream()
                .anyMatch(diagnostic -> diagnostic.code().equals(PassDiagnostics.PROTECTION_PASS_NOT_IMPLEMENTED)
                        && diagnostic.message().contains("fieldInternalization")));
        assertTrue(diagnostics.stream()
                .noneMatch(diagnostic -> diagnostic.message().contains("basicBlockSplitting")));
        assertTrue(diagnostics.stream()
                .noneMatch(diagnostic -> diagnostic.message().contains("nameObfuscation")));
    }

    @Test
    void disabledRootProtectionDoesNotWarn() {
        var disabled = new xyz.melodysky.config.ProtectionConfig(
                false,
                "seed",
                config().ir(),
                config().llvm(),
                config().binary());

        assertFalse(new ProtectionAvailabilityReporter(Set.of(), Set.of()).report(disabled).iterator().hasNext());
    }

    @Test
    void currentImplementationDoesNotWarnForImplementedPasses() {
        var diagnostics = ProtectionAvailabilityReporter.currentImplementation().report(config());

        assertEquals(List.of(), diagnostics);
    }

    private xyz.melodysky.config.ProtectionConfig config() {
        return new xyz.melodysky.config.ProtectionConfig(
                true,
                "seed",
                new IrProtectionConfig(
                        true,
                        true,
                        true,
                        true,
                        true,
                        true,
                        true,
                        true,
                        true,
                        true,
                        true,
                        true),
                new LlvmProtectionConfig(
                        true,
                        true,
                        true,
                        true,
                        true,
                        true),
                new BinaryProtectionConfig(true, true, true, true, true));
    }
}
