package xyz.melodysky.ir.pass.protection;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import xyz.melodysky.ir.model.IrBlock;
import xyz.melodysky.ir.model.IrInstruction;
import xyz.melodysky.ir.model.IrMethod;
import xyz.melodysky.ir.model.IrOpcode;
import xyz.melodysky.ir.model.IrTerminator;
import xyz.melodysky.ir.model.IrTerminatorKind;
import xyz.melodysky.ir.model.IrType;
import xyz.melodysky.ir.model.IrValue;

final class MethodSplitPlanner {
    static final String STUB_BACKED = "METHOD_SPLITTING_STUB_BACKED_METHOD";
    static final String EXCEPTION_SENSITIVE = "METHOD_SPLITTING_EXCEPTION_SENSITIVE";
    static final String HELPER_SENSITIVE = "METHOD_SPLITTING_HELPER_SENSITIVE";
    static final String UNSUPPORTED_LIVE_OUT_ARITY = "METHOD_SPLITTING_UNSUPPORTED_LIVE_OUT_ARITY";
    static final String NO_SAFE_REGION = "METHOD_SPLITTING_NO_SAFE_REGION";

    PlanSelection select(IrMethod method, long seed) {
        if (method.name().equals("<init>") || method.name().equals("<clinit>")) {
            return PlanSelection.skipped(STUB_BACKED);
        }
        if (isExceptionSensitive(method)) {
            return PlanSelection.skipped(EXCEPTION_SENSITIVE);
        }
        if (method.blocks().stream()
                .flatMap(block -> block.instructions().stream())
                .anyMatch(instruction -> !isPureNativeInstruction(instruction))) {
            return PlanSelection.skipped(HELPER_SENSITIVE);
        }

        ArrayList<Candidate> candidates = new ArrayList<>();
        boolean rejectedForLiveOutArity = false;
        for (int blockIndex = 0; blockIndex < method.blocks().size(); blockIndex++) {
            IrBlock block = method.blocks().get(blockIndex);
            if (block.instructions().size() < 3) {
                continue;
            }
            for (int start = 1; start <= block.instructions().size() - 2; start++) {
                CandidateAnalysis analysis = analyzeCandidate(method, blockIndex, start);
                if (analysis.liveOuts().size() != 1) {
                    rejectedForLiveOutArity = true;
                    continue;
                }
                if (!analysis.liveIns().stream().allMatch(this::isNativeScalar)
                        || !isNativeScalar(analysis.liveOuts().get(0))
                        || !analysis.liveInsAvailableAtSplit()) {
                    continue;
                }
                candidates.add(new Candidate(blockIndex, start, analysis));
            }
        }
        if (candidates.isEmpty()) {
            return PlanSelection.skipped(
                    rejectedForLiveOutArity ? UNSUPPORTED_LIVE_OUT_ARITY : NO_SAFE_REGION);
        }

        ProtectionRandom random = new ProtectionRandom(seed);
        String selectionToken = random.token("METHOD_SPLITTING_SELECT", method.methodKey(), 16);
        int selectedIndex = (int) Long.remainderUnsigned(
                Long.parseUnsignedLong(selectionToken, 16),
                candidates.size());
        Candidate selected = candidates.get(selectedIndex);
        IrBlock block = method.blocks().get(selected.blockIndex());
        String helperToken = random.token(
                "METHOD_SPLITTING_HELPER",
                method.methodKey() + ":" + block.name() + ":" + selected.startInclusive(),
                24);
        List<IrValue> liveIns = selected.analysis().liveIns();
        IrValue liveOut = selected.analysis().liveOuts().get(0);
        MethodSplitPlan plan = new MethodSplitPlan(
                method.methodKey(),
                block.name(),
                selected.startInclusive(),
                block.instructions().size(),
                liveIns,
                List.of(liveOut),
                successorBlocks(block.terminator()),
                "j2ll$outline$" + helperToken,
                descriptor(liveIns, liveOut.type()),
                "j2ll_oh_" + helperToken);
        return PlanSelection.selected(plan);
    }

    private CandidateAnalysis analyzeCandidate(IrMethod method, int blockIndex, int startInclusive) {
        IrBlock block = method.blocks().get(blockIndex);
        List<IrInstruction> region = block.instructions().subList(startInclusive, block.instructions().size());
        HashSet<IrValue> regionDefinitions = new HashSet<>();
        region.stream().flatMap(instruction -> instruction.result().stream()).forEach(regionDefinitions::add);

        LinkedHashSet<IrValue> liveIns = new LinkedHashSet<>();
        HashSet<IrValue> definitionsSeen = new HashSet<>();
        for (IrInstruction instruction : region) {
            for (IrValue operand : instruction.operands()) {
                if (!definitionsSeen.contains(operand)) {
                    liveIns.add(operand);
                }
            }
            instruction.result().ifPresent(definitionsSeen::add);
        }
        liveIns.removeAll(regionDefinitions);

        Set<IrValue> outsideUses = usesOutsideRegion(method, blockIndex, startInclusive);
        List<IrValue> liveOuts = region.stream()
                .flatMap(instruction -> instruction.result().stream())
                .filter(outsideUses::contains)
                .distinct()
                .toList();

        HashSet<IrValue> available = new HashSet<>(method.parameters());
        available.addAll(block.parameters());
        block.instructions().subList(0, startInclusive).stream()
                .flatMap(instruction -> instruction.result().stream())
                .forEach(available::add);
        return new CandidateAnalysis(
                List.copyOf(liveIns),
                liveOuts,
                available.containsAll(liveIns));
    }

    private Set<IrValue> usesOutsideRegion(IrMethod method, int regionBlockIndex, int startInclusive) {
        HashSet<IrValue> uses = new HashSet<>();
        for (int blockIndex = 0; blockIndex < method.blocks().size(); blockIndex++) {
            IrBlock block = method.blocks().get(blockIndex);
            int instructionLimit = blockIndex == regionBlockIndex
                    ? startInclusive
                    : block.instructions().size();
            for (int index = 0; index < instructionLimit; index++) {
                uses.addAll(block.instructions().get(index).operands());
            }
            addTerminatorUses(block.terminator(), uses);
        }
        return uses;
    }

    private void addTerminatorUses(IrTerminator terminator, Set<IrValue> uses) {
        terminator.value().ifPresent(uses::add);
        terminator.condition().ifPresent(uses::add);
        terminator.switchValue().ifPresent(uses::add);
        uses.addAll(terminator.targetArguments());
        uses.addAll(terminator.trueTargetArguments());
        uses.addAll(terminator.falseTargetArguments());
        uses.addAll(terminator.defaultTargetArguments());
        terminator.switchCases().forEach(switchCase -> uses.addAll(switchCase.arguments()));
    }

    private boolean isExceptionSensitive(IrMethod method) {
        return method.blocks().stream().anyMatch(block -> block.isExceptionHandler()
                || !block.exceptionCatchTypes().isEmpty()
                || !block.exceptionEdges().isEmpty()
                || block.terminator().kind() == IrTerminatorKind.THROW
                || block.instructions().stream().anyMatch(instruction -> !instruction.exceptionSites().isEmpty()));
    }

    private boolean isPureNativeInstruction(IrInstruction instruction) {
        if (instruction.result().isEmpty()
                || instruction.result().stream().anyMatch(result -> !isNativeScalar(result))
                || instruction.operands().stream().anyMatch(operand -> !isNativeScalar(operand))) {
            return false;
        }
        return switch (instruction.opcode()) {
            case CONST_INT,
                    CONST_LONG,
                    CONST_FLOAT,
                    CONST_DOUBLE,
                    ADD_I32,
                    SUB_I32,
                    MUL_I32,
                    NEG_I32,
                    SHL_I32,
                    SHR_I32,
                    USHR_I32,
                    AND_I32,
                    OR_I32,
                    XOR_I32,
                    BITCAST_I32_TO_F32,
                    CMP_EQ_I32,
                    CMP_NE_I32,
                    CMP_LT_I32,
                    CMP_LE_I32,
                    CMP_GT_I32,
                    CMP_GE_I32,
                    ADD_I64,
                    SUB_I64,
                    MUL_I64,
                    NEG_I64,
                    SHL_I64,
                    SHR_I64,
                    USHR_I64,
                    AND_I64,
                    OR_I64,
                    XOR_I64,
                    BITCAST_I64_TO_F64,
                    ADD_F32,
                    SUB_F32,
                    MUL_F32,
                    DIV_F32,
                    REM_F32,
                    NEG_F32,
                    ADD_F64,
                    SUB_F64,
                    MUL_F64,
                    DIV_F64,
                    REM_F64,
                    NEG_F64,
                    LCMP,
                    FCMPL,
                    FCMPG,
                    DCMPL,
                    DCMPG,
                    I2L,
                    I2F,
                    I2D,
                    I2B,
                    I2C,
                    I2S,
                    L2I,
                    L2F,
                    L2D,
                    F2I,
                    F2L,
                    F2D,
                    D2I,
                    D2L,
                    D2F -> true;
            default -> false;
        };
    }

    private boolean isNativeScalar(IrValue value) {
        return isNativeScalar(value.type());
    }

    private boolean isNativeScalar(IrType type) {
        return type == IrType.I1
                || type == IrType.I32
                || type == IrType.I64
                || type == IrType.F32
                || type == IrType.F64;
    }

    private List<String> successorBlocks(IrTerminator terminator) {
        LinkedHashSet<String> successors = new LinkedHashSet<>();
        terminator.target().ifPresent(successors::add);
        terminator.trueTarget().ifPresent(successors::add);
        terminator.falseTarget().ifPresent(successors::add);
        terminator.defaultTarget().ifPresent(successors::add);
        terminator.switchCases().forEach(switchCase -> successors.add(switchCase.target()));
        return List.copyOf(successors);
    }

    private String descriptor(List<IrValue> parameters, IrType returnType) {
        StringBuilder descriptor = new StringBuilder("(");
        parameters.forEach(parameter -> descriptor.append(descriptorType(parameter.type())));
        return descriptor.append(')').append(descriptorType(returnType)).toString();
    }

    private char descriptorType(IrType type) {
        return switch (type) {
            case I1 -> 'Z';
            case I32 -> 'I';
            case I64 -> 'J';
            case F32 -> 'F';
            case F64 -> 'D';
            default -> throw new IllegalArgumentException("unsupported outlined helper type " + type);
        };
    }

    record PlanSelection(MethodSplitPlan plan, String reasonCode) {
        static PlanSelection selected(MethodSplitPlan plan) {
            return new PlanSelection(plan, "METHOD_SPLITTING");
        }

        static PlanSelection skipped(String reasonCode) {
            return new PlanSelection(null, reasonCode);
        }

        boolean selected() {
            return plan != null;
        }
    }

    private record Candidate(int blockIndex, int startInclusive, CandidateAnalysis analysis) {
    }

    private record CandidateAnalysis(
            List<IrValue> liveIns,
            List<IrValue> liveOuts,
            boolean liveInsAvailableAtSplit) {
    }
}
