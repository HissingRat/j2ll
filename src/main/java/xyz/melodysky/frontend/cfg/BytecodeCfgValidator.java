package xyz.melodysky.frontend.cfg;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import xyz.melodysky.diagnostic.Diagnostic;
import xyz.melodysky.diagnostic.DiagnosticLocation;
import xyz.melodysky.diagnostic.DiagnosticStage;
import xyz.melodysky.pipeline.StageValidator;

public final class BytecodeCfgValidator implements StageValidator<MethodCfgResult> {
    @Override
    public DiagnosticStage stage() {
        return DiagnosticStage.VALIDATION;
    }

    @Override
    public List<Diagnostic> validate(MethodCfgResult artifact) {
        if (artifact.cfg().isEmpty()) {
            return List.of();
        }
        BytecodeCfg cfg = artifact.cfg().orElseThrow();
        ArrayList<Diagnostic> diagnostics = new ArrayList<>();
        DiagnosticLocation location = DiagnosticLocation.methodLocation(
                cfg.method().owner(),
                cfg.method().name(),
                cfg.method().descriptor());

        if (cfg.blocks().stream().noneMatch(block -> block.startInstructionIndex() == 0)) {
            diagnostics.add(Diagnostic.error(
                            DiagnosticStage.VALIDATION,
                            CfgDiagnostics.CFG_MISSING_ENTRY,
                            "CFG has no entry block")
                    .at(location));
        }

        HashSet<Integer> blockIds = new HashSet<>();
        HashSet<Integer> coveredInstructions = new HashSet<>();
        for (BytecodeBasicBlock block : cfg.blocks()) {
            if (!blockIds.add(block.id())) {
                diagnostics.add(Diagnostic.error(
                                DiagnosticStage.VALIDATION,
                                CfgDiagnostics.CFG_DUPLICATE_BLOCK,
                                "duplicate CFG block id " + block.id())
                        .at(location));
            }
            for (int index = block.startInstructionIndex(); index < block.endInstructionIndexExclusive(); index++) {
                if (!coveredInstructions.add(index)) {
                    diagnostics.add(Diagnostic.error(
                                    DiagnosticStage.VALIDATION,
                                    CfgDiagnostics.CFG_OVERLAPPING_BLOCK,
                                    "overlapping CFG block covers instruction " + index)
                            .at(location.withInstructionOffset(index)));
                }
            }
        }

        for (BytecodeEdge edge : cfg.edges()) {
            if (!blockIds.contains(edge.toBlockId())) {
                diagnostics.add(Diagnostic.error(
                                DiagnosticStage.VALIDATION,
                                CfgDiagnostics.CFG_BAD_EDGE_TARGET,
                                "CFG edge targets unknown block " + edge.toBlockId())
                        .at(location));
            }
            if (edge.kind() == BytecodeEdgeKind.EXCEPTION) {
                boolean targetIsHandler = cfg.blocks().stream()
                        .filter(block -> block.id() == edge.toBlockId())
                        .anyMatch(BytecodeBasicBlock::isExceptionHandler);
                if (!targetIsHandler) {
                    diagnostics.add(Diagnostic.error(
                                    DiagnosticStage.VALIDATION,
                                    CfgDiagnostics.CFG_BAD_HANDLER_TARGET,
                                    "exception edge targets non-handler block " + edge.toBlockId())
                            .at(location));
                }
            }
        }

        return diagnostics;
    }
}
