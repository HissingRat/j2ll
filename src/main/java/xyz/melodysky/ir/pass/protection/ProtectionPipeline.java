package xyz.melodysky.ir.pass.protection;

import java.util.List;
import java.util.ArrayList;
import xyz.melodysky.diagnostic.Diagnostic;
import xyz.melodysky.diagnostic.DiagnosticLocation;
import xyz.melodysky.diagnostic.DiagnosticStage;
import xyz.melodysky.ir.model.IrMethod;
import xyz.melodysky.ir.model.IrOpcode;
import xyz.melodysky.ir.pass.PassDiagnostics;
import xyz.melodysky.ir.validate.IrMethodValidator;
import xyz.melodysky.pipeline.StageResult;
import xyz.melodysky.report.ProtectionPassReport;
import xyz.melodysky.report.SensitivePlaintextFact;

public final class ProtectionPipeline {
    private final List<ProtectionPass> passes;

    public ProtectionPipeline(List<ProtectionPass> passes) {
        this.passes = List.copyOf(passes);
    }

    public static ProtectionPipeline defaultPipeline() {
        return new ProtectionPipeline(List.of(
                new StringEncryptionPass(),
                new ControlFlowFlatteningPass(),
                new BasicBlockSplittingPass(),
                new PrimitiveConstantEncryptionPass(),
                new BlockNameObfuscationPass()));
    }

    public IrMethod run(IrMethod method, ProtectionConfig config) {
        return runWithDiagnostics(method, config).artifact().orElse(method);
    }

    public StageResult<IrMethod> runWithDiagnostics(IrMethod method, ProtectionConfig config) {
        ProtectionPipelineResult result = runDetailed(method, config);
        return StageResult.complete(DiagnosticStage.PROTECTION, result.method(), result.diagnostics());
    }

    public ProtectionPipelineResult runDetailed(IrMethod method, ProtectionConfig config) {
        ArrayList<Diagnostic> diagnostics = new ArrayList<>();
        ArrayList<ProtectionPassReport> reports = new ArrayList<>();
        if (config.enabled() && isMonitorSensitive(method)) {
            Diagnostic diagnostic = Diagnostic.warning(
                            DiagnosticStage.PROTECTION,
                            PassDiagnostics.PROTECTION_MONITOR_SENSITIVE_SKIP,
                            "IR protection skipped monitor-sensitive method: " + method.methodKey())
                    .at(DiagnosticLocation.methodLocation(method.owner(), method.name(), method.descriptor()));
            diagnostics.add(diagnostic);
            for (ProtectionPass pass : passes) {
                reports.add(report(pass.name(), "SKIPPED", "PROTECTION_MONITOR_SENSITIVE_SKIP", method, config));
            }
            return new ProtectionPipelineResult(method, diagnostics, reports);
        }
        IrMethod current = method;
        for (ProtectionPass pass : passes) {
            if (!pass.enabled(config)) {
                reports.add(report(pass.name(), "SKIPPED", "PROTECTION_PASS_DISABLED", current, config));
                continue;
            }
            if (!pass.applicable(current)) {
                reports.add(report(pass.name(), "SKIPPED", pass.skipReasonCode(current), current, config));
                diagnostics.add(Diagnostic.warning(
                                DiagnosticStage.PROTECTION,
                                PassDiagnostics.PROTECTION_PASS_NOT_APPLICABLE,
                                "IR protection pass skipped method: " + pass.name() + " -> " + current.methodKey())
                        .at(DiagnosticLocation.methodLocation(current.owner(), current.name(), current.descriptor()))
                        .withDecision(pass.skipReasonCode(current)));
                continue;
            }
            IrMethod before = current;
            current = pass.run(current, config);
            List<Diagnostic> validation = new IrMethodValidator().validate(current);
            diagnostics.addAll(validation);
            String status = validation.stream().anyMatch(diagnostic -> diagnostic.severity().wireName().equals("error"))
                    ? "FAILED"
                    : "RAN";
            reports.add(report(pass.name(), status, status.equals("RAN")
                    ? ranReasonCode(pass.name(), current)
                    : "PASS_VALIDATION_FAILED", before, config, sensitivePlaintextFacts(pass, before, status)));
        }
        return new ProtectionPipelineResult(current, diagnostics, reports);
    }

    private String ranReasonCode(String passName, IrMethod method) {
        if (passName.equals("CONSTANT_ENCRYPTION")) {
            boolean hasFloat = method.blocks().stream()
                    .flatMap(block -> block.instructions().stream())
                    .anyMatch(instruction -> instruction.opcode() == IrOpcode.BITCAST_I32_TO_F32);
            boolean hasDouble = method.blocks().stream()
                    .flatMap(block -> block.instructions().stream())
                    .anyMatch(instruction -> instruction.opcode() == IrOpcode.BITCAST_I64_TO_F64);
            if (hasFloat) {
                return "FLOAT_CONSTANT_ENCRYPTION";
            }
            if (hasDouble) {
                return "DOUBLE_CONSTANT_ENCRYPTION";
            }
        }
        if (passName.equals("CONTROL_FLOW_FLATTENING")) {
            return "CONTROL_FLOW_FLATTENING";
        }
        return "OK";
    }

    private ProtectionPassReport report(
            String passName,
            String status,
            String reasonCode,
            IrMethod method,
            ProtectionConfig config) {
        return report(passName, status, reasonCode, method, config, List.of());
    }

    private ProtectionPassReport report(
            String passName,
            String status,
            String reasonCode,
            IrMethod method,
            ProtectionConfig config,
            List<SensitivePlaintextFact> sensitivePlaintextFacts) {
        return new ProtectionPassReport(
                passName,
                "IR",
                status,
                reasonCode,
                List.of(method.methodKey()),
                List.of(),
                Long.toString(config.seed()),
                sensitivePlaintextFacts);
    }

    private List<SensitivePlaintextFact> sensitivePlaintextFacts(
            ProtectionPass pass,
            IrMethod method,
            String status) {
        if (!status.equals("RAN") || !(pass instanceof StringEncryptionPass stringEncryptionPass)) {
            return List.of();
        }
        return stringEncryptionPass.sensitivePlaintextFacts(method);
    }

    private boolean isMonitorSensitive(IrMethod method) {
        return method.blocks().stream()
                .flatMap(block -> block.instructions().stream())
                .map(instruction -> instruction.opcode())
                .anyMatch(opcode -> opcode == IrOpcode.MONITOR_ENTER
                        || opcode == IrOpcode.MONITOR_EXIT
                        || opcode == IrOpcode.MONITOR_EXIT_ON_EXCEPTION
                        || opcode == IrOpcode.MONITOR_HAPPENS_BEFORE);
    }
}
