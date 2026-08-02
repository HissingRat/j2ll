package xyz.melodysky.ir.pass;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import xyz.melodysky.ir.model.IrBlock;
import xyz.melodysky.ir.model.IrInstruction;
import xyz.melodysky.ir.model.IrMethod;
import xyz.melodysky.ir.model.IrOpcode;
import xyz.melodysky.ir.model.IrType;
import xyz.melodysky.ir.model.IrValue;
import xyz.melodysky.runtime.PureNativeJdkRuntimeHelpers;

/**
 * Replaces exact, non-escaping JDK call combinations with smaller JNI-native
 * implementations while retaining the original call-site exception boundaries.
 */
public final class JdkPureNativeIntrinsicPass implements IrMethodPass {
    public static final String BYTE_BUFFER_ALLOCATE =
            "java/nio/ByteBuffer#allocate!(I)Ljava/nio/ByteBuffer;";
    public static final String BYTE_BUFFER_PUT_INT =
            "java/nio/ByteBuffer#putInt!(I)Ljava/nio/ByteBuffer;";
    public static final String BYTE_BUFFER_ARRAY =
            "java/nio/ByteBuffer#array!()[B";

    @Override
    public String name() {
        return "jdkPureNativeIntrinsics";
    }

    @Override
    public IrMethod run(IrMethod method, PassContext context) {
        UseIndex uses = UseIndex.create(method);
        ArrayList<IrBlock> rewrittenBlocks = new ArrayList<>(method.blocks().size());
        for (int blockIndex = 0; blockIndex < method.blocks().size(); blockIndex++) {
            IrBlock block = method.blocks().get(blockIndex);
            Map<Integer, IrInstruction> replacements = replacements(
                    blockIndex,
                    block,
                    uses);
            if (replacements.isEmpty()) {
                rewrittenBlocks.add(block);
                continue;
            }
            ArrayList<IrInstruction> instructions =
                    new ArrayList<>(block.instructions());
            replacements.forEach(instructions::set);
            rewrittenBlocks.add(new IrBlock(
                    block.name(),
                    block.parameters(),
                    block.exceptionCatchTypes(),
                    block.exceptionEdges(),
                    instructions,
                    block.terminator()));
        }
        return new IrMethod(
                method.owner(),
                method.name(),
                method.descriptor(),
                method.returnType(),
                method.parameters(),
                rewrittenBlocks);
    }

    private Map<Integer, IrInstruction> replacements(
            int blockIndex,
            IrBlock block,
            UseIndex uses) {
        LinkedHashMap<Integer, IrInstruction> replacements =
                new LinkedHashMap<>();
        for (int index = 0; index < block.instructions().size(); index++) {
            IrInstruction allocate = block.instructions().get(index);
            Optional<Chain> maybeChain = chain(
                    blockIndex,
                    index,
                    allocate,
                    uses);
            if (maybeChain.isEmpty()) {
                continue;
            }
            Chain chain = maybeChain.orElseThrow();
            replacements.put(
                    chain.allocateIndex(),
                    helperCall(
                            chain.allocate().result(),
                            List.of(),
                            PureNativeJdkRuntimeHelpers
                                    .I32_BIG_ENDIAN_FRAME_NEW,
                            chain.allocate()));
            replacements.put(
                    chain.writeIndex(),
                    helperCall(
                            chain.write().result(),
                            List.of(
                                    chain.allocate().result().orElseThrow(),
                                    chain.write().operands().get(1)),
                            PureNativeJdkRuntimeHelpers
                                    .I32_BIG_ENDIAN_FRAME_WRITE,
                            chain.write()));
            replacements.put(
                    chain.finishIndex(),
                    helperCall(
                            chain.finish().result(),
                            List.of(chain.write().result().orElseThrow()),
                            PureNativeJdkRuntimeHelpers
                                    .I32_BIG_ENDIAN_FRAME_FINISH,
                            chain.finish()));
        }
        return Map.copyOf(replacements);
    }

    private Optional<Chain> chain(
            int blockIndex,
            int allocateIndex,
            IrInstruction allocate,
            UseIndex uses) {
        if (!isCall(
                        allocate,
                        IrOpcode.CALL_STATIC,
                        BYTE_BUFFER_ALLOCATE,
                        IrType.REFERENCE,
                        List.of(IrType.I32))
                || allocate.callIndirection().isPresent()) {
            return Optional.empty();
        }
        IrValue size = allocate.operands().get(0);
        InstructionLocation sizeDefinition = uses.definitions().get(size);
        if (sizeDefinition == null
                || sizeDefinition.blockIndex() != blockIndex
                || sizeDefinition.instructionIndex() >= allocateIndex
                || sizeDefinition.instruction().opcode() != IrOpcode.CONST_INT
                || sizeDefinition.instruction().intLiteral().orElse(-1) != 4) {
            return Optional.empty();
        }
        IrValue allocated = allocate.result().orElseThrow();
        Optional<InstructionLocation> writeLocation = uses.uniqueInstructionUse(allocated);
        if (writeLocation.isEmpty()
                || writeLocation.orElseThrow().blockIndex() != blockIndex
                || writeLocation.orElseThrow().instructionIndex() <= allocateIndex) {
            return Optional.empty();
        }
        IrInstruction write = writeLocation.orElseThrow().instruction();
        if (!isCall(
                        write,
                        IrOpcode.CALL_VIRTUAL,
                        BYTE_BUFFER_PUT_INT,
                        IrType.REFERENCE,
                        List.of(IrType.REFERENCE, IrType.I32))
                || write.callIndirection().isPresent()
                || !write.operands().get(0).equals(allocated)) {
            return Optional.empty();
        }
        IrValue written = write.result().orElseThrow();
        Optional<InstructionLocation> finishLocation = uses.uniqueInstructionUse(written);
        if (finishLocation.isEmpty()
                || finishLocation.orElseThrow().blockIndex() != blockIndex
                || finishLocation.orElseThrow().instructionIndex()
                        <= writeLocation.orElseThrow().instructionIndex()) {
            return Optional.empty();
        }
        IrInstruction finish = finishLocation.orElseThrow().instruction();
        if (!isCall(
                        finish,
                        IrOpcode.CALL_VIRTUAL,
                        BYTE_BUFFER_ARRAY,
                        IrType.REFERENCE,
                        List.of(IrType.REFERENCE))
                || finish.callIndirection().isPresent()
                || !finish.operands().get(0).equals(written)) {
            return Optional.empty();
        }
        return Optional.of(new Chain(
                allocateIndex,
                allocate,
                writeLocation.orElseThrow().instructionIndex(),
                write,
                finishLocation.orElseThrow().instructionIndex(),
                finish));
    }

    private boolean isCall(
            IrInstruction instruction,
            IrOpcode opcode,
            String symbol,
            IrType resultType,
            List<IrType> operandTypes) {
        return instruction.opcode() == opcode
                && instruction.symbol().map(symbol::equals).orElse(false)
                && instruction.result()
                        .map(IrValue::type)
                        .filter(resultType::equals)
                        .isPresent()
                && instruction.operands().stream()
                        .map(IrValue::type)
                        .toList()
                        .equals(operandTypes);
    }

    private IrInstruction helperCall(
            Optional<IrValue> result,
            List<IrValue> operands,
            String symbol,
            IrInstruction source) {
        return IrInstruction.call(
                        result,
                        IrOpcode.CALL_RUNTIME_HELPER,
                        operands,
                        symbol)
                .withExceptionSites(source.exceptionSites());
    }

    private record Chain(
            int allocateIndex,
            IrInstruction allocate,
            int writeIndex,
            IrInstruction write,
            int finishIndex,
            IrInstruction finish) {
    }

    private record InstructionLocation(
            int blockIndex,
            int instructionIndex,
            IrInstruction instruction) {
    }

    private record UseIndex(
            Map<IrValue, InstructionLocation> definitions,
            Map<IrValue, List<InstructionLocation>> instructionUses,
            Map<IrValue, Integer> allUseCounts) {
        private static UseIndex create(IrMethod method) {
            HashMap<IrValue, InstructionLocation> definitions = new HashMap<>();
            HashMap<IrValue, ArrayList<InstructionLocation>> instructionUses =
                    new HashMap<>();
            HashMap<IrValue, Integer> allUseCounts = new HashMap<>();
            for (int blockIndex = 0;
                    blockIndex < method.blocks().size();
                    blockIndex++) {
                IrBlock block = method.blocks().get(blockIndex);
                for (int instructionIndex = 0;
                        instructionIndex < block.instructions().size();
                        instructionIndex++) {
                    IrInstruction instruction =
                            block.instructions().get(instructionIndex);
                    InstructionLocation location = new InstructionLocation(
                            blockIndex,
                            instructionIndex,
                            instruction);
                    instruction.result().ifPresent(
                            result -> definitions.put(result, location));
                    for (IrValue operand : instruction.operands()) {
                        instructionUses
                                .computeIfAbsent(
                                        operand,
                                        ignored -> new ArrayList<>())
                                .add(location);
                        increment(allUseCounts, operand);
                    }
                    instruction.exceptionSites().stream()
                            .flatMap(site -> site.handlers().stream())
                            .flatMap(edge -> edge.arguments().stream())
                            .forEach(value -> increment(allUseCounts, value));
                }
                block.exceptionEdges().stream()
                        .flatMap(edge -> edge.arguments().stream())
                        .forEach(value -> increment(allUseCounts, value));
                block.terminator().value()
                        .ifPresent(value -> increment(allUseCounts, value));
                block.terminator().condition()
                        .ifPresent(value -> increment(allUseCounts, value));
                block.terminator().switchValue()
                        .ifPresent(value -> increment(allUseCounts, value));
                block.terminator().targetArguments()
                        .forEach(value -> increment(allUseCounts, value));
                block.terminator().trueTargetArguments()
                        .forEach(value -> increment(allUseCounts, value));
                block.terminator().falseTargetArguments()
                        .forEach(value -> increment(allUseCounts, value));
                block.terminator().defaultTargetArguments()
                        .forEach(value -> increment(allUseCounts, value));
                block.terminator().switchCases().stream()
                        .flatMap(switchCase -> switchCase.arguments().stream())
                        .forEach(value -> increment(allUseCounts, value));
            }
            Map<IrValue, List<InstructionLocation>> immutableUses =
                    new HashMap<>();
            instructionUses.forEach((value, locations) ->
                    immutableUses.put(value, List.copyOf(locations)));
            return new UseIndex(
                    Map.copyOf(definitions),
                    Map.copyOf(immutableUses),
                    Map.copyOf(allUseCounts));
        }

        private Optional<InstructionLocation> uniqueInstructionUse(
                IrValue value) {
            if (allUseCounts.getOrDefault(value, 0) != 1) {
                return Optional.empty();
            }
            List<InstructionLocation> locations =
                    instructionUses.getOrDefault(value, List.of());
            return locations.size() == 1
                    ? Optional.of(locations.get(0))
                    : Optional.empty();
        }

        private static void increment(
                Map<IrValue, Integer> counts,
                IrValue value) {
            counts.merge(value, 1, Integer::sum);
        }
    }
}
