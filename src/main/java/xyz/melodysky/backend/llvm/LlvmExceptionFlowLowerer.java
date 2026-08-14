package xyz.melodysky.backend.llvm;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import xyz.melodysky.backend.llvm.model.LlvmBasicBlock;
import xyz.melodysky.backend.llvm.model.LlvmInstruction;
import xyz.melodysky.backend.llvm.model.LlvmTerminator;
import xyz.melodysky.backend.llvm.model.LlvmType;
import xyz.melodysky.ir.model.IrBlock;
import xyz.melodysky.ir.model.IrExceptionEdge;
import xyz.melodysky.ir.model.IrExceptionHandlers;
import xyz.melodysky.ir.model.IrExceptionSite;
import xyz.melodysky.ir.model.IrInstruction;
import xyz.melodysky.ir.model.IrTerminatorKind;
import xyz.melodysky.ir.model.IrValue;
import xyz.melodysky.runtime.RuntimeTokenDomain;
import xyz.melodysky.runtime.RuntimeTokenMapper;

/**
 * Expands JVM exception transfers into physical LLVM basic blocks.
 *
 * <p>A JNI-backed IR instruction can leave a pending exception instead of
 * transferring control. This lowerer checks that state immediately. Protected
 * sites clear it before Java catch matching; unprotected sites preserve it and
 * return directly to the JVM after allowed native cleanup.</p>
 */
final class LlvmExceptionFlowLowerer {
    private static final String CATCH_ALL = "<any>";

    private final Set<String> usedBlockNames;
    private final RuntimeTokenMapper runtimeTokens;
    private int generatedBlockOrdinal;

    LlvmExceptionFlowLowerer(Set<String> existingBlockNames) {
        this(existingBlockNames, RuntimeTokenMapper.compatibility());
    }

    LlvmExceptionFlowLowerer(
            Set<String> existingBlockNames,
            RuntimeTokenMapper runtimeTokens) {
        usedBlockNames = new HashSet<>(Objects.requireNonNull(existingBlockNames, "existingBlockNames"));
        this.runtimeTokens = Objects.requireNonNull(
                runtimeTokens,
                "runtimeTokens");
    }

    BlockResult lower(
            IrBlock block,
            List<InstructionChunk> chunks,
            LlvmTerminator regularTerminator,
            LlvmType functionReturnType,
            List<LlvmInstruction> exceptionalExitCleanup) {
        return lower(
                block,
                chunks,
                regularTerminator,
                functionReturnType,
                exceptionalExitCleanup,
                List.of());
    }

    BlockResult lower(
            IrBlock block,
            List<InstructionChunk> chunks,
            LlvmTerminator regularTerminator,
            LlvmType functionReturnType,
            List<LlvmInstruction> exceptionalExitCleanup,
            List<LlvmInstruction> normalTerminatorCleanup) {
        Objects.requireNonNull(block, "block");
        chunks = List.copyOf(Objects.requireNonNull(chunks, "chunks"));
        Objects.requireNonNull(regularTerminator, "regularTerminator");
        Objects.requireNonNull(functionReturnType, "functionReturnType");
        exceptionalExitCleanup =
                List.copyOf(Objects.requireNonNull(exceptionalExitCleanup, "exceptionalExitCleanup"));
        normalTerminatorCleanup =
                List.copyOf(Objects.requireNonNull(
                        normalTerminatorCleanup,
                        "normalTerminatorCleanup"));

        ArrayList<LlvmBasicBlock> blocks = new ArrayList<>();
        ArrayList<ExceptionalIncoming> exceptionalIncoming = new ArrayList<>();
        String currentBlockName = block.name();
        ArrayList<LlvmInstruction> currentInstructions = new ArrayList<>();

        for (int instructionIndex = 0; instructionIndex < chunks.size(); instructionIndex++) {
            InstructionChunk chunk = chunks.get(instructionIndex);
            currentInstructions.addAll(chunk.instructions());
            if (chunk.source().exceptionSites().isEmpty()) {
                currentInstructions.addAll(chunk.normalCleanup());
                continue;
            }

            PendingSite site = pendingSite(chunk.source());
            String suffix = stableHash(block.name() + ":" + instructionIndex);
            String continuation = uniqueBlockName("j2ll.ex.cont." + suffix);
            String pendingTarget = site.handlers().isEmpty()
                    ? uniqueBlockName("j2ll.ex.unhandled." + suffix)
                    : uniqueBlockName("j2ll.ex.clear." + suffix);
            String pendingFlag = "%j2ll.ex.pending." + suffix;

            currentInstructions.add(LlvmInstruction.rawProvenNoNativeUnwind(
                    Optional.of(site.exceptionValue().name()),
                    "call ptr @j2ll_rt_pending_exception(ptr %j2ll_env)"));
            currentInstructions.add(LlvmInstruction.rawProvenNoNativeUnwind(
                    Optional.of(pendingFlag),
                    "icmp ne ptr " + site.exceptionValue().name() + ", null"));
            blocks.add(new LlvmBasicBlock(
                    currentBlockName,
                    currentInstructions,
                    LlvmTerminator.branch(pendingFlag, pendingTarget, continuation)));

            if (site.handlers().isEmpty()) {
                ArrayList<LlvmInstruction> cleanup =
                        new ArrayList<>(chunk.exceptionalCleanup());
                cleanup.addAll(exceptionalExitCleanup);
                blocks.add(new LlvmBasicBlock(
                        pendingTarget,
                        cleanup,
                        returnDefault(functionReturnType)));
            } else {
                DispatchResult dispatch = dispatch(
                        pendingTarget,
                        site.exceptionValue(),
                        site.handlers(),
                        true,
                        functionReturnType,
                        exceptionalExitCleanup,
                        chunk.exceptionalCleanup(),
                        suffix);
                blocks.addAll(dispatch.blocks());
                exceptionalIncoming.addAll(dispatch.exceptionalIncoming());
            }

            currentBlockName = continuation;
            currentInstructions =
                    new ArrayList<>(chunk.normalCleanup());
        }

        if (block.terminator().kind() == IrTerminatorKind.THROW
                && !block.exceptionEdges().isEmpty()) {
            IrValue exception = block.terminator().value().orElseThrow();
            String suffix = stableHash(block.name() + ":terminator");
            String dispatchEntry = uniqueBlockName("j2ll.ex.dispatch." + suffix);
            currentInstructions.addAll(normalTerminatorCleanup);
            blocks.add(new LlvmBasicBlock(
                    currentBlockName,
                    currentInstructions,
                    LlvmTerminator.gotoBlock(dispatchEntry)));
            DispatchResult dispatch = dispatch(
                    dispatchEntry,
                    exception,
                    block.exceptionEdges(),
                    false,
                    functionReturnType,
                    exceptionalExitCleanup,
                    List.of(),
                    suffix);
            blocks.addAll(dispatch.blocks());
            exceptionalIncoming.addAll(dispatch.exceptionalIncoming());
        } else if (block.terminator().kind() == IrTerminatorKind.THROW) {
            currentInstructions.addAll(exceptionalExitCleanup);
            currentInstructions.add(rethrow(block.terminator().value().orElseThrow()));
            blocks.add(new LlvmBasicBlock(
                    currentBlockName,
                    currentInstructions,
                    returnDefault(functionReturnType)));
        } else {
            currentInstructions.addAll(normalTerminatorCleanup);
            if (block.terminator().kind() == IrTerminatorKind.RETURN
                    || block.terminator().kind() == IrTerminatorKind.THROW) {
                currentInstructions.addAll(exceptionalExitCleanup);
            }
            blocks.add(new LlvmBasicBlock(
                    currentBlockName,
                    currentInstructions,
                    regularTerminator));
        }

        return new BlockResult(
                blocks,
                currentBlockName,
                exceptionalIncoming);
    }

    private PendingSite pendingSite(IrInstruction instruction) {
        List<IrExceptionSite> sites = instruction.exceptionSites();
        if (sites.isEmpty()) {
            throw new IllegalArgumentException("JVM-throwable instruction has no pending-exception site");
        }
        IrExceptionSite first = sites.get(0);
        IrValue exception = first.exceptionValue().orElseThrow(() ->
                new IllegalArgumentException("JVM-throwable instruction has no pending-exception value"));
        for (IrExceptionSite site : sites) {
            if (!site.exceptionValue().equals(Optional.of(exception))
                    || !site.handlers().equals(first.handlers())) {
                throw new IllegalArgumentException(
                        "JVM-throwable instruction has inconsistent exception-site state");
            }
        }
        return new PendingSite(exception, first.handlers());
    }

    private DispatchResult dispatch(
            String entryBlock,
            IrValue exception,
            List<IrExceptionEdge> declaredHandlers,
            boolean clearPendingException,
            LlvmType functionReturnType,
            List<LlvmInstruction> exceptionalExitCleanup,
            List<LlvmInstruction> protectedSiteCleanup,
            String suffix) {
        List<IrExceptionEdge> handlers =
                IrExceptionHandlers.reachable(declaredHandlers);
        ArrayList<String> adapterNames = new ArrayList<>(handlers.size());
        ArrayList<String> checkNames = new ArrayList<>(handlers.size());
        for (int index = 0; index < handlers.size(); index++) {
            adapterNames.add(uniqueBlockName("j2ll.ex.catch." + suffix + "." + index));
            checkNames.add(CATCH_ALL.equals(handlers.get(index).catchType())
                    ? ""
                    : uniqueBlockName("j2ll.ex.check." + suffix + "." + index));
        }
        boolean hasCatchAll = handlers.stream().anyMatch(edge -> CATCH_ALL.equals(edge.catchType()));
        String unmatched = hasCatchAll
                ? ""
                : uniqueBlockName("j2ll.ex.unmatched." + suffix);

        ArrayList<LlvmBasicBlock> blocks = new ArrayList<>();
        ArrayList<ExceptionalIncoming> exceptionalIncoming = new ArrayList<>();
        String firstHandlerEntry = handlerEntry(checkNames, adapterNames, 0);
        if (clearPendingException) {
            ArrayList<LlvmInstruction> entryInstructions =
                    new ArrayList<>();
            entryInstructions.add(LlvmInstruction.rawProvenNoNativeUnwind(
                    Optional.empty(),
                    "call void @j2ll_rt_clear_exception(ptr %j2ll_env)"));
            entryInstructions.addAll(protectedSiteCleanup);
            blocks.add(new LlvmBasicBlock(
                    entryBlock,
                    entryInstructions,
                    LlvmTerminator.gotoBlock(firstHandlerEntry)));
        } else if (!entryBlock.equals(firstHandlerEntry)) {
            blocks.add(new LlvmBasicBlock(
                    entryBlock,
                    List.of(),
                    LlvmTerminator.gotoBlock(firstHandlerEntry)));
        }

        for (int index = 0; index < handlers.size(); index++) {
            IrExceptionEdge handler = handlers.get(index);
            validateHandlerArguments(handler, exception);
            String adapter = adapterNames.get(index);
            if (!CATCH_ALL.equals(handler.catchType())) {
                String check = checkNames.get(index);
                String matchValue = "%j2ll.ex.match." + suffix + "." + index;
                String matcherException = "%j2ll.ex.match.error." + suffix + "." + index;
                String matcherFailed = "%j2ll.ex.match.failed." + suffix + "." + index;
                String matched = "%j2ll.ex.matched." + suffix + "." + index;
                String matchResult = uniqueBlockName("j2ll.ex.match.result." + suffix + "." + index);
                String matchFailure = uniqueBlockName("j2ll.ex.match.failure." + suffix + "." + index);
                String matcher = runtimeTokens.helperSymbol(
                        RuntimeTokenDomain.CLASS_RUNTIME,
                        "instanceof",
                        "instanceof:" + handler.catchType());
                String noMatch = index + 1 < handlers.size()
                        ? handlerEntry(checkNames, adapterNames, index + 1)
                        : unmatched;
                blocks.add(new LlvmBasicBlock(
                        check,
                        List.of(
                                LlvmInstruction.rawProvenNoNativeUnwind(
                                        Optional.of(matchValue),
                                        "call i32 @" + matcher + "(ptr %j2ll_env, ptr "
                                                + exception.name() + ")"),
                                LlvmInstruction.rawProvenNoNativeUnwind(
                                        Optional.of(matcherException),
                                        "call ptr @j2ll_rt_pending_exception(ptr %j2ll_env)"),
                                LlvmInstruction.rawProvenNoNativeUnwind(
                                        Optional.of(matcherFailed),
                                        "icmp ne ptr " + matcherException + ", null")),
                        LlvmTerminator.branch(matcherFailed, matchFailure, matchResult)));
                blocks.add(new LlvmBasicBlock(
                        matchFailure,
                        exceptionalExitCleanup,
                        returnDefault(functionReturnType)));
                blocks.add(new LlvmBasicBlock(
                        matchResult,
                        List.of(LlvmInstruction.rawProvenNoNativeUnwind(
                                Optional.of(matched),
                                "icmp ne i32 " + matchValue + ", 0")),
                        LlvmTerminator.branch(matched, adapter, noMatch)));
            }
            blocks.add(new LlvmBasicBlock(
                    adapter,
                    List.of(),
                    LlvmTerminator.gotoBlock(handler.target())));
            exceptionalIncoming.add(new ExceptionalIncoming(
                    handler.target(),
                    adapter,
                    handler.arguments()));
        }

        if (!hasCatchAll) {
            ArrayList<LlvmInstruction> instructions = new ArrayList<>(exceptionalExitCleanup);
            instructions.add(rethrow(exception));
            blocks.add(new LlvmBasicBlock(
                    unmatched,
                    instructions,
                    returnDefault(functionReturnType)));
        }
        return new DispatchResult(blocks, exceptionalIncoming);
    }

    private void validateHandlerArguments(IrExceptionEdge handler, IrValue exception) {
        if (handler.arguments().isEmpty() || !handler.arguments().get(0).equals(exception)) {
            throw new IllegalArgumentException(
                    "exception handler edge must carry its throwable as the first argument");
        }
    }

    private String handlerEntry(
            List<String> checkNames,
            List<String> adapterNames,
            int index) {
        String check = checkNames.get(index);
        return check.isEmpty() ? adapterNames.get(index) : check;
    }

    private LlvmInstruction rethrow(IrValue exception) {
        return LlvmInstruction.rawProvenNoNativeUnwind(
                Optional.empty(),
                "call void @j2ll_rt_rethrow(ptr %j2ll_env, ptr " + exception.name() + ")");
    }

    private LlvmTerminator returnDefault(LlvmType returnType) {
        if (returnType == LlvmType.VOID) {
            return new LlvmTerminator(LlvmType.VOID, Optional.empty());
        }
        return new LlvmTerminator(returnType, Optional.of(defaultValue(returnType)));
    }

    private String defaultValue(LlvmType returnType) {
        return switch (returnType) {
            case I1, I32, I64 -> "0";
            case F32, F64 -> "0.0";
            case PTR -> "null";
            case VOID -> throw new IllegalArgumentException("void has no default value");
        };
    }

    private String classIdentity(String internalOrDescriptor) {
        if (internalOrDescriptor.startsWith("[")
                || (internalOrDescriptor.startsWith("L") && internalOrDescriptor.endsWith(";"))) {
            return internalOrDescriptor;
        }
        return "L" + internalOrDescriptor + ";";
    }

    private String uniqueBlockName(String preferred) {
        String candidate = preferred;
        while (!usedBlockNames.add(candidate)) {
            candidate = preferred + "." + generatedBlockOrdinal++;
        }
        return candidate;
    }

    private String stableHash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder();
            for (int index = 0; index < 8; index++) {
                result.append(String.format("%02x", digest[index]));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 digest is unavailable", exception);
        }
    }

    record InstructionChunk(
            IrInstruction source,
            List<LlvmInstruction> instructions,
            List<LlvmInstruction> normalCleanup,
            List<LlvmInstruction> exceptionalCleanup) {
        InstructionChunk {
            Objects.requireNonNull(source, "source");
            instructions = List.copyOf(Objects.requireNonNull(instructions, "instructions"));
            normalCleanup = List.copyOf(Objects.requireNonNull(
                    normalCleanup,
                    "normalCleanup"));
            exceptionalCleanup = List.copyOf(Objects.requireNonNull(
                    exceptionalCleanup,
                    "exceptionalCleanup"));
        }

        InstructionChunk(
                IrInstruction source,
                List<LlvmInstruction> instructions) {
            this(source, instructions, List.of(), List.of());
        }
    }

    record ExceptionalIncoming(String target, String predecessorBlock, List<IrValue> arguments) {
        ExceptionalIncoming {
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(predecessorBlock, "predecessorBlock");
            arguments = List.copyOf(Objects.requireNonNull(arguments, "arguments"));
        }
    }

    record BlockResult(
            List<LlvmBasicBlock> blocks,
            String normalExitBlock,
            List<ExceptionalIncoming> exceptionalIncoming) {
        BlockResult {
            blocks = List.copyOf(Objects.requireNonNull(blocks, "blocks"));
            Objects.requireNonNull(normalExitBlock, "normalExitBlock");
            exceptionalIncoming =
                    List.copyOf(Objects.requireNonNull(exceptionalIncoming, "exceptionalIncoming"));
        }
    }

    private record PendingSite(IrValue exceptionValue, List<IrExceptionEdge> handlers) {
        private PendingSite {
            Objects.requireNonNull(exceptionValue, "exceptionValue");
            handlers = List.copyOf(Objects.requireNonNull(handlers, "handlers"));
        }
    }

    private record DispatchResult(
            List<LlvmBasicBlock> blocks,
            List<ExceptionalIncoming> exceptionalIncoming) {
        private DispatchResult {
            blocks = List.copyOf(blocks);
            exceptionalIncoming = List.copyOf(exceptionalIncoming);
        }
    }
}
