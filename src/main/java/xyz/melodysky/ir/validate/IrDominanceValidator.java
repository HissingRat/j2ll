package xyz.melodysky.ir.validate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import xyz.melodysky.diagnostic.Diagnostic;
import xyz.melodysky.diagnostic.DiagnosticLocation;
import xyz.melodysky.diagnostic.DiagnosticStage;
import xyz.melodysky.ir.model.IrBlock;
import xyz.melodysky.ir.model.IrExceptionEdge;
import xyz.melodysky.ir.model.IrMethod;
import xyz.melodysky.ir.model.IrTerminator;
import xyz.melodysky.ir.model.IrValue;

final class IrDominanceValidator {
    List<Diagnostic> validate(
            IrMethod method,
            Map<String, IrBlock> blocksByName,
            DiagnosticLocation location) {
        ArrayList<Diagnostic> diagnostics = new ArrayList<>();
        IrControlFlowGraph graph =
                IrControlFlowGraph.analyze(method, blocksByName);
        for (IrBlock block : method.blocks()) {
            if (!graph.isReachable(block.name())) {
                diagnostics.add(Diagnostic.error(
                                DiagnosticStage.VALIDATION,
                                IrValidationDiagnostics.IR_UNREACHABLE_BLOCK,
                                "IR block is unreachable from method entry: " + block.name())
                        .at(location));
            }
        }

        Map<IrValue, List<Definition>> definitions = definitions(method);
        for (IrBlock block : method.blocks()) {
            if (!graph.isReachable(block.name())) {
                continue;
            }
            validateBlockUses(
                    block,
                    definitions,
                    graph,
                    location,
                    diagnostics);
        }
        return List.copyOf(diagnostics);
    }

    private void validateBlockUses(
            IrBlock block,
            Map<IrValue, List<Definition>> definitions,
            IrControlFlowGraph graph,
            DiagnosticLocation location,
            List<Diagnostic> diagnostics) {
        for (int instructionIndex = 0;
                instructionIndex < block.instructions().size();
                instructionIndex++) {
            var instruction = block.instructions().get(instructionIndex);
            UsePoint point = UsePoint.beforeInstruction(block.name(), instructionIndex);
            for (IrValue operand : instruction.operands()) {
                validateUse(
                        operand,
                        point,
                        definitions,
                        graph,
                        "instruction operand",
                        location,
                        diagnostics);
            }
            for (int siteIndex = 0;
                    siteIndex < instruction.exceptionSites().size();
                    siteIndex++) {
                var site = instruction.exceptionSites().get(siteIndex);
                UsePoint handlerPoint =
                        UsePoint.exceptionHandlerArgument(block.name(), instructionIndex, siteIndex);
                for (IrExceptionEdge edge : site.handlers()) {
                    validateUses(
                            edge.arguments(),
                            handlerPoint,
                            definitions,
                            graph,
                            "exception-site handler argument",
                            location,
                            diagnostics);
                }
            }
        }

        UsePoint terminatorPoint =
                UsePoint.terminator(block.name(), block.instructions().size());
        IrTerminator terminator = block.terminator();
        terminator.value().ifPresent(value -> validateUse(
                value,
                terminatorPoint,
                definitions,
                graph,
                "terminator value",
                location,
                diagnostics));
        terminator.condition().ifPresent(value -> validateUse(
                value,
                terminatorPoint,
                definitions,
                graph,
                "terminator condition",
                location,
                diagnostics));
        terminator.switchValue().ifPresent(value -> validateUse(
                value,
                terminatorPoint,
                definitions,
                graph,
                "switch selector",
                location,
                diagnostics));
        validateUses(
                terminator.targetArguments(),
                terminatorPoint,
                definitions,
                graph,
                "terminator target argument",
                location,
                diagnostics);
        validateUses(
                terminator.trueTargetArguments(),
                terminatorPoint,
                definitions,
                graph,
                "terminator target argument",
                location,
                diagnostics);
        validateUses(
                terminator.falseTargetArguments(),
                terminatorPoint,
                definitions,
                graph,
                "terminator target argument",
                location,
                diagnostics);
        validateUses(
                terminator.defaultTargetArguments(),
                terminatorPoint,
                definitions,
                graph,
                "terminator target argument",
                location,
                diagnostics);
        for (var switchCase : terminator.switchCases()) {
            validateUses(
                    switchCase.arguments(),
                    terminatorPoint,
                    definitions,
                    graph,
                    "terminator target argument",
                    location,
                    diagnostics);
        }
        for (IrExceptionEdge edge : block.exceptionEdges()) {
            validateUses(
                    edge.arguments(),
                    terminatorPoint,
                    definitions,
                    graph,
                    "exception-edge argument",
                    location,
                    diagnostics);
        }
    }

    private void validateUses(
            List<IrValue> values,
            UsePoint point,
            Map<IrValue, List<Definition>> definitions,
            IrControlFlowGraph graph,
            String useKind,
            DiagnosticLocation location,
            List<Diagnostic> diagnostics) {
        for (IrValue value : values) {
            validateUse(
                    value,
                    point,
                    definitions,
                    graph,
                    useKind,
                    location,
                    diagnostics);
        }
    }

    private void validateUse(
            IrValue value,
            UsePoint point,
            Map<IrValue, List<Definition>> definitions,
            IrControlFlowGraph graph,
            String useKind,
            DiagnosticLocation location,
            List<Diagnostic> diagnostics) {
        List<Definition> candidates = definitions.getOrDefault(value, List.of());
        if (candidates.stream().anyMatch(definition -> definition.availableAt(point, graph))) {
            return;
        }
        diagnostics.add(Diagnostic.error(
                        DiagnosticStage.VALIDATION,
                        IrValidationDiagnostics.IR_USE_BEFORE_DEF,
                        "IR " + useKind + " is not dominated by a definition: "
                                + value.name() + " in block " + point.block())
                .at(location));
    }

    private Map<IrValue, List<Definition>> definitions(IrMethod method) {
        HashMap<IrValue, List<Definition>> definitions = new HashMap<>();
        for (IrValue parameter : method.parameters()) {
            addDefinition(definitions, parameter, Definition.methodParameter());
        }
        for (IrBlock block : method.blocks()) {
            for (IrValue parameter : block.parameters()) {
                addDefinition(definitions, parameter, Definition.blockParameter(block.name()));
            }
            for (int instructionIndex = 0;
                    instructionIndex < block.instructions().size();
                    instructionIndex++) {
                var instruction = block.instructions().get(instructionIndex);
                int currentInstructionIndex = instructionIndex;
                instruction.result().ifPresent(result -> addDefinition(
                        definitions,
                        result,
                        Definition.instruction(block.name(), currentInstructionIndex)));
                for (int siteIndex = 0;
                        siteIndex < instruction.exceptionSites().size();
                        siteIndex++) {
                    int currentSiteIndex = siteIndex;
                    instruction.exceptionSites().get(siteIndex).exceptionValue().ifPresent(value ->
                            addDefinition(
                                    definitions,
                                    value,
                                    Definition.exceptionSite(
                                            block.name(),
                                            currentInstructionIndex,
                                            currentSiteIndex)));
                }
            }
        }
        return definitions;
    }

    private void addDefinition(
            Map<IrValue, List<Definition>> definitions,
            IrValue value,
            Definition definition) {
        definitions.computeIfAbsent(value, ignored -> new ArrayList<>()).add(definition);
    }

    private enum DefinitionKind {
        METHOD_PARAMETER,
        BLOCK_PARAMETER,
        INSTRUCTION,
        EXCEPTION_SITE
    }

    private record Definition(
            DefinitionKind kind,
            String block,
            int instructionIndex,
            int siteIndex) {
        private static Definition methodParameter() {
            return new Definition(
                    DefinitionKind.METHOD_PARAMETER,
                    "",
                    -1,
                    -1);
        }

        private static Definition blockParameter(String block) {
            return new Definition(
                    DefinitionKind.BLOCK_PARAMETER,
                    block,
                    -1,
                    -1);
        }

        private static Definition instruction(String block, int instructionIndex) {
            return new Definition(
                    DefinitionKind.INSTRUCTION,
                    block,
                    instructionIndex,
                    -1);
        }

        private static Definition exceptionSite(
                String block,
                int instructionIndex,
                int siteIndex) {
            return new Definition(
                    DefinitionKind.EXCEPTION_SITE,
                    block,
                    instructionIndex,
                    siteIndex);
        }

        private boolean availableAt(
                UsePoint point,
                IrControlFlowGraph graph) {
            if (kind == DefinitionKind.METHOD_PARAMETER) {
                return true;
            }
            if (kind == DefinitionKind.EXCEPTION_SITE) {
                return point.exceptionHandlerArgument()
                        && block.equals(point.block())
                        && instructionIndex == point.instructionIndex()
                        && siteIndex == point.siteIndex();
            }
            if (block.equals(point.block())) {
                return kind == DefinitionKind.BLOCK_PARAMETER
                        || instructionIndex < point.instructionIndex();
            }
            return graph.dominates(block, point.block());
        }
    }

    private record UsePoint(
            String block,
            int instructionIndex,
            int siteIndex,
            boolean exceptionHandlerArgument) {
        private static UsePoint beforeInstruction(
                String block,
                int instructionIndex) {
            return new UsePoint(block, instructionIndex, -1, false);
        }

        private static UsePoint exceptionHandlerArgument(
                String block,
                int instructionIndex,
                int siteIndex) {
            return new UsePoint(block, instructionIndex, siteIndex, true);
        }

        private static UsePoint terminator(
                String block,
                int instructionCount) {
            return new UsePoint(block, instructionCount, -1, false);
        }
    }
}
