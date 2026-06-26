package xyz.melodysky.ir.ssa;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.objectweb.asm.ConstantDynamic;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.LookupSwitchInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MultiANewArrayInsnNode;
import org.objectweb.asm.tree.TableSwitchInsnNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;
import xyz.melodysky.diagnostic.Diagnostic;
import xyz.melodysky.diagnostic.DiagnosticCode;
import xyz.melodysky.diagnostic.DiagnosticLocation;
import xyz.melodysky.diagnostic.DiagnosticStage;
import xyz.melodysky.frontend.cfg.BytecodeBasicBlock;
import xyz.melodysky.frontend.cfg.BytecodeCfg;
import xyz.melodysky.frontend.cfg.BytecodeEdge;
import xyz.melodysky.frontend.cfg.BytecodeEdgeKind;
import xyz.melodysky.frontend.cfg.ExceptionRegion;
import xyz.melodysky.frontend.cfg.MethodCfgResult;
import xyz.melodysky.frontend.classfile.ParsedField;
import xyz.melodysky.frontend.classfile.ParsedMethod;
import xyz.melodysky.ir.model.IrBlock;
import xyz.melodysky.ir.model.IrExceptionEdge;
import xyz.melodysky.ir.model.IrExceptionSite;
import xyz.melodysky.ir.model.IrExceptionSiteKind;
import xyz.melodysky.ir.model.IrInstruction;
import xyz.melodysky.ir.model.IrMethod;
import xyz.melodysky.ir.model.IrOpcode;
import xyz.melodysky.ir.model.IrSwitchCase;
import xyz.melodysky.ir.model.IrTerminator;
import xyz.melodysky.ir.model.IrType;
import xyz.melodysky.ir.model.IrValue;
import xyz.melodysky.pipeline.LoweringStatus;
import xyz.melodysky.pipeline.StageResult;
import xyz.melodysky.runtime.RuntimeHelperCatalog;
import xyz.melodysky.runtime.jdk.JdkIntrinsic;
import xyz.melodysky.runtime.jdk.JdkIntrinsicRegistry;
import xyz.melodysky.runtime.jdk.JdkMethodPolicy;
import xyz.melodysky.runtime.jdk.LambdaMetafactoryBootstrap;
import xyz.melodysky.runtime.jdk.LambdaMetafactoryPlan;
import xyz.melodysky.runtime.jdk.StringConcatBootstrapPlan;
import xyz.melodysky.runtime.jdk.StringConcatFactoryBootstrap;
import xyz.melodysky.runtime.jdk.StringConcatToken;
import xyz.melodysky.runtime.jdk.StringConcatTokenKind;
import xyz.melodysky.runtime.unsafe.UnsafeOperationKind;
import xyz.melodysky.runtime.unsafe.UnsafePlan;
import xyz.melodysky.runtime.unsafe.UnsafePolicy;

public final class BytecodeToSsaLowerer implements Opcodes {
    private final JdkIntrinsicRegistry jdkIntrinsics = JdkIntrinsicRegistry.defaultRegistry();
    private final RuntimeHelperCatalog runtimeHelpers = RuntimeHelperCatalog.defaultCatalog();
    private final StringConcatFactoryBootstrap stringConcatFactory = new StringConcatFactoryBootstrap();
    private final LambdaMetafactoryBootstrap lambdaMetafactory = new LambdaMetafactoryBootstrap();
    private final UnsafePolicy unsafePolicy = new UnsafePolicy();

    public StageResult<SsaMethodResult> lower(MethodCfgResult cfgResult) {
        ParsedMethod method = cfgResult.method();
        if (cfgResult.cfg().isEmpty()) {
            return skipped(method, "NO_CFG", "method has no CFG to lower");
        }
        BytecodeCfg cfg = cfgResult.cfg().orElseThrow();
        if (hasComplexExceptionShape(cfg)
                && !isSupportedSynchronizedExceptionCleanupShape(cfg)
                && !isSupportedCatchAllRethrowShape(cfg)) {
            return skipped(
                    method,
                    unsupportedCatchAllReasonCode(cfg),
                    "SSA lowerer cannot yet prove catch-all/finally shape preserves exception state");
        }
        ValueFactory values = new ValueFactory();
        List<IrValue> parameters = createParameters(method, values);
        MethodMonitor methodMonitor = createMethodMonitor(method, values, parameters);
        ClassInitializationContext classInitialization = createClassInitializationContext(method, values);
        Map<Integer, BytecodeBasicBlock> blocksById = blocksById(cfg);
        Map<Integer, Integer> predecessorCounts = predecessorCounts(cfg);
        ArrayList<Diagnostic> diagnostics = new ArrayList<>();
        Map<Integer, BlockInput> inputs = new HashMap<>();
        Map<Integer, BlockLowering> loweredBlocks = new HashMap<>();
        ArrayDeque<Integer> worklist = new ArrayDeque<>();
        HashSet<Integer> queued = new HashSet<>();

        inputs.put(0, new BlockInput(seedEntryState(method, parameters)));
        enqueue(worklist, queued, 0);
        seedExceptionHandlers(method, cfg, values, parameters, inputs, worklist, queued);

        try {
            while (!worklist.isEmpty()) {
                int blockId = worklist.removeFirst();
                queued.remove(blockId);
                BytecodeBasicBlock block = blocksById.get(blockId);
                if (block == null) {
                    continue;
                }
                BlockInput input = inputs.get(blockId);
                if (input == null) {
                    continue;
                }
                BlockLowering lowered = lowerBlock(
                        cfg,
                        block,
                        values,
                        input.state.copy(),
                        methodMonitor,
                        classInitialization,
                        diagnostics);
                loweredBlocks.put(blockId, lowered);
                for (BytecodeEdge successor : normalSuccessors(cfg, block)) {
                    boolean changed = mergeInto(
                            inputs,
                            successor.toBlockId(),
                            lowered.outgoingState.copy(),
                            values,
                            predecessorCounts.getOrDefault(successor.toBlockId(), 0) > 1);
                    if (changed) {
                        enqueue(worklist, queued, successor.toBlockId());
                    }
                }
            }
        } catch (MergeFailure failure) {
            return skipped(method, failure.reasonCode, failure.getMessage());
        } catch (IllegalStateException exception) {
            Diagnostic diagnostic = Diagnostic.error(
                            DiagnosticStage.LOWERING,
                            LoweringDiagnostics.STACK_UNDERFLOW,
                            exception.getMessage())
                    .at(location(method));
            return StageResult.complete(
                    DiagnosticStage.LOWERING,
                    SsaMethodResult.frontendSkipped(method, "STACK_UNDERFLOW", exception.getMessage()),
                    List.of(diagnostic));
        } catch (UnsupportedOperationException exception) {
            return skipped(method, "UNSUPPORTED_OPCODE", exception.getMessage());
        }

        ArrayList<IrBlock> blocks = new ArrayList<>();
        for (BytecodeBasicBlock block : cfg.blocks()) {
            BlockLowering lowered = loweredBlocks.get(block.id());
            BlockInput input = inputs.get(block.id());
            if (lowered == null || input == null) {
                continue;
            }
            blocks.add(new IrBlock(
                    blockName(block.id()),
                    input.parameters(),
                    block.handlerCatchTypes(),
                    exceptionEdges(cfg, block, methodMonitor, classInitialization),
                    lowered.instructions(),
                    withTargetArguments(lowered.terminator(), lowered.outgoingState(), inputs)));
        }
        if (classInitialization != null) {
            blocks.add(classInitialization.failedBlock());
        }
        if (methodMonitor != null) {
            blocks.add(methodMonitor.cleanupBlock());
        }

        IrMethod irMethod = new IrMethod(
                method.owner(),
                method.name(),
                method.descriptor(),
                returnType(method.descriptor()),
                parameters,
                blocks);
        SsaMethodResult artifact = diagnostics.isEmpty()
                ? SsaMethodResult.lowered(method, irMethod)
                : SsaMethodResult.halfLowered(
                        method,
                        irMethod,
                        DiagnosticCode.JVM_HELPER_FALLBACK.value(),
                        "one or more call sites require JVM helper fallback");
        return StageResult.complete(DiagnosticStage.LOWERING, artifact, diagnostics);
    }

    private BlockLowering lowerBlock(
            BytecodeCfg cfg,
            BytecodeBasicBlock block,
            ValueFactory values,
            FrameState inputState,
            MethodMonitor methodMonitor,
            ClassInitializationContext classInitialization,
            List<Diagnostic> diagnostics) {
        StackState stack = new StackState(inputState.stack());
        LocalState locals = new LocalState(inputState.locals());
        ArrayList<IrInstruction> instructions = new ArrayList<>();
        IrTerminator terminator = null;
        List<IrExceptionEdge> exceptionEdges = exceptionEdges(cfg, block, methodMonitor, classInitialization);
        if (classInitialization != null && block.id() == 0) {
            instructions.addAll(classInitialization.classObjectInstructions());
            instructions.add(IrInstruction.operation(
                    java.util.Optional.empty(),
                    IrOpcode.CLASS_INIT_BEGIN,
                    List.of(classInitialization.classObject()),
                    classInitialization.classSymbol()));
        }
        if (methodMonitor != null && block.id() == 0) {
            instructions.addAll(methodMonitor.lockInstructions());
            appendMonitorHelper(IrOpcode.MONITOR_ENTER, methodMonitor.lock(), List.of(), instructions, "monitorEnter");
        }
        List<AbstractInsnNode> bytecode = cfg.instructions().subList(
                block.startInstructionIndex(),
                block.endInstructionIndexExclusive());
        for (AbstractInsnNode instruction : bytecode) {
            int opcode = instruction.getOpcode();
            if (isConditionalBranch(opcode)) {
                terminator = lowerConditionalBranch(cfg, block, opcode, stack, values, instructions);
            } else if (opcode == GOTO) {
                terminator = IrTerminator.gotoBlock(blockName(branchEdge(cfg, block).toBlockId()));
            } else if (instruction instanceof TableSwitchInsnNode || instruction instanceof LookupSwitchInsnNode) {
                terminator = lowerSwitch(cfg, block, stack);
            } else if (isValueReturn(opcode)) {
                IrValue value = stack.pop();
                appendClassInitializerEnd(classInitialization, instructions);
                appendMethodMonitorExit(methodMonitor, instructions);
                terminator = IrTerminator.returnValue(value);
            } else if (opcode == ATHROW) {
                IrValue value = stack.pop();
                appendClassInitializerFailed(classInitialization, value, instructions);
                appendMethodMonitorExceptionalExit(methodMonitor, instructions);
                terminator = IrTerminator.throwValue(value);
            } else if (opcode == RETURN) {
                appendClassInitializerEnd(classInitialization, instructions);
                appendMethodMonitorExit(methodMonitor, instructions);
                terminator = IrTerminator.returnVoid();
            } else {
                lowerStackInstruction(
                        cfg.method(),
                        instruction,
                        values,
                        stack,
                        locals,
                        exceptionEdges,
                        instructions,
                        diagnostics,
                        block.isExceptionHandler()
                                && block.handlerCatchTypes().contains(ExceptionRegion.CATCH_ALL));
            }
        }
        if (terminator == null) {
            BytecodeEdge fallthrough = edge(cfg, block, BytecodeEdgeKind.FALLTHROUGH);
            if (fallthrough == null) {
                throw new UnsupportedOperationException("method did not lower to an explicit terminator");
            }
            terminator = IrTerminator.gotoBlock(blockName(fallthrough.toBlockId()));
        }
        return new BlockLowering(
                instructions,
                terminator,
                new FrameState(stack.snapshotBottomToTop(), locals.snapshot()));
    }

    private void lowerStackInstruction(
            ParsedMethod method,
            AbstractInsnNode instruction,
            ValueFactory values,
            StackState stack,
            LocalState locals,
            List<IrExceptionEdge> exceptionEdges,
            List<IrInstruction> instructions,
            List<Diagnostic> diagnostics,
            boolean exceptionalMonitorCleanup) {
        int opcode = instruction.getOpcode();
        if (opcode >= ICONST_M1 && opcode <= ICONST_5) {
            int literal = opcode == ICONST_M1 ? -1 : opcode - ICONST_0;
            pushConst(literal, values, stack, instructions);
        } else if (opcode == ACONST_NULL) {
            IrValue result = values.next(IrType.REFERENCE);
            instructions.add(IrInstruction.constNull(result));
            stack.push(result);
        } else if (opcode == LCONST_0 || opcode == LCONST_1) {
            IrValue result = values.next(IrType.I64);
            instructions.add(IrInstruction.constLong(result, opcode == LCONST_0 ? 0L : 1L));
            stack.push(result);
        } else if (opcode >= FCONST_0 && opcode <= FCONST_2) {
            IrValue result = values.next(IrType.F32);
            instructions.add(IrInstruction.constFloat(result, (float) (opcode - FCONST_0)));
            stack.push(result);
        } else if (opcode == DCONST_0 || opcode == DCONST_1) {
            IrValue result = values.next(IrType.F64);
            instructions.add(IrInstruction.constDouble(result, opcode == DCONST_0 ? 0.0D : 1.0D));
            stack.push(result);
        } else if (instruction instanceof IntInsnNode intInsn && (opcode == BIPUSH || opcode == SIPUSH)) {
            pushConst(intInsn.operand, values, stack, instructions);
        } else if (instruction instanceof LdcInsnNode ldc) {
            pushLdc(ldc.cst, method, values, stack, instructions, diagnostics);
        } else if (instruction instanceof VarInsnNode varInsn && isLoad(opcode)) {
            stack.push(locals.get(varInsn.var));
        } else if (instruction instanceof VarInsnNode varInsn && isStore(opcode)) {
            locals.set(varInsn.var, stack.pop());
        } else if (instruction instanceof IincInsnNode iincInsn) {
            lowerIinc(iincInsn, values, locals, instructions);
        } else if (instruction instanceof TypeInsnNode typeInsn
                && (opcode == NEW || opcode == ANEWARRAY || opcode == CHECKCAST || opcode == INSTANCEOF)) {
            lowerTypeInstruction(method, typeInsn, opcode, values, stack, exceptionEdges, instructions);
        } else if (instruction instanceof IntInsnNode intInsn && opcode == NEWARRAY) {
            lowerPrimitiveArrayAllocation(intInsn, values, stack, instructions);
        } else if (instruction instanceof MultiANewArrayInsnNode multiANewArrayInsn) {
            lowerMultiArrayAllocation(multiANewArrayInsn, values, stack, instructions);
        } else if (isStackManipulation(opcode)) {
            lowerStackManipulation(opcode, stack);
        } else if (opcode == ARRAYLENGTH) {
            IrValue array = stack.pop();
            IrValue result = values.next(IrType.I32);
            instructions.add(IrInstruction.operation(
                    java.util.Optional.of(result),
                    IrOpcode.ARRAY_LENGTH,
                    List.of(array),
                    "arrayLength")
                    .withExceptionSite(exceptionSite(IrExceptionSiteKind.NULL_CHECK, exceptionEdges)));
            stack.push(result);
        } else if (isArrayLoad(opcode)) {
            lowerArrayLoad(opcode, values, stack, exceptionEdges, instructions);
        } else if (isArrayStore(opcode)) {
            lowerArrayStore(opcode, stack, exceptionEdges, instructions);
        } else if (isShift(opcode)) {
            lowerShift(opcode, values, stack, instructions);
        } else if (isBinary(opcode)) {
            IrValue right = stack.pop();
            IrValue left = stack.pop();
            IrValue result = values.next(binaryResultType(opcode));
            IrInstruction binary = IrInstruction.binary(result, opcodeFor(opcode), left, right);
            if (isIntegerDivisionOrRemainder(opcode)) {
                binary = binary.withExceptionSite(
                        exceptionSite(IrExceptionSiteKind.DIVISION_BY_ZERO, exceptionEdges));
            }
            instructions.add(binary);
            stack.push(result);
        } else if (isNeg(opcode)) {
            IrValue operand = stack.pop();
            IrValue result = values.next(operand.type());
            instructions.add(IrInstruction.unary(result, opcodeFor(opcode), operand));
            stack.push(result);
        } else if (isConversion(opcode)) {
            IrValue operand = stack.pop();
            IrValue result = values.next(conversionResultType(opcode));
            instructions.add(IrInstruction.unary(result, opcodeFor(opcode), operand));
            stack.push(result);
        } else if (isValueComparison(opcode)) {
            IrValue right = stack.pop();
            IrValue left = stack.pop();
            IrValue result = values.next(IrType.I32);
            instructions.add(IrInstruction.binary(result, opcodeFor(opcode), left, right));
            stack.push(result);
        } else if (instruction instanceof FieldInsnNode fieldInsn
                && (opcode == GETSTATIC || opcode == PUTSTATIC || opcode == GETFIELD || opcode == PUTFIELD)) {
            lowerFieldInstruction(method, fieldInsn, opcode, values, stack, instructions);
        } else if (instruction instanceof MethodInsnNode methodInsn
                && (opcode == INVOKESTATIC || opcode == INVOKESPECIAL || opcode == INVOKEVIRTUAL || opcode == INVOKEINTERFACE)) {
            lowerMethodCall(method, methodInsn, opcode, values, stack, instructions, diagnostics);
        } else if (instruction instanceof InvokeDynamicInsnNode invokeDynamicInsn) {
            lowerDynamicCall(method, invokeDynamicInsn, values, stack, instructions, diagnostics);
        } else if (opcode == MONITORENTER || opcode == MONITOREXIT) {
            lowerMonitorInstruction(opcode, stack, exceptionEdges, instructions, exceptionalMonitorCleanup);
        } else {
            throw new UnsupportedOperationException("unsupported opcode " + opcode);
        }
    }

    private void lowerIinc(
            IincInsnNode iincInsn,
            ValueFactory values,
            LocalState locals,
            List<IrInstruction> instructions) {
        IrValue current = locals.get(iincInsn.var);
        IrValue increment = values.next(IrType.I32);
        instructions.add(IrInstruction.constInt(increment, iincInsn.incr));
        IrValue result = values.next(IrType.I32);
        instructions.add(IrInstruction.binary(result, IrOpcode.ADD_I32, current, increment));
        locals.set(iincInsn.var, result);
    }

    private void lowerStackManipulation(int opcode, StackState stack) {
        switch (opcode) {
            case POP -> stack.applyPop();
            case POP2 -> stack.applyPop2();
            case DUP -> stack.applyDup();
            case DUP_X1 -> stack.applyDupX1();
            case DUP_X2 -> stack.applyDupX2();
            case DUP2 -> stack.applyDup2();
            case DUP2_X1 -> stack.applyDup2X1();
            case DUP2_X2 -> stack.applyDup2X2();
            case SWAP -> stack.applySwap();
            default -> throw new IllegalArgumentException("not a stack manipulation opcode " + opcode);
        }
    }

    private void lowerShift(
            int opcode,
            ValueFactory values,
            StackState stack,
            List<IrInstruction> instructions) {
        IrValue shiftCount = stack.pop();
        IrValue value = stack.pop();
        IrValue mask = values.next(IrType.I32);
        instructions.add(IrInstruction.constInt(mask, binaryResultType(opcode) == IrType.I64 ? 63 : 31));
        IrValue maskedCount = values.next(IrType.I32);
        instructions.add(IrInstruction.binary(maskedCount, IrOpcode.AND_I32, shiftCount, mask));
        IrValue typedCount = maskedCount;
        if (binaryResultType(opcode) == IrType.I64) {
            typedCount = values.next(IrType.I64);
            instructions.add(IrInstruction.unary(typedCount, IrOpcode.I2L, maskedCount));
        }
        IrValue result = values.next(binaryResultType(opcode));
        instructions.add(IrInstruction.binary(result, opcodeFor(opcode), value, typedCount));
        stack.push(result);
    }

    private void lowerTypeInstruction(
            ParsedMethod method,
            TypeInsnNode typeInsn,
            int opcode,
            ValueFactory values,
            StackState stack,
            List<IrExceptionEdge> exceptionEdges,
            List<IrInstruction> instructions) {
        if (opcode == NEW) {
            appendClassInitGuard(method, typeInsn.desc, values, instructions);
            IrValue result = values.next(IrType.REFERENCE);
            instructions.add(IrInstruction.operation(
                    java.util.Optional.of(result),
                    IrOpcode.NEW_OBJECT,
                    List.of(),
                    "object:" + typeInsn.desc));
            stack.push(result);
        } else if (opcode == ANEWARRAY) {
            IrValue count = stack.pop();
            IrValue result = values.next(IrType.REFERENCE);
            instructions.add(IrInstruction.operation(
                    java.util.Optional.of(result),
                    IrOpcode.NEW_ARRAY,
                    List.of(count),
                    "referenceArray:" + typeInsn.desc));
            stack.push(result);
        } else if (opcode == CHECKCAST) {
            IrValue value = stack.pop();
            IrValue result = values.next(IrType.REFERENCE);
            instructions.add(IrInstruction.operation(
                    java.util.Optional.of(result),
                    IrOpcode.CHECKCAST,
                    List.of(value),
                    "checkcast:" + typeInsn.desc)
                    .withExceptionSite(exceptionSite(IrExceptionSiteKind.CLASS_CAST, exceptionEdges)));
            stack.push(result);
        } else {
            IrValue value = stack.pop();
            IrValue result = values.next(IrType.I32);
            instructions.add(IrInstruction.operation(
                    java.util.Optional.of(result),
                    IrOpcode.INSTANCEOF,
                    List.of(value),
                    "instanceof:" + typeInsn.desc));
            stack.push(result);
        }
    }

    private void lowerPrimitiveArrayAllocation(
            IntInsnNode intInsn,
            ValueFactory values,
            StackState stack,
            List<IrInstruction> instructions) {
        IrValue count = stack.pop();
        IrValue result = values.next(IrType.REFERENCE);
        instructions.add(IrInstruction.operation(
                java.util.Optional.of(result),
                IrOpcode.NEW_ARRAY,
                List.of(count),
                "primitiveArray:" + primitiveArrayType(intInsn.operand)));
        stack.push(result);
    }

    private void lowerMultiArrayAllocation(
            MultiANewArrayInsnNode multiANewArrayInsn,
            ValueFactory values,
            StackState stack,
            List<IrInstruction> instructions) {
        ArrayList<IrValue> dimensions = new ArrayList<>();
        for (int index = multiANewArrayInsn.dims - 1; index >= 0; index--) {
            dimensions.add(0, stack.pop());
        }
        IrValue result = values.next(IrType.REFERENCE);
        instructions.add(IrInstruction.operation(
                java.util.Optional.of(result),
                IrOpcode.NEW_MULTI_ARRAY,
                dimensions,
                "multiArray:" + multiANewArrayInsn.desc + ":" + multiANewArrayInsn.dims));
        stack.push(result);
    }

    private void lowerArrayLoad(
            int opcode,
            ValueFactory values,
            StackState stack,
            List<IrExceptionEdge> exceptionEdges,
            List<IrInstruction> instructions) {
        IrValue index = stack.pop();
        IrValue array = stack.pop();
        IrValue result = values.next(arrayLoadType(opcode));
        instructions.add(IrInstruction.operation(
                java.util.Optional.of(result),
                arrayLoadOpcode(opcode),
                List.of(array, index),
                arrayElementKind(opcode))
                .withExceptionSite(exceptionSite(IrExceptionSiteKind.ARRAY_BOUNDS, exceptionEdges)));
        stack.push(result);
    }

    private void lowerArrayStore(
            int opcode,
            StackState stack,
            List<IrExceptionEdge> exceptionEdges,
            List<IrInstruction> instructions) {
        IrValue value = stack.pop();
        IrValue index = stack.pop();
        IrValue array = stack.pop();
        IrInstruction store = IrInstruction.operation(
                java.util.Optional.empty(),
                arrayStoreOpcode(opcode),
                List.of(array, index, value),
                arrayElementKind(opcode))
                .withExceptionSite(exceptionSite(IrExceptionSiteKind.ARRAY_BOUNDS, exceptionEdges));
        if (opcode == AASTORE) {
            store = store.withExceptionSite(exceptionSite(IrExceptionSiteKind.ARRAY_STORE, exceptionEdges));
        }
        instructions.add(store);
    }

    private void lowerFieldInstruction(
            ParsedMethod method,
            FieldInsnNode fieldInsn,
            int opcode,
            ValueFactory values,
            StackState stack,
            List<IrInstruction> instructions) {
        String fieldKey = fieldInsn.owner + "#" + fieldInsn.name + "!" + fieldInsn.desc;
        IrType fieldType = typeAt(fieldInsn.desc, 0).type();
        java.util.Optional<ParsedField> parsedField = fieldMetadata(method, fieldInsn);
        boolean volatileField = parsedField.map(field -> field.accessFlags().isVolatile()).orElse(false);
        boolean finalField = parsedField.map(field -> field.accessFlags().isFinal()).orElse(false);
        if (opcode == GETSTATIC) {
            if (!method.owner().equals(fieldInsn.owner)) {
                appendClassInitGuard(method, fieldInsn.owner, values, instructions);
            }
            IrValue result = values.next(fieldType);
            instructions.add(IrInstruction.fieldGet(result, IrOpcode.GET_STATIC, List.of(), fieldKey));
            if (volatileField) {
                instructions.add(memoryMarker(IrOpcode.VOLATILE_READ_BARRIER, List.of(result), fieldKey));
            }
            stack.push(result);
        } else if (opcode == PUTSTATIC) {
            if (!method.owner().equals(fieldInsn.owner)) {
                appendClassInitGuard(method, fieldInsn.owner, values, instructions);
            }
            IrValue value = stack.pop();
            if (volatileField) {
                instructions.add(memoryMarker(IrOpcode.VOLATILE_WRITE_BARRIER, List.of(value), fieldKey));
            }
            instructions.add(IrInstruction.fieldPut(IrOpcode.PUT_STATIC, List.of(value), fieldKey));
        } else if (opcode == GETFIELD) {
            IrValue receiver = stack.pop();
            IrValue result = values.next(fieldType);
            instructions.add(IrInstruction.fieldGet(result, IrOpcode.GET_FIELD, List.of(receiver), fieldKey));
            if (volatileField) {
                instructions.add(memoryMarker(IrOpcode.VOLATILE_READ_BARRIER, List.of(result), fieldKey));
            }
            stack.push(result);
        } else {
            IrValue value = stack.pop();
            IrValue receiver = stack.pop();
            if (volatileField) {
                instructions.add(memoryMarker(IrOpcode.VOLATILE_WRITE_BARRIER, List.of(value), fieldKey));
            }
            instructions.add(IrInstruction.fieldPut(IrOpcode.PUT_FIELD, List.of(receiver, value), fieldKey));
            if (finalField && method.name().equals("<init>")) {
                instructions.add(memoryMarker(IrOpcode.FINAL_FIELD_PUBLICATION, List.of(receiver, value), fieldKey));
            }
        }
    }

    private void lowerMethodCall(
            ParsedMethod currentMethod,
            MethodInsnNode methodInsn,
            int opcode,
            ValueFactory values,
            StackState stack,
            List<IrInstruction> instructions,
            List<Diagnostic> diagnostics) {
        if (opcode == INVOKESTATIC && !currentMethod.owner().equals(methodInsn.owner)) {
            appendClassInitGuard(currentMethod, methodInsn.owner, values, instructions);
        }
        ArrayList<IrValue> operands = new ArrayList<>();
        List<IrType> parameterTypes = parameterTypes(methodInsn.desc);
        for (int index = parameterTypes.size() - 1; index >= 0; index--) {
            operands.add(0, stack.pop());
        }
        if (opcode == INVOKESPECIAL || opcode == INVOKEVIRTUAL || opcode == INVOKEINTERFACE) {
            operands.add(0, stack.pop());
        }
        IrType returnType = returnType(methodInsn.desc);
        String methodKey = methodInsn.owner + "#" + methodInsn.name + "!" + methodInsn.desc;
        if (lowerUnsafeCall(
                currentMethod,
                methodInsn,
                opcode,
                operands,
                returnType,
                methodKey,
                values,
                stack,
                instructions,
                diagnostics)) {
            return;
        }
        if (lowerMethodHandleCall(
                currentMethod,
                methodInsn,
                opcode,
                operands,
                returnType,
                methodKey,
                values,
                stack,
                instructions,
                diagnostics)) {
            return;
        }
        if (lowerReflectionCall(
                currentMethod,
                methodInsn,
                opcode,
                operands,
                returnType,
                methodKey,
                values,
                stack,
                instructions,
                diagnostics)) {
            return;
        }
        java.util.Optional<JdkIntrinsic> jdkIntrinsic = jdkIntrinsics.lookup(
                methodInsn.owner,
                methodInsn.name,
                methodInsn.desc);
        if (jdkIntrinsic.isPresent()) {
            if (lowerJdkCall(jdkIntrinsic.orElseThrow(), currentMethod, operands, returnType, values, stack, instructions, diagnostics)) {
                return;
            }
        } else if (isJdkOwner(methodInsn.owner)) {
            addJvmHelperFallbackDiagnostic(
                    currentMethod,
                    methodKey,
                    "JDK method has no native policy yet",
                    diagnostics);
        }
        IrInstruction call = appendOrdinaryCall(
                callOpcode(opcode),
                operands,
                returnType,
                values,
                stack,
                instructions,
                methodKey);
        if (isThreadStart(methodInsn, opcode) && !operands.isEmpty()) {
            instructions.add(memoryMarker(IrOpcode.THREAD_START_HAPPENS_BEFORE, List.of(operands.get(0)), methodKey));
        } else if (isThreadJoin(methodInsn, opcode) && !operands.isEmpty()) {
            instructions.add(memoryMarker(IrOpcode.THREAD_JOIN_HAPPENS_BEFORE, List.of(operands.get(0)), methodKey));
        }
    }

    private IrInstruction appendOrdinaryCall(
            IrOpcode opcode,
            List<IrValue> operands,
            IrType returnType,
            ValueFactory values,
            StackState stack,
            List<IrInstruction> instructions,
            String methodKey) {
        IrInstruction call;
        if (returnType == IrType.VOID) {
            call = IrInstruction.call(
                    java.util.Optional.empty(),
                    opcode,
                    operands,
                    methodKey);
        } else {
            IrValue result = values.next(returnType);
            call = IrInstruction.call(
                    java.util.Optional.of(result),
                    opcode,
                    operands,
                    methodKey);
            stack.push(result);
        }
        instructions.add(call);
        return call;
    }

    private boolean lowerJdkCall(
            JdkIntrinsic intrinsic,
            ParsedMethod currentMethod,
            List<IrValue> operands,
            IrType returnType,
            ValueFactory values,
            StackState stack,
            List<IrInstruction> instructions,
            List<Diagnostic> diagnostics) {
        if (intrinsic.policy() == JdkMethodPolicy.DIRECT_NATIVE_LOWERING) {
            return true;
        }
        if (intrinsic.policy() == JdkMethodPolicy.RUNTIME_HELPER) {
            String helperSymbol = runtimeHelperSymbol(intrinsic.helperKind().orElseThrow());
            if (returnType == IrType.VOID) {
                instructions.add(IrInstruction.call(
                        java.util.Optional.empty(),
                        IrOpcode.CALL_RUNTIME_HELPER,
                        operands,
                        helperSymbol));
            } else {
                IrValue result = values.next(returnType);
                instructions.add(IrInstruction.call(
                        java.util.Optional.of(result),
                        IrOpcode.CALL_RUNTIME_HELPER,
                        operands,
                        helperSymbol));
                stack.push(result);
            }
            return true;
        }
        if (intrinsic.policy() == JdkMethodPolicy.JVM_HELPER_FALLBACK) {
            addJvmHelperFallbackDiagnostic(
                    currentMethod,
                    intrinsic.method().methodKey(),
                    intrinsic.reason(),
                    diagnostics);
            return false;
        }
        return false;
    }

    private boolean lowerMethodHandleCall(
            ParsedMethod currentMethod,
            MethodInsnNode methodInsn,
            int opcode,
            List<IrValue> operands,
            IrType returnType,
            String methodKey,
            ValueFactory values,
            StackState stack,
            List<IrInstruction> instructions,
            List<Diagnostic> diagnostics) {
        if (!methodInsn.owner.equals("java/lang/invoke/MethodHandle")
                || !(methodInsn.name.equals("invokeExact") || methodInsn.name.equals("invoke"))) {
            return false;
        }
        if (operands.isEmpty()) {
            addJvmHelperFallbackDiagnostic(
                    currentMethod,
                    methodKey,
                    "MethodHandle call has no receiver",
                    diagnostics);
            appendOrdinaryCall(callOpcode(opcode), operands, returnType, values, stack, instructions, methodKey);
            return true;
        }
        java.util.Optional<MethodHandleConstant> constant = methodHandleConstant(operands.get(0), instructions);
        if (constant.isEmpty()) {
            addJvmHelperFallbackDiagnostic(
                    currentMethod,
                    methodKey,
                    "unsupported MethodHandle chain requires JVM helper fallback",
                    diagnostics);
            appendOrdinaryCall(callOpcode(opcode), operands, returnType, values, stack, instructions, methodKey);
            return true;
        }
        MethodHandleConstant handle = constant.orElseThrow();
        appendOrdinaryCall(
                callOpcodeForHandleTag(handle.tag()),
                operands.subList(1, operands.size()),
                returnType,
                values,
                stack,
                instructions,
                handle.owner() + "#" + handle.name() + "!" + handle.descriptor());
        return true;
    }

    private boolean lowerUnsafeCall(
            ParsedMethod currentMethod,
            MethodInsnNode methodInsn,
            int opcode,
            List<IrValue> operands,
            IrType returnType,
            String methodKey,
            ValueFactory values,
            StackState stack,
            List<IrInstruction> instructions,
            List<Diagnostic> diagnostics) {
        UnsafePlan plan = unsafePolicy.plan(methodInsn.owner, methodInsn.name, methodInsn.desc);
        if (!plan.unsafeOrVarHandleCall()) {
            return false;
        }
        if (!plan.supported()) {
            addJvmHelperFallbackDiagnostic(currentMethod, methodKey, plan.reason(), diagnostics);
            appendOrdinaryCall(callOpcode(opcode), operands, returnType, values, stack, instructions, methodKey);
            return true;
        }
        if (plan.kind() == UnsafeOperationKind.PUT_VOLATILE
                || plan.kind() == UnsafeOperationKind.VAR_HANDLE_SET_VOLATILE
                || plan.compareAndSwap()) {
            instructions.add(memoryMarker(IrOpcode.VOLATILE_WRITE_BARRIER, operands, methodKey));
        }
        xyz.melodysky.runtime.RuntimeHelperKind helperKind = plan.helperKind().orElseThrow();
        List<IrValue> helperOperands = operands;
        if ((methodInsn.owner.equals("sun/misc/Unsafe") || methodInsn.owner.equals("jdk/internal/misc/Unsafe"))
                && dropsUnsafeReceiver(helperKind)) {
            helperOperands = operands.size() > 1 ? List.copyOf(operands.subList(1, operands.size())) : List.of();
        }
        if (helperKind == xyz.melodysky.runtime.RuntimeHelperKind.UNSAFE_ALLOCATE_INSTANCE
                && helperOperands.size() == 1) {
            IrValue classOperand = helperOperands.get(0);
            java.util.Optional<String> className = classInternalNameForValue(classOperand, instructions);
            if (className.isPresent()) {
                ClassObjectReference classObject =
                        createClassObjectReference(classDescriptor(className.orElseThrow()), values);
                instructions.addAll(classObject.instructions());
                helperOperands = List.of(classObject.value());
                removeDefinition(classOperand, instructions, IrOpcode.CONST_CLASS);
            }
        }
        IrInstruction helperCall;
        if (returnType == IrType.VOID) {
            helperCall = IrInstruction.call(
                    java.util.Optional.empty(),
                    IrOpcode.CALL_RUNTIME_HELPER,
                    helperOperands,
                    runtimeHelperSymbol(helperKind));
        } else {
            IrValue result = values.next(returnType);
            helperCall = IrInstruction.call(
                    java.util.Optional.of(result),
                    IrOpcode.CALL_RUNTIME_HELPER,
                    helperOperands,
                    runtimeHelperSymbol(helperKind));
            stack.push(result);
        }
        instructions.add(helperCall);
        if (plan.kind() == UnsafeOperationKind.GET_VOLATILE
                || plan.kind() == UnsafeOperationKind.VAR_HANDLE_GET_VOLATILE
                || plan.compareAndSwap()) {
            instructions.add(memoryMarker(
                    IrOpcode.VOLATILE_READ_BARRIER,
                    helperCall.result().map(List::of).orElse(operands),
                    methodKey));
        }
        return true;
    }

    private boolean dropsUnsafeReceiver(xyz.melodysky.runtime.RuntimeHelperKind helperKind) {
        return helperKind == xyz.melodysky.runtime.RuntimeHelperKind.UNSAFE_OBJECT_FIELD_OFFSET
                || helperKind == xyz.melodysky.runtime.RuntimeHelperKind.UNSAFE_STATIC_FIELD_OFFSET
                || helperKind == xyz.melodysky.runtime.RuntimeHelperKind.UNSAFE_ARRAY_BASE_OFFSET
                || helperKind == xyz.melodysky.runtime.RuntimeHelperKind.UNSAFE_ARRAY_INDEX_SCALE
                || helperKind == xyz.melodysky.runtime.RuntimeHelperKind.UNSAFE_GET_INT
                || helperKind == xyz.melodysky.runtime.RuntimeHelperKind.UNSAFE_PUT_INT
                || helperKind == xyz.melodysky.runtime.RuntimeHelperKind.UNSAFE_COMPARE_AND_SWAP_INT
                || helperKind == xyz.melodysky.runtime.RuntimeHelperKind.UNSAFE_ALLOCATE_INSTANCE;
    }

    private boolean lowerReflectionCall(
            ParsedMethod currentMethod,
            MethodInsnNode methodInsn,
            int opcode,
            List<IrValue> operands,
            IrType returnType,
            String methodKey,
            ValueFactory values,
            StackState stack,
            List<IrInstruction> instructions,
            List<Diagnostic> diagnostics) {
        if (methodInsn.owner.equals("java/lang/Class")
                && methodInsn.name.equals("forName")
                && methodInsn.desc.equals("(Ljava/lang/String;)Ljava/lang/Class;")) {
            java.util.Optional<String> className = stringLiteral(operands.get(0), instructions);
            if (className.isEmpty()) {
                addJvmHelperFallbackDiagnostic(
                        currentMethod,
                        methodKey,
                        "dynamic Class.forName string requires JVM helper fallback",
                        diagnostics);
                appendOrdinaryCall(callOpcode(opcode), operands, returnType, values, stack, instructions, methodKey);
                return true;
            }
            IrValue initialize = values.next(IrType.I32);
            instructions.add(IrInstruction.constInt(initialize, 1));
            lowerClassForNameStatic(className.orElseThrow(), initialize, values, stack, instructions);
            removeDefinition(operands.get(0), instructions, IrOpcode.CONST_STRING);
            return true;
        }
        if (methodInsn.owner.equals("java/lang/Class")
                && methodInsn.name.equals("forName")
                && methodInsn.desc.equals("(Ljava/lang/String;ZLjava/lang/ClassLoader;)Ljava/lang/Class;")) {
            java.util.Optional<String> className = stringLiteral(operands.get(0), instructions);
            if (className.isEmpty()) {
                addJvmHelperFallbackDiagnostic(
                        currentMethod,
                        methodKey,
                        "dynamic Class.forName string requires JVM helper fallback",
                        diagnostics);
                appendOrdinaryCall(callOpcode(opcode), operands, returnType, values, stack, instructions, methodKey);
                return true;
            }
            lowerClassForNameStatic(className.orElseThrow(), operands.get(1), values, stack, instructions);
            removeDefinition(operands.get(0), instructions, IrOpcode.CONST_STRING);
            return true;
        }
        if (methodInsn.owner.equals("java/lang/Class")
                && methodInsn.name.equals("getDeclaredMethod")
                && methodInsn.desc.equals("(Ljava/lang/String;[Ljava/lang/Class;)Ljava/lang/reflect/Method;")) {
            java.util.Optional<String> owner = classInternalNameForValue(operands.get(0), instructions);
            java.util.Optional<String> name = stringLiteral(operands.get(1), instructions);
            java.util.Optional<List<String>> parameters = classArrayDescriptors(operands.get(2), instructions);
            if (owner.isEmpty() || name.isEmpty() || parameters.isEmpty()) {
                addJvmHelperFallbackDiagnostic(
                        currentMethod,
                        methodKey,
                        "dynamic getDeclaredMethod target requires JVM helper fallback",
                        diagnostics);
                appendOrdinaryCall(callOpcode(opcode), operands, returnType, values, stack, instructions, methodKey);
                return true;
            }
            lowerMetadataLookup(
                    "method:" + owner.orElseThrow() + "#" + name.orElseThrow()
                            + "!(" + String.join("", parameters.orElseThrow()) + ")",
                    xyz.melodysky.runtime.RuntimeHelperKind.GET_DECLARED_METHOD,
                    values,
                    stack,
                    instructions);
            removeDefinition(operands.get(0), instructions, IrOpcode.CONST_CLASS);
            removeDefinition(operands.get(1), instructions, IrOpcode.CONST_STRING);
            return true;
        }
        if (methodInsn.owner.equals("java/lang/Class")
                && methodInsn.name.equals("getDeclaredField")
                && methodInsn.desc.equals("(Ljava/lang/String;)Ljava/lang/reflect/Field;")) {
            java.util.Optional<String> owner = classInternalNameForValue(operands.get(0), instructions);
            java.util.Optional<String> name = stringLiteral(operands.get(1), instructions);
            if (owner.isEmpty() || name.isEmpty()) {
                addJvmHelperFallbackDiagnostic(
                        currentMethod,
                        methodKey,
                        "dynamic getDeclaredField target requires JVM helper fallback",
                        diagnostics);
                appendOrdinaryCall(callOpcode(opcode), operands, returnType, values, stack, instructions, methodKey);
                return true;
            }
            lowerMetadataLookup(
                    "field:" + owner.orElseThrow() + "#" + name.orElseThrow(),
                    xyz.melodysky.runtime.RuntimeHelperKind.GET_DECLARED_FIELD,
                    values,
                    stack,
                    instructions);
            removeDefinition(operands.get(0), instructions, IrOpcode.CONST_CLASS);
            removeDefinition(operands.get(1), instructions, IrOpcode.CONST_STRING);
            return true;
        }
        if (methodInsn.owner.equals("java/lang/Class")
                && methodInsn.name.equals("getDeclaredConstructor")
                && methodInsn.desc.equals("([Ljava/lang/Class;)Ljava/lang/reflect/Constructor;")) {
            java.util.Optional<String> owner = classInternalNameForValue(operands.get(0), instructions);
            java.util.Optional<List<String>> parameters = classArrayDescriptors(operands.get(1), instructions);
            if (owner.isEmpty() || parameters.isEmpty()) {
                addJvmHelperFallbackDiagnostic(
                        currentMethod,
                        methodKey,
                        "dynamic getDeclaredConstructor target requires JVM helper fallback",
                        diagnostics);
                appendOrdinaryCall(callOpcode(opcode), operands, returnType, values, stack, instructions, methodKey);
                return true;
            }
            lowerMetadataLookup(
                    "constructor:" + owner.orElseThrow() + "#<init>!("
                            + String.join("", parameters.orElseThrow()) + ")V",
                    xyz.melodysky.runtime.RuntimeHelperKind.GET_DECLARED_CONSTRUCTOR,
                    values,
                    stack,
                    instructions);
            removeDefinition(operands.get(0), instructions, IrOpcode.CONST_CLASS);
            return true;
        }
        if (methodInsn.owner.equals("java/lang/reflect/Method")
                && methodInsn.name.equals("invoke")
                && methodInsn.desc.equals("(Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;")) {
            IrValue result = values.next(IrType.REFERENCE);
            instructions.add(IrInstruction.call(
                    java.util.Optional.of(result),
                    IrOpcode.CALL_RUNTIME_HELPER,
                    operands,
                    runtimeHelperSymbol(xyz.melodysky.runtime.RuntimeHelperKind.REFLECT_INVOKE)));
            stack.push(result);
            return true;
        }
        if (methodInsn.owner.equals("java/lang/reflect/Constructor")
                && methodInsn.name.equals("newInstance")
                && methodInsn.desc.equals("([Ljava/lang/Object;)Ljava/lang/Object;")) {
            IrValue result = values.next(IrType.REFERENCE);
            instructions.add(IrInstruction.call(
                    java.util.Optional.of(result),
                    IrOpcode.CALL_RUNTIME_HELPER,
                    operands,
                    runtimeHelperSymbol(xyz.melodysky.runtime.RuntimeHelperKind.REFLECT_NEW_INSTANCE)));
            stack.push(result);
            return true;
        }
        if (methodInsn.owner.equals("java/lang/reflect/Field")
                && methodInsn.name.equals("get")
                && methodInsn.desc.equals("(Ljava/lang/Object;)Ljava/lang/Object;")) {
            IrValue result = values.next(IrType.REFERENCE);
            instructions.add(IrInstruction.call(
                    java.util.Optional.of(result),
                    IrOpcode.CALL_RUNTIME_HELPER,
                    operands,
                    runtimeHelperSymbol(xyz.melodysky.runtime.RuntimeHelperKind.REFLECT_FIELD_GET)));
            stack.push(result);
            return true;
        }
        if (methodInsn.owner.equals("java/lang/reflect/Field")
                && methodInsn.name.equals("set")
                && methodInsn.desc.equals("(Ljava/lang/Object;Ljava/lang/Object;)V")) {
            instructions.add(IrInstruction.call(
                    java.util.Optional.empty(),
                    IrOpcode.CALL_RUNTIME_HELPER,
                    operands,
                    runtimeHelperSymbol(xyz.melodysky.runtime.RuntimeHelperKind.REFLECT_FIELD_SET)));
            return true;
        }
        if (methodInsn.owner.equals("java/lang/reflect/Field")
                && methodInsn.name.equals("getInt")
                && methodInsn.desc.equals("(Ljava/lang/Object;)I")) {
            IrValue result = values.next(IrType.I32);
            instructions.add(IrInstruction.call(
                    java.util.Optional.of(result),
                    IrOpcode.CALL_RUNTIME_HELPER,
                    operands,
                    runtimeHelperSymbol(xyz.melodysky.runtime.RuntimeHelperKind.REFLECT_FIELD_GET_INT)));
            stack.push(result);
            return true;
        }
        if (methodInsn.owner.equals("java/lang/reflect/Field")
                && methodInsn.name.equals("setInt")
                && methodInsn.desc.equals("(Ljava/lang/Object;I)V")) {
            instructions.add(IrInstruction.call(
                    java.util.Optional.empty(),
                    IrOpcode.CALL_RUNTIME_HELPER,
                    operands,
                    runtimeHelperSymbol(xyz.melodysky.runtime.RuntimeHelperKind.REFLECT_FIELD_SET_INT)));
            return true;
        }
        return false;
    }

    private void lowerClassForNameStatic(
            String binaryName,
            IrValue initialize,
            ValueFactory values,
            StackState stack,
            List<IrInstruction> instructions) {
        String descriptor = classDescriptor(binaryName.replace('.', '/'));
        IrValue classId = values.next(IrType.I64);
        instructions.add(IrInstruction.constLong(classId, stableClassId(descriptor)));
        IrValue result = values.next(IrType.REFERENCE);
        instructions.add(IrInstruction.call(
                java.util.Optional.of(result),
                IrOpcode.CALL_RUNTIME_HELPER,
                List.of(classId, initialize),
                runtimeHelperSymbol(xyz.melodysky.runtime.RuntimeHelperKind.CLASS_FOR_NAME_STATIC)
                        + "|class:" + descriptor));
        stack.push(result);
    }

    private void lowerMetadataLookup(
            String metadataKey,
            xyz.melodysky.runtime.RuntimeHelperKind helperKind,
            ValueFactory values,
            StackState stack,
            List<IrInstruction> instructions) {
        IrValue metadataId = values.next(IrType.I64);
        instructions.add(IrInstruction.constLong(metadataId, stableClassId(metadataKey)));
        IrValue result = values.next(IrType.REFERENCE);
        instructions.add(IrInstruction.call(
                java.util.Optional.of(result),
                IrOpcode.CALL_RUNTIME_HELPER,
                List.of(metadataId),
                runtimeHelperSymbol(helperKind) + "|" + metadataKey));
        stack.push(result);
    }

    private java.util.Optional<String> stringLiteral(IrValue value, List<IrInstruction> instructions) {
        return definingInstruction(value, instructions)
                .filter(instruction -> instruction.opcode() == IrOpcode.CONST_STRING)
                .flatMap(IrInstruction::symbol)
                .filter(symbol -> symbol.startsWith("string:"))
                .map(symbol -> symbol.substring("string:".length()));
    }

    private java.util.Optional<Integer> intLiteral(IrValue value, List<IrInstruction> instructions) {
        return definingInstruction(value, instructions)
                .filter(instruction -> instruction.opcode() == IrOpcode.CONST_INT)
                .flatMap(IrInstruction::intLiteral);
    }

    private java.util.Optional<String> classInternalNameForValue(IrValue value, List<IrInstruction> instructions) {
        return definingInstruction(value, instructions)
                .filter(instruction -> instruction.opcode() == IrOpcode.CONST_CLASS)
                .flatMap(IrInstruction::symbol)
                .filter(symbol -> symbol.startsWith("class:L") && symbol.endsWith(";"))
                .map(symbol -> symbol.substring("class:L".length(), symbol.length() - 1));
    }

    private java.util.Optional<List<String>> classArrayDescriptors(IrValue value, List<IrInstruction> instructions) {
        java.util.Optional<IrInstruction> allocation = definingInstruction(value, instructions)
                .filter(instruction -> instruction.opcode() == IrOpcode.NEW_ARRAY)
                .filter(instruction -> instruction.symbol().orElse("").equals("referenceArray:java/lang/Class"));
        if (allocation.isEmpty()) {
            return java.util.Optional.empty();
        }
        List<IrValue> allocationOperands = allocation.orElseThrow().operands();
        if (allocationOperands.size() != 1) {
            return java.util.Optional.empty();
        }
        java.util.Optional<Integer> size = intLiteral(allocationOperands.get(0), instructions);
        if (size.isEmpty() || size.orElseThrow() < 0) {
            return java.util.Optional.empty();
        }
        ArrayList<String> descriptors = new ArrayList<>(java.util.Collections.nCopies(size.orElseThrow(), null));
        for (IrInstruction instruction : instructions) {
            if (instruction.opcode() != IrOpcode.ARRAY_STORE_REF || instruction.operands().size() != 3) {
                continue;
            }
            if (!instruction.operands().get(0).equals(value)) {
                continue;
            }
            java.util.Optional<Integer> index = intLiteral(instruction.operands().get(1), instructions);
            java.util.Optional<String> className = classInternalNameForValue(instruction.operands().get(2), instructions);
            if (index.isEmpty() || className.isEmpty()) {
                return java.util.Optional.empty();
            }
            int slot = index.orElseThrow();
            if (slot < 0 || slot >= descriptors.size()) {
                return java.util.Optional.empty();
            }
            descriptors.set(slot, classDescriptor(className.orElseThrow()));
        }
        if (descriptors.stream().anyMatch(java.util.Objects::isNull)) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(List.copyOf(descriptors));
    }

    private void removeDefinition(IrValue value, List<IrInstruction> instructions, IrOpcode opcode) {
        instructions.removeIf(instruction -> instruction.opcode() == opcode
                && instruction.result().map(result -> result.name().equals(value.name())).orElse(false));
    }

    private java.util.Optional<IrInstruction> definingInstruction(IrValue value, List<IrInstruction> instructions) {
        for (int index = instructions.size() - 1; index >= 0; index--) {
            IrInstruction instruction = instructions.get(index);
            if (instruction.result().filter(value::equals).isPresent()) {
                return java.util.Optional.of(instruction);
            }
        }
        return java.util.Optional.empty();
    }

    private java.util.Optional<MethodHandleConstant> methodHandleConstant(
            IrValue value,
            List<IrInstruction> instructions) {
        return definingInstruction(value, instructions)
                .filter(instruction -> instruction.opcode() == IrOpcode.CONST_METHOD_HANDLE)
                .flatMap(IrInstruction::symbol)
                .flatMap(this::parseMethodHandleSymbol);
    }

    private java.util.Optional<MethodHandleConstant> parseMethodHandleSymbol(String symbol) {
        if (!symbol.startsWith("methodHandle:")) {
            return java.util.Optional.empty();
        }
        String body = symbol.substring("methodHandle:".length());
        int firstColon = body.indexOf(':');
        int hash = body.indexOf('#', firstColon + 1);
        int bang = body.indexOf('!', hash + 1);
        int lastColon = body.lastIndexOf(':');
        if (firstColon < 0 || hash < 0 || bang < 0 || lastColon <= bang) {
            return java.util.Optional.empty();
        }
        int tag = Integer.parseInt(body.substring(0, firstColon));
        return java.util.Optional.of(new MethodHandleConstant(
                tag,
                body.substring(firstColon + 1, hash),
                body.substring(hash + 1, bang),
                body.substring(bang + 1, lastColon),
                Boolean.parseBoolean(body.substring(lastColon + 1))));
    }

    private IrOpcode callOpcodeForHandleTag(int tag) {
        return switch (tag) {
            case H_INVOKESTATIC -> IrOpcode.CALL_STATIC;
            case H_INVOKEVIRTUAL -> IrOpcode.CALL_VIRTUAL;
            case H_INVOKEINTERFACE -> IrOpcode.CALL_INTERFACE;
            case H_INVOKESPECIAL, H_NEWINVOKESPECIAL -> IrOpcode.CALL_SPECIAL;
            default -> IrOpcode.CALL_DYNAMIC;
        };
    }

    private String runtimeHelperSymbol(xyz.melodysky.runtime.RuntimeHelperKind helperKind) {
        return runtimeHelpers.helper(helperKind).orElseThrow().llvmSymbol();
    }

    private boolean isJdkOwner(String owner) {
        return owner.startsWith("java/") || owner.startsWith("jdk/");
    }

    private void addJvmHelperFallbackDiagnostic(
            ParsedMethod currentMethod,
            String methodKey,
            String reason,
            List<Diagnostic> diagnostics) {
        diagnostics.add(Diagnostic.warning(
                        DiagnosticStage.LOWERING,
                        DiagnosticCode.JVM_HELPER_FALLBACK,
                        methodKey + " uses JVM helper fallback: " + reason)
                .at(location(currentMethod))
                .withDecision(LoweringStatus.HALF_LOWERED.wireName())
                .withConservativeFallbackAvailable(true));
    }

    private void lowerDynamicCall(
            ParsedMethod currentMethod,
            InvokeDynamicInsnNode invokeDynamicInsn,
            ValueFactory values,
            StackState stack,
            List<IrInstruction> instructions,
            List<Diagnostic> diagnostics) {
        ArrayList<IrValue> operands = new ArrayList<>();
        List<IrType> parameterTypes = parameterTypes(invokeDynamicInsn.desc);
        for (int index = parameterTypes.size() - 1; index >= 0; index--) {
            operands.add(0, stack.pop());
        }
        IrType returnType = returnType(invokeDynamicInsn.desc);
        StringConcatBootstrapPlan concatPlan = stringConcatFactory.parse(
                invokeDynamicInsn.name,
                invokeDynamicInsn.bsm,
                invokeDynamicInsn.bsmArgs);
        if (concatPlan.stringConcatFactory()) {
            if (concatPlan.supported() && returnType == IrType.REFERENCE) {
                lowerStringConcat(concatPlan, parameterTypes, operands, values, stack, instructions);
                return;
            }
            addJvmHelperFallbackDiagnostic(
                    currentMethod,
                    "indy:" + invokeDynamicInsn.name + "!" + invokeDynamicInsn.desc,
                    concatPlan.reason(),
                    diagnostics);
        }
        LambdaMetafactoryPlan lambdaPlan = lambdaMetafactory.parse(invokeDynamicInsn.bsm, invokeDynamicInsn.bsmArgs);
        if (lambdaPlan.lambdaMetafactory()) {
            if (lambdaPlan.supported()
                    && returnType == IrType.REFERENCE
                    && lambdaCapturesAreSupported(operands)) {
                lowerLambdaMetafactory(lambdaPlan, currentMethod, invokeDynamicInsn, operands, values, stack, instructions);
                return;
            }
            String reason = lambdaPlan.supported()
                    ? "unsupported lambda capture shape"
                    : lambdaPlan.reason();
            addJvmHelperFallbackDiagnostic(
                    currentMethod,
                    "indy:" + invokeDynamicInsn.name + "!" + invokeDynamicInsn.desc,
                    reason,
                    diagnostics);
        }
        String methodKey = "indy:" + invokeDynamicInsn.name
                + "!" + invokeDynamicInsn.desc
                + ":bsm:" + handleKey(invokeDynamicInsn.bsm)
                + ":args:" + invokeDynamicInsn.bsmArgs.length;
        if (returnType == IrType.VOID) {
            instructions.add(IrInstruction.call(
                    java.util.Optional.empty(),
                    IrOpcode.CALL_DYNAMIC,
                    operands,
                    methodKey));
        } else {
            IrValue result = values.next(returnType);
            instructions.add(IrInstruction.call(
                    java.util.Optional.of(result),
                    IrOpcode.CALL_DYNAMIC,
                    operands,
                    methodKey));
            stack.push(result);
        }
    }

    private void lowerStringConcat(
            StringConcatBootstrapPlan concatPlan,
            List<IrType> parameterTypes,
            List<IrValue> operands,
            ValueFactory values,
            StackState stack,
            List<IrInstruction> instructions) {
        IrValue builder = values.next(IrType.REFERENCE);
        instructions.add(IrInstruction.call(
                java.util.Optional.of(builder),
                IrOpcode.CALL_RUNTIME_HELPER,
                List.of(),
                runtimeHelperSymbol(xyz.melodysky.runtime.RuntimeHelperKind.STRING_BUILDER_NEW)));
        List<StringConcatToken> tokens = concatPlan.tokens().isEmpty()
                ? defaultConcatTokens(operands.size())
                : concatPlan.tokens();
        for (StringConcatToken token : tokens) {
            if (token.kind() == StringConcatTokenKind.OPERAND) {
                IrType operandType = parameterTypes.get(token.operandIndex());
                builder = appendStringBuilderValue(
                        builder,
                        operands.get(token.operandIndex()),
                        operandType,
                        values,
                        instructions);
            } else {
                IrValue constantId = values.next(IrType.I64);
                instructions.add(IrInstruction.constLong(constantId, stableClassId("string:" + token.constant())));
                IrValue constant = values.next(IrType.REFERENCE);
                instructions.add(IrInstruction.call(
                        java.util.Optional.of(constant),
                        IrOpcode.CALL_RUNTIME_HELPER,
                        List.of(constantId),
                        runtimeHelperSymbol(xyz.melodysky.runtime.RuntimeHelperKind.STRING_CONSTANT)
                                + "|string:" + token.constant()));
                builder = appendStringBuilderValue(builder, constant, IrType.REFERENCE, values, instructions);
            }
        }
        IrValue result = values.next(IrType.REFERENCE);
        instructions.add(IrInstruction.call(
                java.util.Optional.of(result),
                IrOpcode.CALL_RUNTIME_HELPER,
                List.of(builder),
                runtimeHelperSymbol(xyz.melodysky.runtime.RuntimeHelperKind.STRING_BUILDER_TO_STRING)));
        stack.push(result);
    }

    private List<StringConcatToken> defaultConcatTokens(int operandCount) {
        ArrayList<StringConcatToken> tokens = new ArrayList<>();
        for (int index = 0; index < operandCount; index++) {
            tokens.add(StringConcatToken.operand(index));
        }
        return List.copyOf(tokens);
    }

    private IrValue appendStringBuilderValue(
            IrValue builder,
            IrValue value,
            IrType type,
            ValueFactory values,
            List<IrInstruction> instructions) {
        xyz.melodysky.runtime.RuntimeHelperKind helperKind = switch (type) {
            case I64 -> xyz.melodysky.runtime.RuntimeHelperKind.STRING_BUILDER_APPEND_I64;
            case F32 -> xyz.melodysky.runtime.RuntimeHelperKind.STRING_BUILDER_APPEND_F32;
            case F64 -> xyz.melodysky.runtime.RuntimeHelperKind.STRING_BUILDER_APPEND_F64;
            case I32, I1 -> xyz.melodysky.runtime.RuntimeHelperKind.STRING_BUILDER_APPEND_I32;
            case REFERENCE -> xyz.melodysky.runtime.RuntimeHelperKind.STRING_BUILDER_APPEND_REF;
            case VOID -> throw new IllegalArgumentException("cannot append void string concat operand");
        };
        IrValue nextBuilder = values.next(IrType.REFERENCE);
        instructions.add(IrInstruction.call(
                java.util.Optional.of(nextBuilder),
                IrOpcode.CALL_RUNTIME_HELPER,
                List.of(builder, value),
                runtimeHelperSymbol(helperKind)));
        return nextBuilder;
    }

    private boolean lambdaCapturesAreSupported(List<IrValue> operands) {
        return operands.size() <= 1
                && operands.stream().allMatch(operand -> operand.type() == IrType.REFERENCE);
    }

    private void lowerLambdaMetafactory(
            LambdaMetafactoryPlan lambdaPlan,
            ParsedMethod currentMethod,
            InvokeDynamicInsnNode invokeDynamicInsn,
            List<IrValue> operands,
            ValueFactory values,
            StackState stack,
            List<IrInstruction> instructions) {
        String lambdaSpec = lambdaSpec(lambdaPlan, currentMethod, invokeDynamicInsn);
        IrValue targetId = values.next(IrType.I64);
        instructions.add(IrInstruction.constLong(
                targetId,
                stableClassId("lambda:" + lambdaSpec)));
        IrValue capture = operands.isEmpty() ? values.next(IrType.REFERENCE) : operands.get(0);
        if (operands.isEmpty()) {
            instructions.add(IrInstruction.constNull(capture));
        }
        IrValue lambdaObject = values.next(IrType.REFERENCE);
        instructions.add(IrInstruction.call(
                java.util.Optional.of(lambdaObject),
                IrOpcode.CALL_RUNTIME_HELPER,
                List.of(targetId, capture),
                runtimeHelperSymbol(xyz.melodysky.runtime.RuntimeHelperKind.LAMBDA_NEW)
                        + "|lambda:"
                        + Base64.getUrlEncoder()
                                .withoutPadding()
                                .encodeToString(lambdaSpec.getBytes(StandardCharsets.UTF_8))));
        stack.push(lambdaObject);
    }

    private String lambdaSpec(
            LambdaMetafactoryPlan lambdaPlan,
            ParsedMethod currentMethod,
            InvokeDynamicInsnNode invokeDynamicInsn) {
        Handle implementation = lambdaPlan.implementationHandle().orElseThrow();
        String samDescriptor = invokeDynamicInsn.bsmArgs.length > 0 && invokeDynamicInsn.bsmArgs[0] instanceof Type sam
                ? sam.getDescriptor()
                : "()V";
        String instantiatedDescriptor =
                invokeDynamicInsn.bsmArgs.length > 2 && invokeDynamicInsn.bsmArgs[2] instanceof Type instantiated
                        ? instantiated.getDescriptor()
                        : samDescriptor;
        return String.join(
                "\n",
                currentMethod.owner(),
                invokeDynamicInsn.name,
                invokeDynamicInsn.desc,
                samDescriptor,
                Integer.toString(implementation.getTag()),
                implementation.getOwner(),
                implementation.getName(),
                implementation.getDesc(),
                instantiatedDescriptor);
    }

    private void lowerMonitorInstruction(
            int opcode,
            StackState stack,
            List<IrExceptionEdge> exceptionEdges,
            List<IrInstruction> instructions,
            boolean exceptionalMonitorCleanup) {
        IrValue monitor = stack.pop();
        IrOpcode irOpcode = opcode == MONITORENTER
                ? IrOpcode.MONITOR_ENTER
                : exceptionalMonitorCleanup ? IrOpcode.MONITOR_EXIT_ON_EXCEPTION : IrOpcode.MONITOR_EXIT;
        appendMonitorHelper(
                irOpcode,
                monitor,
                exceptionEdges,
                instructions,
                opcode == MONITORENTER ? "monitorEnter" : "monitorExit");
    }

    private IrTerminator lowerConditionalBranch(
            BytecodeCfg cfg,
            BytecodeBasicBlock block,
            int opcode,
            StackState stack,
            ValueFactory values,
            List<IrInstruction> instructions) {
        IrValue right;
        IrValue left;
        if (isIntZeroBranch(opcode)) {
            left = stack.pop();
            right = values.next(IrType.I32);
            instructions.add(IrInstruction.constInt(right, 0));
        } else if (isNullBranch(opcode)) {
            left = stack.pop();
            right = values.next(IrType.REFERENCE);
            instructions.add(IrInstruction.constNull(right));
        } else {
            right = stack.pop();
            left = stack.pop();
        }
        IrValue condition = values.next(IrType.I1);
        instructions.add(IrInstruction.binary(condition, compareOpcode(opcode), left, right));
        BytecodeEdge trueEdge = branchEdge(cfg, block);
        BytecodeEdge falseEdge = edge(cfg, block, BytecodeEdgeKind.FALLTHROUGH);
        if (falseEdge == null) {
            throw new UnsupportedOperationException("conditional branch has no fallthrough edge");
        }
        return IrTerminator.branch(condition, blockName(trueEdge.toBlockId()), blockName(falseEdge.toBlockId()));
    }

    private IrTerminator lowerSwitch(BytecodeCfg cfg, BytecodeBasicBlock block, StackState stack) {
        IrValue selector = stack.pop();
        ArrayList<IrSwitchCase> cases = new ArrayList<>();
        String defaultTarget = null;
        for (BytecodeEdge edge : cfg.edges().stream()
                .filter(candidate -> candidate.fromBlockId() == block.id() && candidate.kind() == BytecodeEdgeKind.SWITCH)
                .toList()) {
            if ("default".equals(edge.detail())) {
                defaultTarget = blockName(edge.toBlockId());
            } else {
                cases.add(new IrSwitchCase(Integer.parseInt(edge.detail()), blockName(edge.toBlockId())));
            }
        }
        if (defaultTarget == null) {
            throw new UnsupportedOperationException("switch instruction has no default edge");
        }
        return IrTerminator.switchOn(selector, defaultTarget, cases);
    }

    private FrameState seedEntryState(ParsedMethod method, List<IrValue> parameters) {
        LocalState locals = new LocalState();
        seedParameterLocals(method, parameters, locals);
        return new FrameState(List.of(), locals.snapshot());
    }

    private void seedExceptionHandlers(
            ParsedMethod method,
            BytecodeCfg cfg,
            ValueFactory values,
            List<IrValue> parameters,
            Map<Integer, BlockInput> inputs,
            ArrayDeque<Integer> worklist,
            Set<Integer> queued) {
        for (BytecodeBasicBlock block : cfg.blocks()) {
            if (!block.isExceptionHandler()) {
                continue;
            }
            IrValue exception = values.next(IrType.REFERENCE);
            LocalState locals = new LocalState();
            seedParameterLocals(method, parameters, locals);
            BlockInput handlerInput = new BlockInput(new FrameState(List.of(exception), locals.snapshot()));
            MergeSlot exceptionSlot = new MergeSlot(MergeSlotKind.STACK, 0);
            handlerInput.parametersBySlot.put(exceptionSlot, new MergeParameter(exceptionSlot, exception));
            if (!inputs.containsKey(block.id())) {
                inputs.put(block.id(), handlerInput);
                enqueue(worklist, queued, block.id());
            }
        }
    }

    private boolean hasComplexExceptionShape(BytecodeCfg cfg) {
        return cfg.exceptionRegions().stream()
                .anyMatch(region -> ExceptionRegion.CATCH_ALL.equals(region.catchType()));
    }

    private boolean isSupportedSynchronizedExceptionCleanupShape(BytecodeCfg cfg) {
        if (!containsMonitorInstruction(cfg)) {
            return false;
        }
        List<ExceptionRegion> catchAllRegions = cfg.exceptionRegions().stream()
                .filter(region -> ExceptionRegion.CATCH_ALL.equals(region.catchType()))
                .toList();
        if (catchAllRegions.isEmpty()) {
            return false;
        }
        return catchAllRegions.stream()
                .map(region -> blockContainingInstruction(cfg, region.handlerInstructionIndex()))
                .allMatch(block -> block != null && handlerHasMonitorExitAndRethrow(cfg, block));
    }

    private boolean isSupportedCatchAllRethrowShape(BytecodeCfg cfg) {
        List<ExceptionRegion> catchAllRegions = cfg.exceptionRegions().stream()
                .filter(region -> ExceptionRegion.CATCH_ALL.equals(region.catchType()))
                .toList();
        if (catchAllRegions.isEmpty()) {
            return false;
        }
        return catchAllRegions.stream()
                .map(region -> blockContainingInstruction(cfg, region.handlerInstructionIndex()))
                .allMatch(block -> block != null && handlerHasRethrow(cfg, block));
    }

    private String unsupportedCatchAllReasonCode(BytecodeCfg cfg) {
        boolean hasRethrow = cfg.exceptionRegions().stream()
                .filter(region -> ExceptionRegion.CATCH_ALL.equals(region.catchType()))
                .map(region -> blockContainingInstruction(cfg, region.handlerInstructionIndex()))
                .anyMatch(block -> block != null && handlerHasRethrow(cfg, block));
        return hasRethrow ? "UNSUPPORTED_EXCEPTION_STATE_MERGE" : "UNSUPPORTED_MULTI_EXIT_FINALLY";
    }

    private boolean containsMonitorInstruction(BytecodeCfg cfg) {
        return cfg.instructions().stream()
                .mapToInt(AbstractInsnNode::getOpcode)
                .anyMatch(opcode -> opcode == MONITORENTER || opcode == MONITOREXIT);
    }

    private BytecodeBasicBlock blockContainingInstruction(BytecodeCfg cfg, int instructionIndex) {
        return cfg.blocks().stream()
                .filter(block -> block.startInstructionIndex() <= instructionIndex
                        && instructionIndex < block.endInstructionIndexExclusive())
                .findFirst()
                .orElse(null);
    }

    private boolean handlerHasMonitorExitAndRethrow(BytecodeCfg cfg, BytecodeBasicBlock block) {
        List<AbstractInsnNode> instructions = cfg.instructions().subList(
                block.startInstructionIndex(),
                block.endInstructionIndexExclusive());
        boolean hasMonitorExit = instructions.stream().anyMatch(instruction -> instruction.getOpcode() == MONITOREXIT);
        boolean hasRethrow = instructions.stream().anyMatch(instruction -> instruction.getOpcode() == ATHROW);
        return hasMonitorExit && hasRethrow;
    }

    private boolean handlerHasRethrow(BytecodeCfg cfg, BytecodeBasicBlock block) {
        List<AbstractInsnNode> instructions = cfg.instructions().subList(
                block.startInstructionIndex(),
                block.endInstructionIndexExclusive());
        return instructions.stream().anyMatch(instruction -> instruction.getOpcode() == ATHROW);
    }

    private Map<Integer, BytecodeBasicBlock> blocksById(BytecodeCfg cfg) {
        HashMap<Integer, BytecodeBasicBlock> blocks = new HashMap<>();
        for (BytecodeBasicBlock block : cfg.blocks()) {
            blocks.put(block.id(), block);
        }
        return Map.copyOf(blocks);
    }

    private Map<Integer, Integer> predecessorCounts(BytecodeCfg cfg) {
        HashMap<Integer, Set<Integer>> predecessors = new HashMap<>();
        for (BytecodeEdge edge : cfg.edges()) {
            if (edge.kind() == BytecodeEdgeKind.EXCEPTION) {
                continue;
            }
            predecessors.computeIfAbsent(edge.toBlockId(), ignored -> new HashSet<>()).add(edge.fromBlockId());
        }
        HashMap<Integer, Integer> counts = new HashMap<>();
        for (Map.Entry<Integer, Set<Integer>> entry : predecessors.entrySet()) {
            counts.put(entry.getKey(), entry.getValue().size());
        }
        return Map.copyOf(counts);
    }

    private List<BytecodeEdge> normalSuccessors(BytecodeCfg cfg, BytecodeBasicBlock block) {
        return cfg.edges().stream()
                .filter(edge -> edge.fromBlockId() == block.id() && edge.kind() != BytecodeEdgeKind.EXCEPTION)
                .toList();
    }

    private List<IrExceptionEdge> exceptionEdges(
            BytecodeCfg cfg,
            BytecodeBasicBlock block,
            MethodMonitor methodMonitor,
            ClassInitializationContext classInitialization) {
        ArrayList<IrExceptionEdge> edges = cfg.edges().stream()
                .filter(edge -> edge.fromBlockId() == block.id() && edge.kind() == BytecodeEdgeKind.EXCEPTION)
                .map(edge -> new IrExceptionEdge(blockName(edge.toBlockId()), edge.detail()))
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        if (classInitialization != null) {
            edges.add(classInitialization.failedEdge());
        }
        if (methodMonitor != null) {
            edges.add(methodMonitor.cleanupEdge());
        }
        return List.copyOf(edges);
    }

    private void enqueue(ArrayDeque<Integer> worklist, Set<Integer> queued, int blockId) {
        if (queued.add(blockId)) {
            worklist.addLast(blockId);
        }
    }

    private boolean mergeInto(
            Map<Integer, BlockInput> inputs,
            int blockId,
            FrameState incoming,
            ValueFactory values,
            boolean mergeBlock) {
        BlockInput current = inputs.get(blockId);
        if (current == null) {
            inputs.put(blockId, new BlockInput(incoming.copy()));
            return true;
        }
        return current.merge(incoming, values, mergeBlock);
    }

    private IrTerminator withTargetArguments(
            IrTerminator terminator,
            FrameState outgoing,
            Map<Integer, BlockInput> inputs) {
        return switch (terminator.kind()) {
            case GOTO -> IrTerminator.gotoBlock(
                    terminator.target().orElseThrow(),
                    targetArguments(terminator.target().orElseThrow(), outgoing, inputs));
            case BRANCH -> IrTerminator.branch(
                    terminator.condition().orElseThrow(),
                    terminator.trueTarget().orElseThrow(),
                    targetArguments(terminator.trueTarget().orElseThrow(), outgoing, inputs),
                    terminator.falseTarget().orElseThrow(),
                    targetArguments(terminator.falseTarget().orElseThrow(), outgoing, inputs));
            case SWITCH -> {
                ArrayList<IrSwitchCase> cases = new ArrayList<>();
                for (IrSwitchCase switchCase : terminator.switchCases()) {
                    cases.add(new IrSwitchCase(
                            switchCase.key(),
                            switchCase.target(),
                            targetArguments(switchCase.target(), outgoing, inputs)));
                }
                yield IrTerminator.switchOn(
                        terminator.switchValue().orElseThrow(),
                        terminator.defaultTarget().orElseThrow(),
                        targetArguments(terminator.defaultTarget().orElseThrow(), outgoing, inputs),
                        cases);
            }
            case RETURN, THROW -> terminator;
        };
    }

    private List<IrValue> targetArguments(
            String targetName,
            FrameState outgoing,
            Map<Integer, BlockInput> inputs) {
        BlockInput target = inputs.get(blockId(targetName));
        if (target == null) {
            return List.of();
        }
        ArrayList<IrValue> arguments = new ArrayList<>();
        for (MergeParameter parameter : target.parametersBySlot.values()) {
            arguments.add(outgoing.value(parameter.slot()));
        }
        return List.copyOf(arguments);
    }

    private int blockId(String blockName) {
        if (!blockName.startsWith("b")) {
            throw new IllegalArgumentException("unexpected block name " + blockName);
        }
        return Integer.parseInt(blockName.substring(1));
    }

    private List<IrValue> createParameters(ParsedMethod method, ValueFactory values) {
        ArrayList<IrValue> parameters = new ArrayList<>();
        int parameterIndex = 0;
        if (!method.accessFlags().isStatic()) {
            IrValue self = values.parameter(parameterIndex++, IrType.REFERENCE);
            parameters.add(self);
        }
        for (IrType parameterType : parameterTypes(method.descriptor())) {
            IrValue parameter = values.parameter(parameterIndex++, parameterType);
            parameters.add(parameter);
        }
        return List.copyOf(parameters);
    }

    private void seedParameterLocals(ParsedMethod method, List<IrValue> parameters, LocalState locals) {
        int slot = 0;
        int index = 0;
        if (!method.accessFlags().isStatic()) {
            locals.set(slot++, parameters.get(index++));
        }
        for (IrType parameterType : parameterTypes(method.descriptor())) {
            locals.set(slot, parameters.get(index++));
            slot += parameterType == IrType.I64 || parameterType == IrType.F64 ? 2 : 1;
        }
    }

    private void pushConst(int literal, ValueFactory values, StackState stack, List<IrInstruction> instructions) {
        IrValue result = values.next(IrType.I32);
        instructions.add(IrInstruction.constInt(result, literal));
        stack.push(result);
    }

    private void pushLdc(
            Object constant,
            ParsedMethod currentMethod,
            ValueFactory values,
            StackState stack,
            List<IrInstruction> instructions,
            List<Diagnostic> diagnostics) {
        if (constant instanceof Integer integer) {
            pushConst(integer, values, stack, instructions);
        } else if (constant instanceof Long longValue) {
            IrValue result = values.next(IrType.I64);
            instructions.add(IrInstruction.constLong(result, longValue));
            stack.push(result);
        } else if (constant instanceof Float floatValue) {
            IrValue result = values.next(IrType.F32);
            instructions.add(IrInstruction.constFloat(result, floatValue));
            stack.push(result);
        } else if (constant instanceof Double doubleValue) {
            IrValue result = values.next(IrType.F64);
            instructions.add(IrInstruction.constDouble(result, doubleValue));
            stack.push(result);
        } else if (constant instanceof String stringValue) {
            pushSymbolicConstant(
                    IrOpcode.CONST_STRING,
                    "string:" + stringValue,
                    values,
                    stack,
                    instructions);
        } else if (constant instanceof Type typeValue && typeValue.getSort() == Type.METHOD) {
            pushSymbolicConstant(
                    IrOpcode.CONST_METHOD_TYPE,
                    "methodType:" + typeValue.getDescriptor(),
                    values,
                    stack,
                    instructions);
        } else if (constant instanceof Type typeValue) {
            pushSymbolicConstant(
                    IrOpcode.CONST_CLASS,
                    "class:" + typeValue.getDescriptor(),
                    values,
                    stack,
                    instructions);
        } else if (constant instanceof Handle handle) {
            pushSymbolicConstant(
                    IrOpcode.CONST_METHOD_HANDLE,
                    "methodHandle:" + handle.getTag()
                            + ":" + handle.getOwner()
                            + "#" + handle.getName()
                            + "!" + handle.getDesc()
                            + ":" + handle.isInterface(),
                    values,
                    stack,
                    instructions);
        } else if (constant instanceof ConstantDynamic constantDynamic) {
            pushConstantDynamic(constantDynamic, currentMethod, values, stack, instructions, diagnostics);
        } else {
            throw new UnsupportedOperationException("unsupported LDC constant " + constant);
        }
    }

    private void pushConstantDynamic(
            ConstantDynamic constantDynamic,
            ParsedMethod currentMethod,
            ValueFactory values,
            StackState stack,
            List<IrInstruction> instructions,
            List<Diagnostic> diagnostics) {
        String key = "condy:" + constantDynamic.getName()
                + "!" + constantDynamic.getDescriptor()
                + ":bsm:" + handleKey(constantDynamic.getBootstrapMethod());
        if (isSupportedConstantDynamic(constantDynamic)) {
            IrValue constantId = values.next(IrType.I64);
            instructions.add(IrInstruction.constLong(constantId, stableClassId(key)));
            IrValue result = values.next(typeAt(constantDynamic.getDescriptor(), 0).type());
            instructions.add(IrInstruction.call(
                    java.util.Optional.of(result),
                    IrOpcode.CALL_RUNTIME_HELPER,
                    List.of(constantId),
                    runtimeHelperSymbol(xyz.melodysky.runtime.RuntimeHelperKind.CONSTANT_DYNAMIC)));
            stack.push(result);
            return;
        }
        addJvmHelperFallbackDiagnostic(
                currentMethod,
                key,
                "unsupported ConstantDynamic bootstrap requires JVM helper fallback",
                diagnostics);
        IrValue result = values.next(typeAt(constantDynamic.getDescriptor(), 0).type());
        instructions.add(IrInstruction.call(
                java.util.Optional.of(result),
                IrOpcode.CALL_DYNAMIC,
                List.of(),
                key));
        stack.push(result);
    }

    private boolean isSupportedConstantDynamic(ConstantDynamic constantDynamic) {
        Handle bootstrap = constantDynamic.getBootstrapMethod();
        return bootstrap.getOwner().equals("java/lang/invoke/ConstantBootstraps")
                && bootstrap.getName().equals("nullConstant")
                && typeAt(constantDynamic.getDescriptor(), 0).type() == IrType.REFERENCE;
    }

    private void pushSymbolicConstant(
            IrOpcode opcode,
            String symbol,
            ValueFactory values,
            StackState stack,
            List<IrInstruction> instructions) {
        IrValue result = values.next(IrType.REFERENCE);
        instructions.add(IrInstruction.symbolicConstant(result, opcode, symbol));
        stack.push(result);
    }

    private boolean isLoad(int opcode) {
        return opcode == ILOAD || opcode == LLOAD || opcode == FLOAD || opcode == DLOAD || opcode == ALOAD;
    }

    private boolean isStore(int opcode) {
        return opcode == ISTORE || opcode == LSTORE || opcode == FSTORE || opcode == DSTORE || opcode == ASTORE;
    }

    private boolean isBinary(int opcode) {
        return opcode == IADD || opcode == ISUB || opcode == IMUL || opcode == IDIV || opcode == IREM
                || opcode == LADD || opcode == LSUB || opcode == LMUL || opcode == LDIV || opcode == LREM
                || opcode == FADD || opcode == FSUB || opcode == FMUL || opcode == FDIV || opcode == FREM
                || opcode == DADD || opcode == DSUB || opcode == DMUL || opcode == DDIV || opcode == DREM
                || opcode == IAND || opcode == IOR || opcode == IXOR
                || opcode == LAND || opcode == LOR || opcode == LXOR;
    }

    private boolean isIntegerDivisionOrRemainder(int opcode) {
        return opcode == IDIV || opcode == IREM || opcode == LDIV || opcode == LREM;
    }

    private boolean isNeg(int opcode) {
        return opcode == INEG || opcode == LNEG || opcode == FNEG || opcode == DNEG;
    }

    private boolean isShift(int opcode) {
        return opcode == ISHL || opcode == ISHR || opcode == IUSHR
                || opcode == LSHL || opcode == LSHR || opcode == LUSHR;
    }

    private boolean isConversion(int opcode) {
        return opcode == I2L || opcode == I2F || opcode == I2D || opcode == I2B || opcode == I2C || opcode == I2S
                || opcode == L2I || opcode == L2F || opcode == L2D
                || opcode == F2I || opcode == F2L || opcode == F2D
                || opcode == D2I || opcode == D2L || opcode == D2F;
    }

    private boolean isValueComparison(int opcode) {
        return opcode == LCMP || opcode == FCMPL || opcode == FCMPG || opcode == DCMPL || opcode == DCMPG;
    }

    private boolean isStackManipulation(int opcode) {
        return opcode == POP || opcode == POP2 || opcode == DUP || opcode == DUP_X1 || opcode == DUP_X2
                || opcode == DUP2 || opcode == DUP2_X1 || opcode == DUP2_X2 || opcode == SWAP;
    }

    private boolean isArrayLoad(int opcode) {
        return opcode == IALOAD || opcode == LALOAD || opcode == FALOAD || opcode == DALOAD || opcode == AALOAD
                || opcode == BALOAD || opcode == CALOAD || opcode == SALOAD;
    }

    private boolean isArrayStore(int opcode) {
        return opcode == IASTORE || opcode == LASTORE || opcode == FASTORE || opcode == DASTORE || opcode == AASTORE
                || opcode == BASTORE || opcode == CASTORE || opcode == SASTORE;
    }

    private boolean isValueReturn(int opcode) {
        return opcode == IRETURN || opcode == LRETURN || opcode == FRETURN || opcode == DRETURN || opcode == ARETURN;
    }

    private boolean isConditionalBranch(int opcode) {
        return isIntZeroBranch(opcode) || isIntCompareBranch(opcode) || isReferenceBranch(opcode);
    }

    private boolean isIntZeroBranch(int opcode) {
        return opcode == IFEQ || opcode == IFNE || opcode == IFLT || opcode == IFLE || opcode == IFGT || opcode == IFGE;
    }

    private boolean isIntCompareBranch(int opcode) {
        return opcode == IF_ICMPEQ
                || opcode == IF_ICMPNE
                || opcode == IF_ICMPLT
                || opcode == IF_ICMPLE
                || opcode == IF_ICMPGT
                || opcode == IF_ICMPGE;
    }

    private boolean isReferenceBranch(int opcode) {
        return opcode == IF_ACMPEQ || opcode == IF_ACMPNE || isNullBranch(opcode);
    }

    private boolean isNullBranch(int opcode) {
        return opcode == IFNULL || opcode == IFNONNULL;
    }

    private boolean hasMerge(BytecodeCfg cfg) {
        Map<Integer, Set<Integer>> predecessors = new HashMap<>();
        for (BytecodeEdge edge : cfg.edges()) {
            if (edge.kind() == BytecodeEdgeKind.EXCEPTION) {
                continue;
            }
            predecessors.computeIfAbsent(edge.toBlockId(), ignored -> new HashSet<>()).add(edge.fromBlockId());
        }
        return predecessors.values().stream().anyMatch(fromBlocks -> fromBlocks.size() > 1);
    }

    private IrExceptionSite exceptionSite(IrExceptionSiteKind kind, List<IrExceptionEdge> handlers) {
        return new IrExceptionSite(kind, handlers);
    }

    private IrInstruction memoryMarker(IrOpcode opcode, List<IrValue> operands, String symbol) {
        return IrInstruction.operation(java.util.Optional.empty(), opcode, operands, symbol);
    }

    private MethodMonitor createMethodMonitor(ParsedMethod method, ValueFactory values, List<IrValue> parameters) {
        if (!method.accessFlags().isSynchronized()) {
            return null;
        }
        if (!method.accessFlags().isStatic()) {
            return new MethodMonitor(parameters.get(0), List.of(), values.next(IrType.REFERENCE));
        }
        ClassObjectReference classObject = createClassObjectReference(classDescriptor(method.owner()), values);
        return new MethodMonitor(classObject.value(), classObject.instructions(), values.next(IrType.REFERENCE));
    }

    private ClassInitializationContext createClassInitializationContext(ParsedMethod method, ValueFactory values) {
        if (!method.name().equals("<clinit>")) {
            return null;
        }
        ClassObjectReference classObject = createClassObjectReference(classDescriptor(method.owner()), values);
        return new ClassInitializationContext(
                classObject.value(),
                classObject.instructions(),
                classObject.symbol(),
                values.next(IrType.REFERENCE));
    }

    private void appendClassInitGuard(
            ParsedMethod currentMethod,
            String targetInternalName,
            ValueFactory values,
            List<IrInstruction> instructions) {
        if (currentMethod.name().equals("<clinit>") && currentMethod.owner().equals(targetInternalName)) {
            return;
        }
        ClassObjectReference classObject = createClassObjectReference(classDescriptor(targetInternalName), values);
        instructions.addAll(classObject.instructions());
        instructions.add(IrInstruction.operation(
                java.util.Optional.empty(),
                IrOpcode.CLASS_INIT_GUARD,
                List.of(classObject.value()),
                classObject.symbol() + ":superBeforeSubclass"));
        instructions.add(memoryMarker(
                IrOpcode.CLASS_INIT_HAPPENS_BEFORE,
                List.of(classObject.value()),
                "classInitGuard"));
    }

    private ClassObjectReference createClassObjectReference(String classDescriptor, ValueFactory values) {
        IrValue classId = values.next(IrType.I64);
        IrValue classObject = values.next(IrType.REFERENCE);
        String symbol = "class:" + classDescriptor;
        return new ClassObjectReference(
                classObject,
                List.of(
                        IrInstruction.constLong(classId, stableClassId(classDescriptor)),
                        IrInstruction.operation(
                                java.util.Optional.of(classObject),
                                IrOpcode.CLASS_OBJECT,
                                List.of(classId),
                                symbol)),
                symbol);
    }

    private void appendClassInitializerEnd(
            ClassInitializationContext classInitialization,
            List<IrInstruction> instructions) {
        if (classInitialization == null) {
            return;
        }
        instructions.add(IrInstruction.operation(
                java.util.Optional.empty(),
                IrOpcode.CLASS_INIT_END,
                List.of(classInitialization.classObject()),
                classInitialization.classSymbol()));
        instructions.add(memoryMarker(
                IrOpcode.CLASS_INIT_HAPPENS_BEFORE,
                List.of(classInitialization.classObject()),
                "classInitEnd"));
    }

    private void appendClassInitializerFailed(
            ClassInitializationContext classInitialization,
            IrValue exception,
            List<IrInstruction> instructions) {
        if (classInitialization == null) {
            return;
        }
        instructions.add(IrInstruction.operation(
                java.util.Optional.empty(),
                IrOpcode.CLASS_INIT_FAILED,
                List.of(classInitialization.classObject(), exception),
                classInitialization.classSymbol()));
        instructions.add(memoryMarker(
                IrOpcode.CLASS_INIT_HAPPENS_BEFORE,
                List.of(classInitialization.classObject()),
                "classInitFailed"));
    }

    private String classDescriptor(String internalName) {
        return "L" + internalName + ";";
    }

    private long stableClassId(String classDescriptor) {
        return Integer.toUnsignedLong(classDescriptor.hashCode());
    }

    private void appendMethodMonitorExit(MethodMonitor methodMonitor, List<IrInstruction> instructions) {
        if (methodMonitor != null) {
            appendMonitorHelper(IrOpcode.MONITOR_EXIT, methodMonitor.lock(), List.of(), instructions, "monitorExit");
        }
    }

    private void appendMethodMonitorExceptionalExit(MethodMonitor methodMonitor, List<IrInstruction> instructions) {
        if (methodMonitor != null) {
            appendMonitorHelper(
                    IrOpcode.MONITOR_EXIT_ON_EXCEPTION,
                    methodMonitor.lock(),
                    List.of(),
                    instructions,
                    "monitorExitOnException");
        }
    }

    private void appendMonitorHelper(
            IrOpcode opcode,
            IrValue monitor,
            List<IrExceptionEdge> exceptionEdges,
            List<IrInstruction> instructions,
            String happensBeforeSymbol) {
        IrInstruction monitorInstruction = IrInstruction.operation(
                java.util.Optional.empty(),
                opcode,
                List.of(monitor),
                "monitor")
                .withExceptionSite(exceptionSite(IrExceptionSiteKind.NULL_CHECK, exceptionEdges));
        instructions.add(monitorInstruction);
        instructions.add(memoryMarker(
                IrOpcode.MONITOR_HAPPENS_BEFORE,
                List.of(monitor),
                happensBeforeSymbol));
    }

    private java.util.Optional<ParsedField> fieldMetadata(ParsedMethod method, FieldInsnNode fieldInsn) {
        return method.ownerFields().stream()
                .filter(field -> field.owner().equals(fieldInsn.owner)
                        && field.name().equals(fieldInsn.name)
                        && field.descriptor().equals(fieldInsn.desc))
                .findFirst();
    }

    private boolean isThreadStart(MethodInsnNode methodInsn, int opcode) {
        return opcode == INVOKEVIRTUAL
                && methodInsn.owner.equals("java/lang/Thread")
                && methodInsn.name.equals("start")
                && methodInsn.desc.equals("()V");
    }

    private boolean isThreadJoin(MethodInsnNode methodInsn, int opcode) {
        return opcode == INVOKEVIRTUAL
                && methodInsn.owner.equals("java/lang/Thread")
                && methodInsn.name.equals("join")
                && methodInsn.desc.equals("()V");
    }

    private BytecodeEdge edge(BytecodeCfg cfg, BytecodeBasicBlock block, BytecodeEdgeKind kind) {
        return cfg.edges().stream()
                .filter(candidate -> candidate.fromBlockId() == block.id() && candidate.kind() == kind)
                .findFirst()
                .orElse(null);
    }

    private BytecodeEdge branchEdge(BytecodeCfg cfg, BytecodeBasicBlock block) {
        BytecodeEdge edge = edge(cfg, block, BytecodeEdgeKind.BRANCH);
        if (edge == null) {
            throw new UnsupportedOperationException("branch instruction has no branch edge");
        }
        return edge;
    }

    private String blockName(int blockId) {
        return "b" + blockId;
    }

    private IrType binaryResultType(int opcode) {
        return switch (opcode) {
            case LADD, LSUB, LMUL, LDIV, LREM, LAND, LOR, LXOR, LSHL, LSHR, LUSHR -> IrType.I64;
            case FADD, FSUB, FMUL, FDIV, FREM -> IrType.F32;
            case DADD, DSUB, DMUL, DDIV, DREM -> IrType.F64;
            default -> IrType.I32;
        };
    }

    private IrType conversionResultType(int opcode) {
        return switch (opcode) {
            case I2L, F2L, D2L -> IrType.I64;
            case I2F, L2F, D2F -> IrType.F32;
            case I2D, L2D, F2D -> IrType.F64;
            case I2B, I2C, I2S, L2I, F2I, D2I -> IrType.I32;
            default -> throw new IllegalArgumentException("unsupported conversion opcode " + opcode);
        };
    }

    private IrOpcode opcodeFor(int opcode) {
        return switch (opcode) {
            case IADD -> IrOpcode.ADD_I32;
            case ISUB -> IrOpcode.SUB_I32;
            case IMUL -> IrOpcode.MUL_I32;
            case IDIV -> IrOpcode.DIV_I32;
            case IREM -> IrOpcode.REM_I32;
            case INEG -> IrOpcode.NEG_I32;
            case ISHL -> IrOpcode.SHL_I32;
            case ISHR -> IrOpcode.SHR_I32;
            case IUSHR -> IrOpcode.USHR_I32;
            case IAND -> IrOpcode.AND_I32;
            case IOR -> IrOpcode.OR_I32;
            case IXOR -> IrOpcode.XOR_I32;
            case LADD -> IrOpcode.ADD_I64;
            case LSUB -> IrOpcode.SUB_I64;
            case LMUL -> IrOpcode.MUL_I64;
            case LDIV -> IrOpcode.DIV_I64;
            case LREM -> IrOpcode.REM_I64;
            case LNEG -> IrOpcode.NEG_I64;
            case LSHL -> IrOpcode.SHL_I64;
            case LSHR -> IrOpcode.SHR_I64;
            case LUSHR -> IrOpcode.USHR_I64;
            case LAND -> IrOpcode.AND_I64;
            case LOR -> IrOpcode.OR_I64;
            case LXOR -> IrOpcode.XOR_I64;
            case FADD -> IrOpcode.ADD_F32;
            case FSUB -> IrOpcode.SUB_F32;
            case FMUL -> IrOpcode.MUL_F32;
            case FDIV -> IrOpcode.DIV_F32;
            case FREM -> IrOpcode.REM_F32;
            case FNEG -> IrOpcode.NEG_F32;
            case DADD -> IrOpcode.ADD_F64;
            case DSUB -> IrOpcode.SUB_F64;
            case DMUL -> IrOpcode.MUL_F64;
            case DDIV -> IrOpcode.DIV_F64;
            case DREM -> IrOpcode.REM_F64;
            case DNEG -> IrOpcode.NEG_F64;
            case LCMP -> IrOpcode.LCMP;
            case FCMPL -> IrOpcode.FCMPL;
            case FCMPG -> IrOpcode.FCMPG;
            case DCMPL -> IrOpcode.DCMPL;
            case DCMPG -> IrOpcode.DCMPG;
            case I2L -> IrOpcode.I2L;
            case I2F -> IrOpcode.I2F;
            case I2D -> IrOpcode.I2D;
            case I2B -> IrOpcode.I2B;
            case I2C -> IrOpcode.I2C;
            case I2S -> IrOpcode.I2S;
            case L2I -> IrOpcode.L2I;
            case L2F -> IrOpcode.L2F;
            case L2D -> IrOpcode.L2D;
            case F2I -> IrOpcode.F2I;
            case F2L -> IrOpcode.F2L;
            case F2D -> IrOpcode.F2D;
            case D2I -> IrOpcode.D2I;
            case D2L -> IrOpcode.D2L;
            case D2F -> IrOpcode.D2F;
            default -> throw new IllegalArgumentException("unsupported binary opcode " + opcode);
        };
    }

    private IrOpcode compareOpcode(int opcode) {
        return switch (opcode) {
            case IFEQ, IF_ICMPEQ -> IrOpcode.CMP_EQ_I32;
            case IFNE, IF_ICMPNE -> IrOpcode.CMP_NE_I32;
            case IFLT, IF_ICMPLT -> IrOpcode.CMP_LT_I32;
            case IFLE, IF_ICMPLE -> IrOpcode.CMP_LE_I32;
            case IFGT, IF_ICMPGT -> IrOpcode.CMP_GT_I32;
            case IFGE, IF_ICMPGE -> IrOpcode.CMP_GE_I32;
            case IF_ACMPEQ, IFNULL -> IrOpcode.CMP_EQ_REF;
            case IF_ACMPNE, IFNONNULL -> IrOpcode.CMP_NE_REF;
            default -> throw new IllegalArgumentException("unsupported branch opcode " + opcode);
        };
    }

    private IrOpcode callOpcode(int opcode) {
        return switch (opcode) {
            case INVOKESTATIC -> IrOpcode.CALL_STATIC;
            case INVOKESPECIAL -> IrOpcode.CALL_SPECIAL;
            case INVOKEVIRTUAL -> IrOpcode.CALL_VIRTUAL;
            case INVOKEINTERFACE -> IrOpcode.CALL_INTERFACE;
            default -> throw new IllegalArgumentException("unsupported call opcode " + opcode);
        };
    }

    private IrOpcode arrayLoadOpcode(int opcode) {
        return switch (opcode) {
            case LALOAD -> IrOpcode.ARRAY_LOAD_I64;
            case FALOAD -> IrOpcode.ARRAY_LOAD_F32;
            case DALOAD -> IrOpcode.ARRAY_LOAD_F64;
            case AALOAD -> IrOpcode.ARRAY_LOAD_REF;
            case IALOAD, BALOAD, CALOAD, SALOAD -> IrOpcode.ARRAY_LOAD_I32;
            default -> throw new IllegalArgumentException("not an array load opcode " + opcode);
        };
    }

    private IrOpcode arrayStoreOpcode(int opcode) {
        return switch (opcode) {
            case LASTORE -> IrOpcode.ARRAY_STORE_I64;
            case FASTORE -> IrOpcode.ARRAY_STORE_F32;
            case DASTORE -> IrOpcode.ARRAY_STORE_F64;
            case AASTORE -> IrOpcode.ARRAY_STORE_REF;
            case IASTORE, BASTORE, CASTORE, SASTORE -> IrOpcode.ARRAY_STORE_I32;
            default -> throw new IllegalArgumentException("not an array store opcode " + opcode);
        };
    }

    private IrType arrayLoadType(int opcode) {
        return switch (opcode) {
            case LALOAD -> IrType.I64;
            case FALOAD -> IrType.F32;
            case DALOAD -> IrType.F64;
            case AALOAD -> IrType.REFERENCE;
            case IALOAD, BALOAD, CALOAD, SALOAD -> IrType.I32;
            default -> throw new IllegalArgumentException("not an array load opcode " + opcode);
        };
    }

    private String arrayElementKind(int opcode) {
        return switch (opcode) {
            case IALOAD, IASTORE -> "int";
            case LALOAD, LASTORE -> "long";
            case FALOAD, FASTORE -> "float";
            case DALOAD, DASTORE -> "double";
            case AALOAD, AASTORE -> "reference";
            case BALOAD, BASTORE -> "byteOrBoolean";
            case CALOAD, CASTORE -> "char";
            case SALOAD, SASTORE -> "short";
            default -> throw new IllegalArgumentException("not an array opcode " + opcode);
        };
    }

    private String primitiveArrayType(int operand) {
        return switch (operand) {
            case T_BOOLEAN -> "boolean";
            case T_CHAR -> "char";
            case T_FLOAT -> "float";
            case T_DOUBLE -> "double";
            case T_BYTE -> "byte";
            case T_SHORT -> "short";
            case T_INT -> "int";
            case T_LONG -> "long";
            default -> throw new IllegalArgumentException("not a primitive array type " + operand);
        };
    }

    private String handleKey(Handle handle) {
        return handle.getTag()
                + ":" + handle.getOwner()
                + "#" + handle.getName()
                + "!" + handle.getDesc()
                + ":" + handle.isInterface();
    }

    private StageResult<SsaMethodResult> skipped(ParsedMethod method, String reasonCode, String reason) {
        Diagnostic diagnostic = Diagnostic.warning(
                        DiagnosticStage.LOWERING,
                        diagnosticCode(reasonCode),
                        reason)
                .at(location(method))
                .withDecision("frontendSkipped");
        return StageResult.complete(
                DiagnosticStage.LOWERING,
                SsaMethodResult.frontendSkipped(method, reasonCode, reason),
                List.of(diagnostic));
    }

    private xyz.melodysky.diagnostic.DiagnosticCode diagnosticCode(String reasonCode) {
        if (reasonCode.equals("UNSUPPORTED_CFG_SHAPE")) {
            return LoweringDiagnostics.UNSUPPORTED_CFG_SHAPE;
        }
        if (reasonCode.equals("UNSUPPORTED_COMPLEX_EXCEPTION_SHAPE")) {
            return LoweringDiagnostics.UNSUPPORTED_COMPLEX_EXCEPTION_SHAPE;
        }
        if (reasonCode.equals("UNSUPPORTED_FINALLY_SUBROUTINE")) {
            return LoweringDiagnostics.UNSUPPORTED_FINALLY_SUBROUTINE;
        }
        if (reasonCode.equals("UNSUPPORTED_MULTI_EXIT_FINALLY")) {
            return LoweringDiagnostics.UNSUPPORTED_MULTI_EXIT_FINALLY;
        }
        if (reasonCode.equals("UNSUPPORTED_EXCEPTION_STATE_MERGE")) {
            return LoweringDiagnostics.UNSUPPORTED_EXCEPTION_STATE_MERGE;
        }
        if (reasonCode.equals("UNSUPPORTED_SYNCHRONIZED_METHOD")) {
            return LoweringDiagnostics.UNSUPPORTED_SYNCHRONIZED_METHOD;
        }
        if (reasonCode.equals("UNSUPPORTED_SYNCHRONIZED_EXCEPTION_CLEANUP")) {
            return LoweringDiagnostics.UNSUPPORTED_SYNCHRONIZED_EXCEPTION_CLEANUP;
        }
        if (reasonCode.equals("UNSUPPORTED_SSA_MERGE")) {
            return LoweringDiagnostics.UNSUPPORTED_SSA_MERGE;
        }
        if (reasonCode.equals("SSA_MERGE_STACK_HEIGHT_MISMATCH")) {
            return LoweringDiagnostics.SSA_MERGE_STACK_HEIGHT_MISMATCH;
        }
        if (reasonCode.equals("SSA_MERGE_TYPE_MISMATCH")) {
            return LoweringDiagnostics.SSA_MERGE_TYPE_MISMATCH;
        }
        if (reasonCode.equals("SSA_MERGE_LOCAL_SLOT_MISMATCH")) {
            return LoweringDiagnostics.SSA_MERGE_LOCAL_SLOT_MISMATCH;
        }
        return LoweringDiagnostics.UNSUPPORTED_OPCODE;
    }

    private DiagnosticLocation location(ParsedMethod method) {
        return DiagnosticLocation.methodLocation(method.owner(), method.name(), method.descriptor());
    }

    private IrType returnType(String descriptor) {
        int end = descriptor.indexOf(')');
        return typeAt(descriptor, end + 1).type();
    }

    private List<IrType> parameterTypes(String descriptor) {
        ArrayList<IrType> types = new ArrayList<>();
        int index = 1;
        while (descriptor.charAt(index) != ')') {
            ParsedType parsed = typeAt(descriptor, index);
            types.add(parsed.type());
            index = parsed.nextIndex();
        }
        return types;
    }

    private ParsedType typeAt(String descriptor, int index) {
        char tag = descriptor.charAt(index);
        return switch (tag) {
            case 'V' -> new ParsedType(IrType.VOID, index + 1);
            case 'I', 'Z', 'B', 'S', 'C' -> new ParsedType(IrType.I32, index + 1);
            case 'J' -> new ParsedType(IrType.I64, index + 1);
            case 'F' -> new ParsedType(IrType.F32, index + 1);
            case 'D' -> new ParsedType(IrType.F64, index + 1);
            case 'L' -> new ParsedType(IrType.REFERENCE, descriptor.indexOf(';', index) + 1);
            case '[' -> {
                int next = index;
                while (descriptor.charAt(next) == '[') {
                    next++;
                }
                if (descriptor.charAt(next) == 'L') {
                    next = descriptor.indexOf(';', next);
                }
                yield new ParsedType(IrType.REFERENCE, next + 1);
            }
            default -> throw new IllegalArgumentException("unsupported descriptor type " + tag);
        };
    }

    private record ParsedType(IrType type, int nextIndex) {
    }

    private record MethodHandleConstant(
            int tag,
            String owner,
            String name,
            String descriptor,
            boolean interfaceOwner) {
    }

    private record BlockLowering(List<IrInstruction> instructions, IrTerminator terminator, FrameState outgoingState) {
        private BlockLowering {
            instructions = List.copyOf(instructions);
        }
    }

    private record MethodMonitor(
            IrValue lock,
            List<IrInstruction> lockInstructions,
            IrValue cleanupException) {
        private static final String CLEANUP_BLOCK = "$sync_cleanup";

        private IrExceptionEdge cleanupEdge() {
            return new IrExceptionEdge(CLEANUP_BLOCK, ExceptionRegion.CATCH_ALL);
        }

        private IrBlock cleanupBlock() {
            return new IrBlock(
                    CLEANUP_BLOCK,
                    List.of(cleanupException),
                    List.of(ExceptionRegion.CATCH_ALL),
                    List.of(),
                    List.of(
                            IrInstruction.operation(
                                    java.util.Optional.empty(),
                                    IrOpcode.MONITOR_EXIT_ON_EXCEPTION,
                                    List.of(lock),
                                    "monitor"),
                            IrInstruction.operation(
                                    java.util.Optional.empty(),
                                    IrOpcode.MONITOR_HAPPENS_BEFORE,
                                    List.of(lock),
                                    "monitorExitOnException")),
                    IrTerminator.throwValue(cleanupException));
        }
    }

    private record ClassObjectReference(IrValue value, List<IrInstruction> instructions, String symbol) {
        private ClassObjectReference {
            instructions = List.copyOf(instructions);
        }
    }

    private record ClassInitializationContext(
            IrValue classObject,
            List<IrInstruction> classObjectInstructions,
            String classSymbol,
            IrValue failedException) {
        private static final String FAILED_BLOCK = "$class_init_failed";

        private ClassInitializationContext {
            classObjectInstructions = List.copyOf(classObjectInstructions);
        }

        private IrExceptionEdge failedEdge() {
            return new IrExceptionEdge(FAILED_BLOCK, ExceptionRegion.CATCH_ALL);
        }

        private IrBlock failedBlock() {
            return new IrBlock(
                    FAILED_BLOCK,
                    List.of(failedException),
                    List.of(ExceptionRegion.CATCH_ALL),
                    List.of(),
                    List.of(
                            IrInstruction.operation(
                                    java.util.Optional.empty(),
                                    IrOpcode.CLASS_INIT_FAILED,
                                    List.of(classObject, failedException),
                                    classSymbol),
                            IrInstruction.operation(
                                    java.util.Optional.empty(),
                                    IrOpcode.CLASS_INIT_HAPPENS_BEFORE,
                                    List.of(classObject),
                                    "classInitFailed")),
                    IrTerminator.throwValue(failedException));
        }
    }

    private static final class FrameState {
        private final ArrayList<IrValue> stack;
        private final TreeMap<Integer, IrValue> locals;

        private FrameState(List<IrValue> stack, Map<Integer, IrValue> locals) {
            this.stack = new ArrayList<>(stack);
            this.locals = new TreeMap<>(locals);
        }

        private List<IrValue> stack() {
            return List.copyOf(stack);
        }

        private Map<Integer, IrValue> locals() {
            return Map.copyOf(locals);
        }

        private FrameState copy() {
            return new FrameState(stack, locals);
        }

        private boolean sameValues(FrameState other) {
            return stack.equals(other.stack) && locals.equals(other.locals);
        }

        private void replaceWith(FrameState other) {
            stack.clear();
            stack.addAll(other.stack);
            locals.clear();
            locals.putAll(other.locals);
        }

        private IrValue value(MergeSlot slot) {
            if (slot.kind() == MergeSlotKind.STACK) {
                if (slot.index() >= stack.size()) {
                    throw new IllegalStateException("missing stack value for merge argument " + slot);
                }
                return stack.get(slot.index());
            }
            IrValue value = locals.get(slot.index());
            if (value == null) {
                throw new IllegalStateException("missing local value for merge argument " + slot);
            }
            return value;
        }

        private void set(MergeSlot slot, IrValue value) {
            if (slot.kind() == MergeSlotKind.STACK) {
                stack.set(slot.index(), value);
            } else {
                locals.put(slot.index(), value);
            }
        }
    }

    private static final class BlockInput {
        private final FrameState state;
        private final TreeMap<MergeSlot, MergeParameter> parametersBySlot = new TreeMap<>();

        private BlockInput(FrameState state) {
            this.state = state;
        }

        private List<IrValue> parameters() {
            return parametersBySlot.values().stream().map(MergeParameter::value).toList();
        }

        private boolean merge(FrameState incoming, ValueFactory values, boolean mergeBlock) {
            if (!mergeBlock) {
                if (state.sameValues(incoming)) {
                    return false;
                }
                state.replaceWith(incoming);
                return true;
            }

            if (state.stack.size() != incoming.stack.size()) {
                throw new MergeFailure(
                        "SSA_MERGE_STACK_HEIGHT_MISMATCH",
                        "merge stack height mismatch: existing "
                                + state.stack.size() + " vs incoming " + incoming.stack.size());
            }

            boolean changed = false;
            for (int index = 0; index < state.stack.size(); index++) {
                changed |= mergeValue(new MergeSlot(MergeSlotKind.STACK, index), incoming.stack.get(index), values);
            }

            TreeSet<Integer> slots = new TreeSet<>();
            slots.addAll(state.locals.keySet());
            slots.addAll(incoming.locals.keySet());
            for (int slot : slots) {
                IrValue current = state.locals.get(slot);
                IrValue incomingValue = incoming.locals.get(slot);
                if (current == null || incomingValue == null) {
                    throw new MergeFailure(
                            "SSA_MERGE_LOCAL_SLOT_MISMATCH",
                            "merge local slot " + slot + " is not defined on every incoming edge");
                }
                changed |= mergeValue(new MergeSlot(MergeSlotKind.LOCAL, slot), incomingValue, values);
            }
            return changed;
        }

        private boolean mergeValue(MergeSlot slot, IrValue incomingValue, ValueFactory values) {
            IrValue current = state.value(slot);
            if (current.type() != incomingValue.type()) {
                throw new MergeFailure(
                        "SSA_MERGE_TYPE_MISMATCH",
                        "merge value type mismatch at " + slot + ": existing "
                                + current.type() + " vs incoming " + incomingValue.type());
            }
            MergeParameter existing = parametersBySlot.get(slot);
            if (existing != null) {
                return false;
            }
            if (current.equals(incomingValue)) {
                return false;
            }
            IrValue parameter = values.next(current.type());
            parametersBySlot.put(slot, new MergeParameter(slot, parameter));
            state.set(slot, parameter);
            return true;
        }
    }

    private enum MergeSlotKind {
        STACK,
        LOCAL
    }

    private record MergeSlot(MergeSlotKind kind, int index) implements Comparable<MergeSlot> {
        @Override
        public int compareTo(MergeSlot other) {
            int byKind = Integer.compare(kind.ordinal(), other.kind.ordinal());
            return byKind != 0 ? byKind : Integer.compare(index, other.index);
        }
    }

    private record MergeParameter(MergeSlot slot, IrValue value) {
    }

    private static final class MergeFailure extends RuntimeException {
        private final String reasonCode;

        private MergeFailure(String reasonCode, String message) {
            super(message);
            this.reasonCode = reasonCode;
        }
    }
}
