package xyz.melodysky.frontend.bytecode;

import xyz.melodysky.ir.model.IrBinaryOpcode;
import xyz.melodysky.ir.model.IrBlock;
import xyz.melodysky.ir.model.IrClassRef;
import xyz.melodysky.ir.model.IrCompareOpcode;
import xyz.melodysky.ir.model.IrFieldRef;
import xyz.melodysky.ir.model.IrInstruction;
import xyz.melodysky.ir.model.IrMethod;
import xyz.melodysky.ir.model.IrMethodRef;
import xyz.melodysky.ir.model.IrTerminator;
import xyz.melodysky.ir.model.IrType;
import xyz.melodysky.ir.model.IrValue;
import xyz.melodysky.ir.validate.IrMethodValidator;
import org.objectweb.asm.ConstantDynamic;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.Handle;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.LineNumberNode;
import org.objectweb.asm.tree.LookupSwitchInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.MultiANewArrayInsnNode;
import org.objectweb.asm.tree.TableSwitchInsnNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class MethodIrBuilder {

    private final IrMethodValidator validator = new IrMethodValidator();

    public IrMethod build(MethodNode methodNode) {
        return build(null, methodNode);
    }

    public IrMethod build(String ownerInternalName, MethodNode methodNode) {
        AbstractInsnNode entryInstruction = nextExecutable(methodNode.instructions.getFirst());
        if (entryInstruction == null) {
            throw new UnsupportedBytecodeException("Method " + methodNode.name + methodNode.desc + " has no executable instructions");
        }

        LinkedHashSet<AbstractInsnNode> blockStarts = collectBlockStarts(methodNode, entryInstruction);
        IdentityHashMap<AbstractInsnNode, String> blockLabels = assignBlockLabels(blockStarts, entryInstruction);

        ArrayList<IrBlock> blocks = new ArrayList<>();
        Deque<IrValue> stack = new ArrayDeque<>();
        ArrayList<IrInstruction> currentInstructions = new ArrayList<>();
        String currentBlockLabel = null;
        int nextValueId = 0;
        Map<String, List<IrType>> incomingStackTypesByBlock = new HashMap<>();
        Map<String, List<Integer>> incomingStackSlotsByBlock = new HashMap<>();
        Set<String> emittedBlocks = new LinkedHashSet<>();
        Map<Integer, IrType> localTypes = initializeLocalTypes(ownerInternalName, methodNode);
        Map<Integer, Integer> localStorageSlots = initializeLocalStorageSlots(localTypes);
        int nextStorageSlot = methodNode.maxLocals;
        Map<String, IrType> handlerEntryTypesByBlock = collectExceptionHandlerEntryTypes(methodNode, blockLabels);
        incomingStackTypesByBlock.put("entry", List.of());
        incomingStackSlotsByBlock.put("entry", List.of());

        for (AbstractInsnNode instruction = methodNode.instructions.getFirst();
             instruction != null;
             instruction = instruction.getNext()) {
            if (shouldIgnore(instruction)) {
                continue;
            }

            String blockStartLabel = blockLabels.get(instruction);
            if (blockStartLabel != null) {
                if (currentBlockLabel == null) {
                    currentBlockLabel = blockStartLabel;
                    nextValueId = restoreIncomingStack(
                            currentInstructions,
                            stack,
                            blockStartLabel,
                            incomingStackTypesByBlock,
                            incomingStackSlotsByBlock,
                            nextValueId
                    );
                    if (!incomingStackTypesByBlock.containsKey(blockStartLabel) && handlerEntryTypesByBlock.containsKey(blockStartLabel)) {
                        IrValue exceptionValue = emitCaughtExceptionValue(
                                currentInstructions,
                                handlerEntryTypesByBlock.get(blockStartLabel),
                                nextValueId++
                        );
                        stack.push(exceptionValue);
                    }
                } else if (!currentBlockLabel.equals(blockStartLabel)) {
                    nextStorageSlot = spillStackToTargets(
                            methodNode,
                            currentInstructions,
                            stack,
                            List.of(blockStartLabel),
                            incomingStackTypesByBlock,
                            incomingStackSlotsByBlock,
                            emittedBlocks,
                            nextStorageSlot
                    );
                    blocks.add(new IrBlock(currentBlockLabel, List.copyOf(currentInstructions), new IrTerminator.Goto(blockStartLabel)));
                    emittedBlocks.add(currentBlockLabel);
                    currentInstructions.clear();
                    currentBlockLabel = blockStartLabel;
                    nextValueId = restoreIncomingStack(
                            currentInstructions,
                            stack,
                            blockStartLabel,
                            incomingStackTypesByBlock,
                            incomingStackSlotsByBlock,
                            nextValueId
                    );
                    if (!incomingStackTypesByBlock.containsKey(blockStartLabel) && handlerEntryTypesByBlock.containsKey(blockStartLabel)) {
                        IrValue exceptionValue = emitCaughtExceptionValue(
                                currentInstructions,
                                handlerEntryTypesByBlock.get(blockStartLabel),
                                nextValueId++
                        );
                        stack.push(exceptionValue);
                    }
                }
            }

            if (currentBlockLabel == null) {
                throw new UnsupportedBytecodeException("Encountered executable instruction outside any block in "
                        + methodNode.name + methodNode.desc);
            }

            switch (instruction) {
                case VarInsnNode varInsn -> {
                    switch (varInsn.getOpcode()) {
                        case Opcodes.ILOAD -> {
                            IrType localType = requireLocalType(methodNode, localTypes, varInsn.var);
                            if (!isIntLike(localType)) {
                                throw new UnsupportedBytecodeException("Local slot " + varInsn.var + " is not int-like in "
                                        + methodNode.name + methodNode.desc);
                            }
                            IrValue raw = new IrValue(nextValueId++, localType, "local");
                            currentInstructions.add(new IrInstruction.LoadLocal(raw, requireLocalStorageSlot(methodNode, localStorageSlots, varInsn.var)));
                            if (localType == IrType.INT) {
                                stack.push(raw);
                            } else {
                                IrValue promoted = new IrValue(nextValueId++, IrType.INT, "conv");
                                currentInstructions.add(new IrInstruction.Convert(promoted, raw));
                                stack.push(promoted);
                            }
                        }
                        case Opcodes.LLOAD, Opcodes.FLOAD, Opcodes.DLOAD -> {
                            IrType localType = requireLocalType(methodNode, localTypes, varInsn.var);
                            IrType expectedType = switch (varInsn.getOpcode()) {
                                case Opcodes.LLOAD -> IrType.LONG;
                                case Opcodes.FLOAD -> IrType.FLOAT;
                                case Opcodes.DLOAD -> IrType.DOUBLE;
                                default -> throw new IllegalStateException("Unexpected numeric load opcode " + varInsn.getOpcode());
                            };
                            if (localType != expectedType) {
                                throw new UnsupportedBytecodeException("Local slot " + varInsn.var + " is not "
                                        + expectedType.displayName() + " in " + methodNode.name + methodNode.desc);
                            }
                            IrValue value = new IrValue(nextValueId++, localType, "local");
                            currentInstructions.add(new IrInstruction.LoadLocal(value, requireLocalStorageSlot(methodNode, localStorageSlots, varInsn.var)));
                            stack.push(value);
                        }
                        case Opcodes.ALOAD -> {
                            IrType localType = requireLocalType(methodNode, localTypes, varInsn.var);
                            if (localType.isPrimitive() || localType == IrType.VOID) {
                                throw new UnsupportedBytecodeException("Local slot " + varInsn.var + " is not reference-like in "
                                        + methodNode.name + methodNode.desc);
                            }
                            IrValue value = new IrValue(nextValueId++, localType, "local");
                            currentInstructions.add(new IrInstruction.LoadLocal(value, requireLocalStorageSlot(methodNode, localStorageSlots, varInsn.var)));
                            stack.push(value);
                        }
                        case Opcodes.ISTORE -> {
                            IrValue value = popIntLike(stack, methodNode);
                            int storageSlot = allocateStorageSlotForStore(localStorageSlots, localTypes, varInsn.var, value.type(), nextStorageSlot);
                            if (storageSlot == nextStorageSlot) {
                                nextStorageSlot++;
                            }
                            currentInstructions.add(new IrInstruction.StoreLocal(storageSlot, value));
                            localTypes.put(varInsn.var, value.type());
                        }
                        case Opcodes.LSTORE, Opcodes.FSTORE, Opcodes.DSTORE -> {
                            IrType expectedType = switch (varInsn.getOpcode()) {
                                case Opcodes.LSTORE -> IrType.LONG;
                                case Opcodes.FSTORE -> IrType.FLOAT;
                                case Opcodes.DSTORE -> IrType.DOUBLE;
                                default -> throw new IllegalStateException("Unexpected numeric store opcode " + varInsn.getOpcode());
                            };
                            IrValue value = popValueOfExpectedType(stack, methodNode, expectedType, "store local");
                            int storageSlot = allocateStorageSlotForStore(localStorageSlots, localTypes, varInsn.var, value.type(), nextStorageSlot);
                            if (storageSlot == nextStorageSlot) {
                                nextStorageSlot++;
                            }
                            currentInstructions.add(new IrInstruction.StoreLocal(storageSlot, value));
                            localTypes.put(varInsn.var, value.type());
                        }
                        case Opcodes.ASTORE -> {
                            IrValue value = popReferenceLike(stack, methodNode);
                            int storageSlot = allocateStorageSlotForStore(localStorageSlots, localTypes, varInsn.var, value.type(), nextStorageSlot);
                            if (storageSlot == nextStorageSlot) {
                                nextStorageSlot++;
                            }
                            currentInstructions.add(new IrInstruction.StoreLocal(storageSlot, value));
                            localTypes.put(varInsn.var, value.type());
                        }
                        default -> throw unsupported(methodNode, varInsn, "only ILOAD/LLOAD/FLOAD/DLOAD/ALOAD and ISTORE/LSTORE/FSTORE/DSTORE/ASTORE are supported in the current slice");
                    }
                }
                case IntInsnNode intInsn -> {
                    if (intInsn.getOpcode() == Opcodes.NEWARRAY) {
                        nextValueId = handleNewPrimitiveArray(methodNode, currentInstructions, stack, intInsn, nextValueId);
                        continue;
                    }
                    if (intInsn.getOpcode() != Opcodes.BIPUSH && intInsn.getOpcode() != Opcodes.SIPUSH) {
                        throw unsupported(methodNode, intInsn, "only BIPUSH/SIPUSH and NEWARRAY are supported int instructions");
                    }
                    stack.push(emitConst(currentInstructions, intInsn.operand, nextValueId++));
                }
                case LdcInsnNode ldcInsn -> {
                    if (ldcInsn.cst instanceof Integer integerValue) {
                        stack.push(emitConst(currentInstructions, integerValue, nextValueId++));
                        continue;
                    }
                    if (ldcInsn.cst instanceof Long longValue) {
                        stack.push(emitLongConst(currentInstructions, longValue, nextValueId++));
                        continue;
                    }
                    if (ldcInsn.cst instanceof Float floatValue) {
                        stack.push(emitFloatConst(currentInstructions, floatValue, nextValueId++));
                        continue;
                    }
                    if (ldcInsn.cst instanceof Double doubleValue) {
                        stack.push(emitDoubleConst(currentInstructions, doubleValue, nextValueId++));
                        continue;
                    }
                    if (ldcInsn.cst instanceof String stringValue) {
                        stack.push(emitStringConstant(currentInstructions, stringValue, nextValueId++));
                        continue;
                    }
                    if (ldcInsn.cst instanceof Type typeValue) {
                        stack.push(emitClassConstant(currentInstructions, typeValue, nextValueId++));
                        continue;
                    }
                    throw unsupported(methodNode, ldcInsn, "only integer/long/float/double/string/class LDC constants are supported in the current slice");
                }
                case FieldInsnNode fieldInsn -> {
                    nextValueId = handleStaticFieldInstruction(methodNode, currentInstructions, stack, fieldInsn, nextValueId);
                    ProtectedExceptionEdgeResult edgeResult = maybeEmitProtectedExceptionEdge(
                            methodNode,
                            blockLabels,
                            fieldInsn,
                            currentBlockLabel,
                            currentInstructions,
                            stack,
                            blocks,
                            incomingStackTypesByBlock,
                            incomingStackSlotsByBlock,
                            emittedBlocks,
                            nextStorageSlot,
                            nextValueId
                    );
                    nextStorageSlot = edgeResult.nextStorageSlot();
                    nextValueId = edgeResult.nextValueId();
                    if (edgeResult.terminatedBlock()) {
                        currentBlockLabel = null;
                    }
                }
                case JumpInsnNode jumpInsn -> {
                    IrTerminator terminator;
                    if (jumpInsn.getOpcode() == Opcodes.GOTO) {
                        String target = requiredBlockLabel(methodNode, blockLabels, nextExecutable(jumpInsn.label));
                        nextStorageSlot = spillStackToTargets(
                                methodNode,
                                currentInstructions,
                                stack,
                                List.of(target),
                                incomingStackTypesByBlock,
                                incomingStackSlotsByBlock,
                                emittedBlocks,
                                nextStorageSlot
                        );
                        terminator = new IrTerminator.Goto(target);
                    } else {
                        String trueTarget = requiredBlockLabel(methodNode, blockLabels, nextExecutable(jumpInsn.label));
                        String falseTarget = requiredBlockLabel(methodNode, blockLabels, nextExecutable(jumpInsn.getNext()));
                        ConditionEmission emittedCondition = emitJumpCondition(methodNode, currentInstructions, stack, jumpInsn, nextValueId);
                        nextValueId += emittedCondition.consumedIds();
                        nextStorageSlot = spillStackToTargets(
                                methodNode,
                                currentInstructions,
                                stack,
                                List.of(trueTarget, falseTarget),
                                incomingStackTypesByBlock,
                                incomingStackSlotsByBlock,
                                emittedBlocks,
                                nextStorageSlot
                        );
                        terminator = new IrTerminator.Branch(
                                emittedCondition.condition(),
                                trueTarget,
                                falseTarget
                        );
                    }

                    blocks.add(new IrBlock(currentBlockLabel, List.copyOf(currentInstructions), terminator));
                    emittedBlocks.add(currentBlockLabel);
                    currentInstructions.clear();
                    currentBlockLabel = null;
                }
                case TableSwitchInsnNode switchInsn -> {
                    CoercedValue selectorValue = popPromotedInt(currentInstructions, stack, methodNode, nextValueId);
                    IrValue selector = selectorValue.value();
                    nextValueId = selectorValue.nextValueId();
                    IrTerminator.Switch terminator = lowerTableSwitch(methodNode, blockLabels, switchInsn, selector);
                    nextStorageSlot = spillStackToTargets(
                            methodNode,
                            currentInstructions,
                            stack,
                            switchTargets(terminator),
                            incomingStackTypesByBlock,
                            incomingStackSlotsByBlock,
                            emittedBlocks,
                            nextStorageSlot
                    );
                    blocks.add(new IrBlock(currentBlockLabel, List.copyOf(currentInstructions), terminator));
                    emittedBlocks.add(currentBlockLabel);
                    currentInstructions.clear();
                    currentBlockLabel = null;
                }
                case LookupSwitchInsnNode switchInsn -> {
                    CoercedValue selectorValue = popPromotedInt(currentInstructions, stack, methodNode, nextValueId);
                    IrValue selector = selectorValue.value();
                    nextValueId = selectorValue.nextValueId();
                    IrTerminator.Switch terminator = lowerLookupSwitch(methodNode, blockLabels, switchInsn, selector);
                    nextStorageSlot = spillStackToTargets(
                            methodNode,
                            currentInstructions,
                            stack,
                            switchTargets(terminator),
                            incomingStackTypesByBlock,
                            incomingStackSlotsByBlock,
                            emittedBlocks,
                            nextStorageSlot
                    );
                    blocks.add(new IrBlock(currentBlockLabel, List.copyOf(currentInstructions), terminator));
                    emittedBlocks.add(currentBlockLabel);
                    currentInstructions.clear();
                    currentBlockLabel = null;
                }
                case MethodInsnNode methodInsn -> {
                    nextValueId = handleInvoke(methodNode, currentInstructions, stack, methodInsn, nextValueId);
                    ProtectedExceptionEdgeResult edgeResult = maybeEmitProtectedExceptionEdge(
                            methodNode,
                            blockLabels,
                            methodInsn,
                            currentBlockLabel,
                            currentInstructions,
                            stack,
                            blocks,
                            incomingStackTypesByBlock,
                            incomingStackSlotsByBlock,
                            emittedBlocks,
                            nextStorageSlot,
                            nextValueId
                    );
                    nextStorageSlot = edgeResult.nextStorageSlot();
                    nextValueId = edgeResult.nextValueId();
                    if (edgeResult.terminatedBlock()) {
                        currentBlockLabel = null;
                    }
                }
                case InvokeDynamicInsnNode indyInsn -> {
                    nextValueId = handleInvokeDynamic(ownerInternalName, methodNode, currentInstructions, stack, indyInsn, nextValueId);
                    ProtectedExceptionEdgeResult edgeResult = maybeEmitProtectedExceptionEdge(
                            methodNode,
                            blockLabels,
                            indyInsn,
                            currentBlockLabel,
                            currentInstructions,
                            stack,
                            blocks,
                            incomingStackTypesByBlock,
                            incomingStackSlotsByBlock,
                            emittedBlocks,
                            nextStorageSlot,
                            nextValueId
                    );
                    nextStorageSlot = edgeResult.nextStorageSlot();
                    nextValueId = edgeResult.nextValueId();
                    if (edgeResult.terminatedBlock()) {
                        currentInstructions = new ArrayList<>();
                        currentBlockLabel = null;
                    }
                }
                case TypeInsnNode typeInsn -> {
                    nextValueId = handleTypeInstruction(methodNode, currentInstructions, stack, typeInsn, nextValueId);
                    ProtectedExceptionEdgeResult edgeResult = maybeEmitProtectedExceptionEdge(
                            methodNode,
                            blockLabels,
                            typeInsn,
                            currentBlockLabel,
                            currentInstructions,
                            stack,
                            blocks,
                            incomingStackTypesByBlock,
                            incomingStackSlotsByBlock,
                            emittedBlocks,
                            nextStorageSlot,
                            nextValueId
                    );
                    nextStorageSlot = edgeResult.nextStorageSlot();
                    nextValueId = edgeResult.nextValueId();
                    if (edgeResult.terminatedBlock()) {
                        currentBlockLabel = null;
                    }
                }
                case IincInsnNode iincInsn -> {
                    IrType localType = requireLocalType(methodNode, localTypes, iincInsn.var);
                    if (localType != IrType.INT) {
                        throw unsupported(methodNode, iincInsn, "iinc currently requires an int local");
                    }
                    IrValue loaded = new IrValue(nextValueId++, IrType.INT, "local");
                    int storageSlot = requireLocalStorageSlot(methodNode, localStorageSlots, iincInsn.var);
                    currentInstructions.add(new IrInstruction.LoadLocal(loaded, storageSlot));
                    IrValue delta = emitConst(currentInstructions, iincInsn.incr, nextValueId++);
                    IrValue updated = new IrValue(nextValueId++, IrType.INT, "tmp");
                    currentInstructions.add(new IrInstruction.Binary(updated, IrBinaryOpcode.ADD, loaded, delta));
                    currentInstructions.add(new IrInstruction.StoreLocal(storageSlot, updated));
                }
                case MultiANewArrayInsnNode multiArrayInsn -> {
                    nextValueId = handleMultiNewArray(methodNode, currentInstructions, stack, multiArrayInsn, nextValueId);
                    ProtectedExceptionEdgeResult edgeResult = maybeEmitProtectedExceptionEdge(
                            methodNode,
                            blockLabels,
                            multiArrayInsn,
                            currentBlockLabel,
                            currentInstructions,
                            stack,
                            blocks,
                            incomingStackTypesByBlock,
                            incomingStackSlotsByBlock,
                            emittedBlocks,
                            nextStorageSlot,
                            nextValueId
                    );
                    nextStorageSlot = edgeResult.nextStorageSlot();
                    nextValueId = edgeResult.nextValueId();
                    if (edgeResult.terminatedBlock()) {
                        currentBlockLabel = null;
                    }
                }
                default -> {
                    int opcode = instruction.getOpcode();
                    switch (opcode) {
                        case Opcodes.NOP -> {
                        }
                        case Opcodes.ACONST_NULL -> stack.push(emitNullConst(currentInstructions, nextValueId++));
                        case Opcodes.LCONST_0 -> stack.push(emitLongConst(currentInstructions, 0L, nextValueId++));
                        case Opcodes.LCONST_1 -> stack.push(emitLongConst(currentInstructions, 1L, nextValueId++));
                        case Opcodes.FCONST_0 -> stack.push(emitFloatConst(currentInstructions, 0.0f, nextValueId++));
                        case Opcodes.FCONST_1 -> stack.push(emitFloatConst(currentInstructions, 1.0f, nextValueId++));
                        case Opcodes.FCONST_2 -> stack.push(emitFloatConst(currentInstructions, 2.0f, nextValueId++));
                        case Opcodes.DCONST_0 -> stack.push(emitDoubleConst(currentInstructions, 0.0d, nextValueId++));
                        case Opcodes.DCONST_1 -> stack.push(emitDoubleConst(currentInstructions, 1.0d, nextValueId++));
                        case Opcodes.POP -> {
                            if (stack.isEmpty()) {
                                throw new UnsupportedBytecodeException("Operand stack underflow in " + methodNode.name + methodNode.desc);
                            }
                            stack.pop();
                        }
                        case Opcodes.POP2 -> popTwoCategorySlots(methodNode, stack);
                        case Opcodes.DUP -> duplicateTopOfStack(methodNode, stack);
                        case Opcodes.DUP2 -> duplicateTopTwoCategorySlots(methodNode, stack);
                        case Opcodes.DUP_X1 -> duplicateTopOfStackAndInsertBelowOne(methodNode, stack);
                        case Opcodes.DUP_X2 -> duplicateTopOfStackAndInsertBelowTwo(methodNode, stack);
                        case Opcodes.DUP2_X1 -> duplicateTopTwoCategorySlotsAndInsertBelowOne(methodNode, stack);
                        case Opcodes.ICONST_M1, Opcodes.ICONST_0, Opcodes.ICONST_1, Opcodes.ICONST_2,
                                Opcodes.ICONST_3, Opcodes.ICONST_4, Opcodes.ICONST_5 ->
                                stack.push(emitConst(currentInstructions, opcode - Opcodes.ICONST_0, nextValueId++));
                        case Opcodes.IALOAD -> {
                            nextValueId = handleArrayLoad(methodNode, currentInstructions, stack, IrType.INT, IrType.INT, nextValueId);
                        }
                        case Opcodes.BALOAD -> {
                            nextValueId = handleArrayLoad(methodNode, currentInstructions, stack, IrType.BYTE, IrType.INT, nextValueId);
                        }
                        case Opcodes.CALOAD -> {
                            nextValueId = handleArrayLoad(methodNode, currentInstructions, stack, IrType.CHAR, IrType.INT, nextValueId);
                        }
                        case Opcodes.SALOAD -> {
                            nextValueId = handleArrayLoad(methodNode, currentInstructions, stack, IrType.SHORT, IrType.INT, nextValueId);
                        }
                        case Opcodes.LALOAD -> {
                            nextValueId = handleArrayLoad(methodNode, currentInstructions, stack, IrType.LONG, IrType.LONG, nextValueId);
                        }
                        case Opcodes.FALOAD -> {
                            nextValueId = handleArrayLoad(methodNode, currentInstructions, stack, IrType.FLOAT, IrType.FLOAT, nextValueId);
                        }
                        case Opcodes.DALOAD -> {
                            nextValueId = handleArrayLoad(methodNode, currentInstructions, stack, IrType.DOUBLE, IrType.DOUBLE, nextValueId);
                        }
                        case Opcodes.AALOAD -> {
                            nextValueId = handleReferenceArrayLoad(methodNode, currentInstructions, stack, nextValueId);
                        }
                        case Opcodes.IASTORE -> {
                            nextValueId = handleArrayStore(methodNode, currentInstructions, stack, IrType.INT, nextValueId);
                        }
                        case Opcodes.BASTORE -> {
                            nextValueId = handleArrayStore(methodNode, currentInstructions, stack, IrType.BYTE, nextValueId);
                        }
                        case Opcodes.CASTORE -> {
                            nextValueId = handleArrayStore(methodNode, currentInstructions, stack, IrType.CHAR, nextValueId);
                        }
                        case Opcodes.SASTORE -> {
                            nextValueId = handleArrayStore(methodNode, currentInstructions, stack, IrType.SHORT, nextValueId);
                        }
                        case Opcodes.LASTORE -> {
                            nextValueId = handleArrayStore(methodNode, currentInstructions, stack, IrType.LONG, nextValueId);
                        }
                        case Opcodes.FASTORE -> {
                            nextValueId = handleArrayStore(methodNode, currentInstructions, stack, IrType.FLOAT, nextValueId);
                        }
                        case Opcodes.DASTORE -> {
                            nextValueId = handleArrayStore(methodNode, currentInstructions, stack, IrType.DOUBLE, nextValueId);
                        }
                        case Opcodes.AASTORE -> {
                            nextValueId = handleReferenceArrayStore(methodNode, currentInstructions, stack, nextValueId);
                        }
                        case Opcodes.ARRAYLENGTH -> {
                            IrValue array = popArray(stack, methodNode);
                            IrValue result = new IrValue(nextValueId++, IrType.INT, "len");
                            currentInstructions.add(new IrInstruction.CallHelper(
                                    result,
                                    "ir_rt_array_length",
                                    List.of(array)
                            ));
                            stack.push(result);
                        }
                        case Opcodes.INSTANCEOF -> {
                            if (!(instruction instanceof TypeInsnNode typeInsn)) {
                                throw unsupported(methodNode, instruction, "instanceof requires a type operand");
                            }
                            IrValue value = popReferenceLike(stack, methodNode);
                            IrType targetType = typeInsn.desc.startsWith("[")
                                    ? lowerType(Type.getType(typeInsn.desc))
                                    : IrType.reference(typeInsn.desc);
                            IrValue result = new IrValue(nextValueId++, IrType.BOOLEAN, "instanceof");
                            currentInstructions.add(new IrInstruction.CallHelper(
                                    result,
                                    instanceOfHelperName(targetType),
                                    List.of(value)
                            ));
                            stack.push(result);
                        }
                        case Opcodes.IADD, Opcodes.ISUB, Opcodes.IMUL, Opcodes.IDIV, Opcodes.IREM,
                                Opcodes.IAND, Opcodes.IOR, Opcodes.IXOR, Opcodes.ISHL, Opcodes.ISHR, Opcodes.IUSHR -> {
                            IrValue right = popIntLike(stack, methodNode);
                            if (right.type() != IrType.INT) {
                                CoercedValue coercedRight = coerceForExpectedType(currentInstructions, right, IrType.INT, nextValueId);
                                right = coercedRight.value();
                                nextValueId = coercedRight.nextValueId();
                            }
                            IrValue left = popIntLike(stack, methodNode);
                            if (left.type() != IrType.INT) {
                                CoercedValue coercedLeft = coerceForExpectedType(currentInstructions, left, IrType.INT, nextValueId);
                                left = coercedLeft.value();
                                nextValueId = coercedLeft.nextValueId();
                            }
                            IrValue result = new IrValue(nextValueId++, IrType.INT, "tmp");
                            currentInstructions.add(new IrInstruction.Binary(result, lowerBinaryOpcode(opcode), left, right));
                            stack.push(result);
                        }
                        case Opcodes.LADD, Opcodes.LSUB, Opcodes.LMUL, Opcodes.LDIV, Opcodes.LREM,
                                Opcodes.LAND, Opcodes.LOR, Opcodes.LXOR -> {
                            IrValue right = popExactType(stack, methodNode, IrType.LONG);
                            IrValue left = popExactType(stack, methodNode, IrType.LONG);
                            IrValue result = new IrValue(nextValueId++, IrType.LONG, "tmp");
                            currentInstructions.add(new IrInstruction.Binary(result, lowerBinaryOpcode(opcode), left, right));
                            stack.push(result);
                        }
                        case Opcodes.LSHL, Opcodes.LSHR, Opcodes.LUSHR -> {
                            IrValue right = popIntLike(stack, methodNode);
                            if (right.type() != IrType.INT) {
                                CoercedValue coercedRight = coerceForExpectedType(currentInstructions, right, IrType.INT, nextValueId);
                                right = coercedRight.value();
                                nextValueId = coercedRight.nextValueId();
                            }
                            IrValue mask = emitConst(currentInstructions, 63, nextValueId++);
                            IrValue masked = new IrValue(nextValueId++, IrType.INT, "tmp");
                            currentInstructions.add(new IrInstruction.Binary(masked, IrBinaryOpcode.AND, right, mask));
                            IrValue widenedShift = new IrValue(nextValueId++, IrType.LONG, "conv");
                            currentInstructions.add(new IrInstruction.Convert(widenedShift, masked));
                            IrValue left = popExactType(stack, methodNode, IrType.LONG);
                            IrValue result = new IrValue(nextValueId++, IrType.LONG, "tmp");
                            currentInstructions.add(new IrInstruction.Binary(result, lowerBinaryOpcode(opcode), left, widenedShift));
                            stack.push(result);
                        }
                        case Opcodes.FADD, Opcodes.FSUB, Opcodes.FMUL, Opcodes.FDIV, Opcodes.FREM -> {
                            IrValue right = popExactType(stack, methodNode, IrType.FLOAT);
                            IrValue left = popExactType(stack, methodNode, IrType.FLOAT);
                            IrValue result = new IrValue(nextValueId++, IrType.FLOAT, "tmp");
                            currentInstructions.add(new IrInstruction.Binary(result, lowerBinaryOpcode(opcode), left, right));
                            stack.push(result);
                        }
                        case Opcodes.DADD, Opcodes.DSUB, Opcodes.DMUL, Opcodes.DDIV, Opcodes.DREM -> {
                            IrValue right = popExactType(stack, methodNode, IrType.DOUBLE);
                            IrValue left = popExactType(stack, methodNode, IrType.DOUBLE);
                            IrValue result = new IrValue(nextValueId++, IrType.DOUBLE, "tmp");
                            currentInstructions.add(new IrInstruction.Binary(result, lowerBinaryOpcode(opcode), left, right));
                            stack.push(result);
                        }
                        case Opcodes.LCMP, Opcodes.FCMPL, Opcodes.FCMPG, Opcodes.DCMPL, Opcodes.DCMPG -> {
                            IrType operandType = switch (opcode) {
                                case Opcodes.LCMP -> IrType.LONG;
                                case Opcodes.FCMPL, Opcodes.FCMPG -> IrType.FLOAT;
                                case Opcodes.DCMPL, Opcodes.DCMPG -> IrType.DOUBLE;
                                default -> throw new IllegalStateException("Unexpected compare helper opcode " + opcode);
                            };
                            IrValue right = popExactType(stack, methodNode, operandType);
                            IrValue left = popExactType(stack, methodNode, operandType);
                            IrValue result = new IrValue(nextValueId++, IrType.INT, "cmp");
                            currentInstructions.add(new IrInstruction.CallHelper(
                                    result,
                                    compareHelperName(opcode),
                                    List.of(left, right)
                            ));
                            stack.push(result);
                        }
                        case Opcodes.I2L, Opcodes.I2F, Opcodes.I2D,
                                Opcodes.I2B, Opcodes.I2C, Opcodes.I2S,
                                Opcodes.L2I, Opcodes.L2F, Opcodes.L2D,
                                Opcodes.F2I, Opcodes.F2L, Opcodes.F2D,
                                Opcodes.D2I, Opcodes.D2L, Opcodes.D2F -> {
                            IrValue source = popConversionSource(stack, methodNode, opcode);
                            IrType targetType = lowerConvertTargetType(opcode);
                            IrValue converted = new IrValue(nextValueId++, targetType, "conv");
                            currentInstructions.add(new IrInstruction.Convert(converted, source));
                            stack.push(converted);
                        }
                        case Opcodes.INEG -> nextValueId = handleNegation(methodNode, currentInstructions, stack, IrType.INT, nextValueId);
                        case Opcodes.LNEG -> nextValueId = handleNegation(methodNode, currentInstructions, stack, IrType.LONG, nextValueId);
                        case Opcodes.FNEG -> nextValueId = handleNegation(methodNode, currentInstructions, stack, IrType.FLOAT, nextValueId);
                        case Opcodes.DNEG -> nextValueId = handleNegation(methodNode, currentInstructions, stack, IrType.DOUBLE, nextValueId);
                        case Opcodes.MONITORENTER -> {
                            IrValue monitor = popReferenceLike(stack, methodNode);
                            currentInstructions.add(new IrInstruction.CallHelperVoid("ir_rt_monitor_enter", List.of(monitor)));
                        }
                        case Opcodes.MONITOREXIT -> {
                            IrValue monitor = popReferenceLike(stack, methodNode);
                            currentInstructions.add(new IrInstruction.CallHelperVoid("ir_rt_monitor_exit", List.of(monitor)));
                        }
                        case Opcodes.IRETURN -> {
                            ensureStackShape(methodNode, stack, 1, "IRETURN");
                            CoercedValue coercedReturn = coerceForExpectedType(
                                    currentInstructions,
                                    popIntLike(stack, methodNode),
                                    lowerType(Type.getReturnType(methodNode.desc)),
                                    nextValueId
                            );
                            IrValue returnValue = coercedReturn.value();
                            nextValueId = coercedReturn.nextValueId();
                            blocks.add(new IrBlock(currentBlockLabel, List.copyOf(currentInstructions), new IrTerminator.Return(returnValue)));
                            emittedBlocks.add(currentBlockLabel);
                            currentInstructions.clear();
                            currentBlockLabel = null;
                        }
                        case Opcodes.LRETURN, Opcodes.FRETURN, Opcodes.DRETURN -> {
                            ensureStackShape(methodNode, stack, 1, opcodeName(opcode));
                            IrType returnType = lowerType(Type.getReturnType(methodNode.desc));
                            CoercedValue coercedReturn = coerceForExpectedType(
                                    currentInstructions,
                                    popValueOfExpectedType(stack, methodNode, returnType, opcodeName(opcode)),
                                    returnType,
                                    nextValueId
                            );
                            IrValue returnValue = coercedReturn.value();
                            nextValueId = coercedReturn.nextValueId();
                            blocks.add(new IrBlock(currentBlockLabel, List.copyOf(currentInstructions), new IrTerminator.Return(returnValue)));
                            emittedBlocks.add(currentBlockLabel);
                            currentInstructions.clear();
                            currentBlockLabel = null;
                        }
                        case Opcodes.ARETURN -> {
                            ensureStackShape(methodNode, stack, 1, "ARETURN");
                            CoercedValue coercedReturn = coerceForExpectedType(
                                    currentInstructions,
                                    popReferenceLike(stack, methodNode),
                                    lowerType(Type.getReturnType(methodNode.desc)),
                                    nextValueId
                            );
                            IrValue returnValue = coercedReturn.value();
                            nextValueId = coercedReturn.nextValueId();
                            blocks.add(new IrBlock(currentBlockLabel, List.copyOf(currentInstructions), new IrTerminator.Return(returnValue)));
                            emittedBlocks.add(currentBlockLabel);
                            currentInstructions.clear();
                            currentBlockLabel = null;
                        }
                        case Opcodes.ATHROW -> {
                            if (stack.isEmpty()) {
                                throw new UnsupportedBytecodeException("Operand stack underflow in " + methodNode.name + methodNode.desc);
                            }
                            IrValue exceptionValue = popReferenceLike(stack, methodNode);
                            stack.clear();
                            ExceptionHandlerEdge handler = firstCoveringExceptionHandler(methodNode, blockLabels, instruction);
                            if (handler != null && handler.isBroad()) {
                                currentInstructions.add(new IrInstruction.CallHelperVoid("ir_rt_throw", List.of(exceptionValue)));
                                blocks.add(new IrBlock(currentBlockLabel, List.copyOf(currentInstructions), new IrTerminator.Goto(handler.targetBlock())));
                            } else if (handler != null) {
                                int thrownSlot = nextStorageSlot++;
                                currentInstructions.add(new IrInstruction.StoreLocal(thrownSlot, exceptionValue));
                                IrValue matches = new IrValue(nextValueId++, IrType.BOOLEAN, "throw_match");
                                currentInstructions.add(new IrInstruction.CallHelper(
                                        matches,
                                        instanceOfHelperName(handler.catchType()),
                                        List.of(exceptionValue)
                                ));

                                String handlerRelayTarget = currentBlockLabel + "$throw_handler_" + nextValueId;
                                String rethrowTarget = currentBlockLabel + "$throw_rethrow_" + nextValueId;
                                blocks.add(new IrBlock(
                                        currentBlockLabel,
                                        List.copyOf(currentInstructions),
                                        new IrTerminator.Branch(matches, handlerRelayTarget, rethrowTarget)
                                ));
                                emittedBlocks.add(currentBlockLabel);

                                IrValue handlerCaught = new IrValue(nextValueId++, handler.catchType(), "caught");
                                ArrayList<IrInstruction> handlerRelayInstructions = new ArrayList<>();
                                handlerRelayInstructions.add(new IrInstruction.LoadLocal(handlerCaught, thrownSlot));
                                handlerRelayInstructions.add(new IrInstruction.CallHelperVoid("ir_rt_throw", List.of(handlerCaught)));
                                blocks.add(new IrBlock(
                                        handlerRelayTarget,
                                        List.copyOf(handlerRelayInstructions),
                                        new IrTerminator.Goto(handler.targetBlock())
                                ));
                                emittedBlocks.add(handlerRelayTarget);

                                IrValue rethrowCaught = new IrValue(nextValueId++, handler.catchType(), "caught");
                                ArrayList<IrInstruction> rethrowInstructions = new ArrayList<>();
                                rethrowInstructions.add(new IrInstruction.LoadLocal(rethrowCaught, thrownSlot));
                                blocks.add(new IrBlock(
                                        rethrowTarget,
                                        List.copyOf(rethrowInstructions),
                                        new IrTerminator.Throw(rethrowCaught)
                                ));
                                emittedBlocks.add(rethrowTarget);
                            } else {
                                blocks.add(new IrBlock(currentBlockLabel, List.copyOf(currentInstructions), new IrTerminator.Throw(exceptionValue)));
                            }
                            emittedBlocks.add(currentBlockLabel);
                            currentInstructions.clear();
                            currentBlockLabel = null;
                        }
                        case Opcodes.RETURN -> {
                            ensureStackShape(methodNode, stack, 0, "RETURN");
                            blocks.add(new IrBlock(currentBlockLabel, List.copyOf(currentInstructions), new IrTerminator.ReturnVoid()));
                            emittedBlocks.add(currentBlockLabel);
                            currentInstructions.clear();
                            currentBlockLabel = null;
                        }
                        default -> throw unsupported(methodNode, instruction, "opcode is not implemented in the current slice");
                    }
                    ProtectedExceptionEdgeResult edgeResult = maybeEmitProtectedExceptionEdge(
                            methodNode,
                            blockLabels,
                            instruction,
                            currentBlockLabel,
                            currentInstructions,
                            stack,
                            blocks,
                            incomingStackTypesByBlock,
                            incomingStackSlotsByBlock,
                            emittedBlocks,
                            nextStorageSlot,
                            nextValueId
                    );
                    nextStorageSlot = edgeResult.nextStorageSlot();
                    nextValueId = edgeResult.nextValueId();
                    if (edgeResult.terminatedBlock()) {
                        currentBlockLabel = null;
                    }
                }
            }
        }

        if (currentBlockLabel != null) {
            throw new UnsupportedBytecodeException("Method " + methodNode.name + methodNode.desc
                    + " ended without an explicit terminator in block " + currentBlockLabel);
        }

        IrMethod method = createMethod(methodNode, blocks, nextStorageSlot);
        validator.validate(method);
        return method;
    }

    private LinkedHashSet<AbstractInsnNode> collectBlockStarts(MethodNode methodNode, AbstractInsnNode entryInstruction) {
        LinkedHashSet<AbstractInsnNode> blockStarts = new LinkedHashSet<>();
        blockStarts.add(entryInstruction);

        for (AbstractInsnNode instruction = methodNode.instructions.getFirst();
             instruction != null;
             instruction = instruction.getNext()) {
            if (shouldIgnore(instruction)) {
                continue;
            }

            if (instruction instanceof JumpInsnNode jumpInsn) {
                addBlockStart(blockStarts, nextExecutable(jumpInsn.label));
                addBlockStart(blockStarts, nextExecutable(jumpInsn.getNext()));
                continue;
            }

            if (instruction instanceof TableSwitchInsnNode tableSwitchInsn) {
                addBlockStart(blockStarts, nextExecutable(tableSwitchInsn.dflt));
                for (LabelNode label : tableSwitchInsn.labels) {
                    addBlockStart(blockStarts, nextExecutable(label));
                }
                continue;
            }

            if (instruction instanceof LookupSwitchInsnNode lookupSwitchInsn) {
                addBlockStart(blockStarts, nextExecutable(lookupSwitchInsn.dflt));
                for (LabelNode label : lookupSwitchInsn.labels) {
                    addBlockStart(blockStarts, nextExecutable(label));
                }
                continue;
            }

            if (requiresExceptionCheck(instruction)) {
                addBlockStart(blockStarts, nextExecutable(instruction.getNext()));
            }

            int opcode = instruction.getOpcode();
            if (opcode == Opcodes.IRETURN || opcode == Opcodes.LRETURN || opcode == Opcodes.FRETURN
                    || opcode == Opcodes.DRETURN || opcode == Opcodes.ARETURN || opcode == Opcodes.RETURN
                    || opcode == Opcodes.ATHROW) {
                addBlockStart(blockStarts, nextExecutable(instruction.getNext()));
            }
        }

        if (methodNode.tryCatchBlocks != null) {
            for (TryCatchBlockNode tryCatchBlock : methodNode.tryCatchBlocks) {
                addBlockStart(blockStarts, nextExecutable(tryCatchBlock.handler));
            }
        }

        return blockStarts;
    }

    private Map<String, IrType> collectExceptionHandlerEntryTypes(MethodNode methodNode,
                                                                  IdentityHashMap<AbstractInsnNode, String> blockLabels) {
        HashMap<String, IrType> handlerTypes = new HashMap<>();
        if (methodNode.tryCatchBlocks == null) {
            return handlerTypes;
        }
        for (TryCatchBlockNode tryCatchBlock : methodNode.tryCatchBlocks) {
            AbstractInsnNode handlerInstruction = nextExecutable(tryCatchBlock.handler);
            String blockLabel = blockLabels.get(handlerInstruction);
            if (blockLabel == null) {
                continue;
            }
            IrType catchType = tryCatchBlock.type == null
                    ? IrType.reference("java/lang/Throwable")
                    : IrType.reference(tryCatchBlock.type);
            handlerTypes.merge(blockLabel, catchType, this::mergeCatchTypes);
        }
        return handlerTypes;
    }

    private ProtectedExceptionEdgeResult maybeEmitProtectedExceptionEdge(MethodNode methodNode,
                                                                        IdentityHashMap<AbstractInsnNode, String> blockLabels,
                                                                        AbstractInsnNode instruction,
                                                                        String currentBlockLabel,
                                                                        List<IrInstruction> currentInstructions,
                                                                        Deque<IrValue> stack,
                                                                        List<IrBlock> blocks,
                                                                        Map<String, List<IrType>> incomingStackTypesByBlock,
                                                                        Map<String, List<Integer>> incomingStackSlotsByBlock,
                                                                        Set<String> emittedBlocks,
                                                                        int nextStorageSlot,
                                                                        int nextValueId) {
        if (currentBlockLabel == null || !requiresExceptionCheck(instruction)) {
            return new ProtectedExceptionEdgeResult(nextStorageSlot, nextValueId, false);
        }
        ExceptionHandlerEdge handler = firstCoveringExceptionHandler(methodNode, blockLabels, instruction);
        AbstractInsnNode nextInstruction = nextExecutable(instruction.getNext());
        if (nextInstruction == null) {
            return new ProtectedExceptionEdgeResult(nextStorageSlot, nextValueId, false);
        }
        String normalTarget = requiredBlockLabel(methodNode, blockLabels, nextInstruction);
        IrValue pending = new IrValue(nextValueId++, IrType.BOOLEAN, "exc");
        currentInstructions.add(new IrInstruction.CallHelper(pending, "ir_rt_exception_pending", List.of()));
        nextStorageSlot = spillStackToTargets(
                methodNode,
                currentInstructions,
                stack,
                List.of(normalTarget),
                incomingStackTypesByBlock,
                incomingStackSlotsByBlock,
                emittedBlocks,
                nextStorageSlot
        );
        if (handler == null) {
            String rethrowTarget = currentBlockLabel + "$exc_unhandled_" + nextValueId;
            blocks.add(new IrBlock(currentBlockLabel, List.copyOf(currentInstructions), new IrTerminator.Branch(pending, rethrowTarget, normalTarget)));
            emittedBlocks.add(currentBlockLabel);
            currentInstructions.clear();
            stack.clear();

            IrValue currentException = new IrValue(nextValueId++, IrType.reference("java/lang/Throwable"), "caught");
            ArrayList<IrInstruction> rethrowInstructions = new ArrayList<>();
            rethrowInstructions.add(new IrInstruction.CallHelper(currentException, "ir_rt_current_exception", List.of()));
            blocks.add(new IrBlock(rethrowTarget, List.copyOf(rethrowInstructions), new IrTerminator.Throw(currentException)));
            emittedBlocks.add(rethrowTarget);
            return new ProtectedExceptionEdgeResult(nextStorageSlot, nextValueId, true);
        }
        if (handler.isBroad()) {
            blocks.add(new IrBlock(currentBlockLabel, List.copyOf(currentInstructions), new IrTerminator.Branch(pending, handler.targetBlock(), normalTarget)));
            emittedBlocks.add(currentBlockLabel);
            currentInstructions.clear();
            stack.clear();
            return new ProtectedExceptionEdgeResult(nextStorageSlot, nextValueId, true);
        }
        String matchTarget = currentBlockLabel + "$exc_match_" + nextValueId;
        String handlerRelayTarget = currentBlockLabel + "$exc_handler_" + nextValueId;
        String rethrowTarget = currentBlockLabel + "$exc_rethrow_" + nextValueId;
        blocks.add(new IrBlock(currentBlockLabel, List.copyOf(currentInstructions), new IrTerminator.Branch(pending, matchTarget, normalTarget)));
        emittedBlocks.add(currentBlockLabel);
        currentInstructions.clear();
        stack.clear();

        int caughtSlot = nextStorageSlot++;
        IrValue caught = new IrValue(nextValueId++, handler.catchType(), "caught");
        IrValue matches = new IrValue(nextValueId++, IrType.BOOLEAN, "match");
        ArrayList<IrInstruction> matchInstructions = new ArrayList<>();
        matchInstructions.add(new IrInstruction.CallHelper(caught, "ir_rt_current_exception", List.of()));
        matchInstructions.add(new IrInstruction.StoreLocal(caughtSlot, caught));
        matchInstructions.add(new IrInstruction.CallHelper(matches, instanceOfHelperName(handler.catchType()), List.of(caught)));
        blocks.add(new IrBlock(matchTarget, List.copyOf(matchInstructions), new IrTerminator.Branch(matches, handlerRelayTarget, rethrowTarget)));
        emittedBlocks.add(matchTarget);

        IrValue handlerCaught = new IrValue(nextValueId++, handler.catchType(), "caught");
        ArrayList<IrInstruction> handlerRelayInstructions = new ArrayList<>();
        handlerRelayInstructions.add(new IrInstruction.LoadLocal(handlerCaught, caughtSlot));
        handlerRelayInstructions.add(new IrInstruction.CallHelperVoid("ir_rt_throw", List.of(handlerCaught)));
        blocks.add(new IrBlock(handlerRelayTarget, List.copyOf(handlerRelayInstructions), new IrTerminator.Goto(handler.targetBlock())));
        emittedBlocks.add(handlerRelayTarget);

        IrValue rethrowCaught = new IrValue(nextValueId++, handler.catchType(), "caught");
        ArrayList<IrInstruction> rethrowInstructions = new ArrayList<>();
        rethrowInstructions.add(new IrInstruction.LoadLocal(rethrowCaught, caughtSlot));
        blocks.add(new IrBlock(rethrowTarget, List.copyOf(rethrowInstructions), new IrTerminator.Throw(rethrowCaught)));
        emittedBlocks.add(rethrowTarget);
        return new ProtectedExceptionEdgeResult(nextStorageSlot, nextValueId, true);
    }

    private boolean requiresExceptionCheck(AbstractInsnNode instruction) {
        return instruction instanceof MethodInsnNode
                || instruction instanceof InvokeDynamicInsnNode
                || instruction instanceof FieldInsnNode
                || (instruction instanceof IntInsnNode intInsnNode
                && intInsnNode.getOpcode() == Opcodes.NEWARRAY)
                || (instruction instanceof TypeInsnNode typeInsnNode
                && (typeInsnNode.getOpcode() == Opcodes.NEW
                || typeInsnNode.getOpcode() == Opcodes.ANEWARRAY
                || typeInsnNode.getOpcode() == Opcodes.CHECKCAST))
                || isPotentiallyThrowingArrayInstruction(instruction.getOpcode());
    }

    private boolean isPotentiallyThrowingArrayInstruction(int opcode) {
        return switch (opcode) {
            case Opcodes.IALOAD, Opcodes.LALOAD, Opcodes.FALOAD, Opcodes.DALOAD,
                    Opcodes.AALOAD, Opcodes.BALOAD, Opcodes.CALOAD, Opcodes.SALOAD,
                    Opcodes.IASTORE, Opcodes.LASTORE, Opcodes.FASTORE, Opcodes.DASTORE,
                    Opcodes.AASTORE, Opcodes.BASTORE, Opcodes.CASTORE, Opcodes.SASTORE,
                    Opcodes.ARRAYLENGTH -> true;
            default -> false;
        };
    }

    private ExceptionHandlerEdge firstCoveringExceptionHandler(MethodNode methodNode,
                                                               IdentityHashMap<AbstractInsnNode, String> blockLabels,
                                                               AbstractInsnNode instruction) {
        if (methodNode.tryCatchBlocks == null) {
            return null;
        }
        for (TryCatchBlockNode tryCatchBlock : methodNode.tryCatchBlocks) {
            if (!coversInstruction(tryCatchBlock, instruction)) {
                continue;
            }
            IrType catchType = tryCatchBlock.type == null
                    ? IrType.reference("java/lang/Throwable")
                    : IrType.reference(tryCatchBlock.type);
            boolean broad = tryCatchBlock.type == null || "java/lang/Throwable".equals(tryCatchBlock.type);
            if (blockLabels == null) {
                return new ExceptionHandlerEdge("<exception-handler>", catchType, broad);
            }
            return new ExceptionHandlerEdge(
                    requiredBlockLabel(methodNode, blockLabels, nextExecutable(tryCatchBlock.handler)),
                    catchType,
                    broad
            );
        }
        return null;
    }

    private boolean coversInstruction(TryCatchBlockNode tryCatchBlock, AbstractInsnNode instruction) {
        for (AbstractInsnNode current = tryCatchBlock.start; current != null && current != tryCatchBlock.end; current = current.getNext()) {
            if (current == instruction) {
                return true;
            }
        }
        return false;
    }

    private IrType mergeCatchTypes(IrType left, IrType right) {
        if (left.equals(right)) {
            return left;
        }
        return IrType.reference("java/lang/Throwable");
    }

    private IdentityHashMap<AbstractInsnNode, String> assignBlockLabels(Set<AbstractInsnNode> blockStarts, AbstractInsnNode entryInstruction) {
        IdentityHashMap<AbstractInsnNode, String> blockLabels = new IdentityHashMap<>();
        int index = 0;
        for (AbstractInsnNode start : blockStarts) {
            blockLabels.put(start, start == entryInstruction ? "entry" : "block" + index++);
        }
        return blockLabels;
    }

    private boolean shouldIgnore(AbstractInsnNode instruction) {
        return instruction instanceof LabelNode
                || instruction instanceof LineNumberNode
                || instruction instanceof FrameNode;
    }

    private AbstractInsnNode nextExecutable(AbstractInsnNode instruction) {
        AbstractInsnNode cursor = instruction;
        while (cursor != null && shouldIgnore(cursor)) {
            cursor = cursor.getNext();
        }
        return cursor;
    }

    private void addBlockStart(Set<AbstractInsnNode> blockStarts, AbstractInsnNode instruction) {
        if (instruction != null) {
            blockStarts.add(instruction);
        }
    }

    private IrValue emitConst(List<IrInstruction> instructions, int value, int id) {
        IrValue constant = new IrValue(id, IrType.INT, "const");
        instructions.add(new IrInstruction.Const(constant, value));
        return constant;
    }

    private IrValue emitBooleanConst(List<IrInstruction> instructions, boolean value, int id) {
        IrValue constant = new IrValue(id, IrType.BOOLEAN, "const");
        instructions.add(new IrInstruction.Const(constant, value));
        return constant;
    }

    private IrValue emitLongConst(List<IrInstruction> instructions, long value, int id) {
        IrValue constant = new IrValue(id, IrType.LONG, "const");
        instructions.add(new IrInstruction.Const(constant, value));
        return constant;
    }

    private IrValue emitFloatConst(List<IrInstruction> instructions, float value, int id) {
        IrValue constant = new IrValue(id, IrType.FLOAT, "const");
        instructions.add(new IrInstruction.Const(constant, value));
        return constant;
    }

    private IrValue emitDoubleConst(List<IrInstruction> instructions, double value, int id) {
        IrValue constant = new IrValue(id, IrType.DOUBLE, "const");
        instructions.add(new IrInstruction.Const(constant, value));
        return constant;
    }

    private IrValue emitNullConst(List<IrInstruction> instructions, int id) {
        IrValue constant = new IrValue(id, IrType.reference("java/lang/Object"), "null");
        instructions.add(new IrInstruction.Const(constant, null));
        return constant;
    }

    private IrValue emitTypedNullConst(List<IrInstruction> instructions, IrType type, int id) {
        IrValue constant = new IrValue(id, type, "null");
        instructions.add(new IrInstruction.Const(constant, null));
        return constant;
    }

    private IrValue emitStringConstant(List<IrInstruction> instructions, String value, int id) {
        IrValue constant = new IrValue(id, IrType.reference("java/lang/String"), "const");
        instructions.add(new IrInstruction.CallHelper(constant, stringConstantHelperName(value), List.of()));
        return constant;
    }

    private IrValue emitClassConstant(List<IrInstruction> instructions, Type type, int id) {
        IrValue constant = new IrValue(id, IrType.reference("java/lang/Class"), "const");
        instructions.add(new IrInstruction.CallHelper(constant, classConstantHelperName(type), List.of()));
        return constant;
    }

    private IrValue emitCaughtExceptionValue(List<IrInstruction> instructions, IrType exceptionType, int id) {
        IrValue exceptionValue = new IrValue(id, exceptionType, "caught");
        instructions.add(new IrInstruction.CallHelper(exceptionValue, "ir_rt_current_exception", List.of()));
        return exceptionValue;
    }

    private int restoreIncomingStack(List<IrInstruction> currentInstructions, Deque<IrValue> stack, String blockLabel,
                                     Map<String, List<IrType>> incomingStackTypesByBlock,
                                     Map<String, List<Integer>> incomingStackSlotsByBlock,
                                     int nextValueId) {
        List<IrType> incomingTypes = incomingStackTypesByBlock.get(blockLabel);
        List<Integer> incomingSlots = incomingStackSlotsByBlock.get(blockLabel);
        if (incomingTypes == null || incomingTypes.isEmpty()) {
            return nextValueId;
        }
        for (int index = 0; index < incomingTypes.size(); index++) {
            IrValue loaded = new IrValue(nextValueId++, incomingTypes.get(index), "stack");
            currentInstructions.add(new IrInstruction.LoadLocal(loaded, incomingSlots.get(index)));
            stack.push(loaded);
        }
        return nextValueId;
    }

    private int spillStackToTargets(MethodNode methodNode, List<IrInstruction> currentInstructions, Deque<IrValue> stack,
                                    List<String> targetLabels,
                                    Map<String, List<IrType>> incomingStackTypesByBlock,
                                    Map<String, List<Integer>> incomingStackSlotsByBlock,
                                    Set<String> emittedBlocks,
                                    int nextStorageSlot) {
        if (stack.isEmpty()) {
            return nextStorageSlot;
        }

        List<IrValue> incomingValues = snapshotStackBottomToTop(stack);
        List<IrType> incomingTypes = incomingValues.stream().map(IrValue::type).toList();
        for (String targetLabel : targetLabels) {
            nextStorageSlot = registerIncomingStack(
                    methodNode,
                    targetLabel,
                    incomingTypes,
                    incomingStackTypesByBlock,
                    incomingStackSlotsByBlock,
                    emittedBlocks,
                    nextStorageSlot
            );
            List<Integer> spillSlots = incomingStackSlotsByBlock.get(targetLabel);
            for (int index = 0; index < incomingValues.size(); index++) {
                currentInstructions.add(new IrInstruction.StoreLocal(spillSlots.get(index), incomingValues.get(index)));
            }
        }
        stack.clear();
        return nextStorageSlot;
    }

    private int registerIncomingStack(MethodNode methodNode, String targetLabel, List<IrType> incomingTypes,
                                      Map<String, List<IrType>> incomingStackTypesByBlock,
                                      Map<String, List<Integer>> incomingStackSlotsByBlock,
                                      Set<String> emittedBlocks,
                                      int nextStorageSlot) {
        List<IrType> existingTypes = incomingStackTypesByBlock.get(targetLabel);
        if (existingTypes == null) {
            if (emittedBlocks.contains(targetLabel)) {
                throw new UnsupportedBytecodeException("Cannot retroactively assign stack-carry inputs for already emitted block "
                        + targetLabel + " in " + methodNode.name + methodNode.desc);
            }
            incomingStackTypesByBlock.put(targetLabel, List.copyOf(incomingTypes));
            ArrayList<Integer> spillSlots = new ArrayList<>(incomingTypes.size());
            for (int index = 0; index < incomingTypes.size(); index++) {
                spillSlots.add(nextStorageSlot++);
            }
            incomingStackSlotsByBlock.put(targetLabel, List.copyOf(spillSlots));
            return nextStorageSlot;
        }

        if (existingTypes.size() != incomingTypes.size()) {
            throw new UnsupportedBytecodeException("Incompatible stack-carry merge into block " + targetLabel + " in "
                    + methodNode.name + methodNode.desc + ": expected " + existingTypes.size()
                    + " value(s) but found " + incomingTypes.size());
        }

        ArrayList<IrType> mergedTypes = new ArrayList<>(existingTypes.size());
        boolean changed = false;
        for (int index = 0; index < existingTypes.size(); index++) {
            IrType mergedType = mergeIncomingStackType(methodNode, targetLabel, existingTypes.get(index), incomingTypes.get(index));
            mergedTypes.add(mergedType);
            if (!mergedType.equals(existingTypes.get(index))) {
                changed = true;
            }
        }

        if (changed) {
            if (emittedBlocks.contains(targetLabel)) {
                throw new UnsupportedBytecodeException("Cannot widen incoming stack types for already emitted block "
                        + targetLabel + " in " + methodNode.name + methodNode.desc);
            }
            incomingStackTypesByBlock.put(targetLabel, List.copyOf(mergedTypes));
        }
        return nextStorageSlot;
    }

    private IrType mergeIncomingStackType(MethodNode methodNode, String targetLabel, IrType existingType, IrType incomingType) {
        if (existingType.equals(incomingType)) {
            return existingType;
        }
        if (isIntLike(existingType) && isIntLike(incomingType)) {
            return IrType.INT;
        }
        if (isReferenceLike(existingType) && isReferenceLike(incomingType)) {
            return IrType.reference("java/lang/Object");
        }
        throw new UnsupportedBytecodeException("Incompatible stack-carry merge into block " + targetLabel + " in "
                + methodNode.name + methodNode.desc + ": " + existingType.displayName()
                + " vs " + incomingType.displayName());
    }

    private List<IrValue> snapshotStackBottomToTop(Deque<IrValue> stack) {
        ArrayList<IrValue> values = new ArrayList<>(stack.size());
        for (IrValue value : stack) {
            values.add(0, value);
        }
        return List.copyOf(values);
    }

    private ConditionEmission emitJumpCondition(MethodNode methodNode, List<IrInstruction> instructions, Deque<IrValue> stack,
                                                JumpInsnNode jumpInsn, int nextValueId) {
        IrCompareOpcode compareOpcode = lowerCompareOpcode(jumpInsn.getOpcode());

        IrValue left;
        IrValue right;
        int consumedIds;
        if (isSingleIntOperandJump(jumpInsn.getOpcode())) {
            left = popIntLike(stack, methodNode);
            if (left.type() == IrType.BOOLEAN) {
                right = emitBooleanConst(instructions, false, nextValueId);
                consumedIds = 2;
            } else if (left.type() == IrType.INT) {
                right = emitConst(instructions, 0, nextValueId);
                consumedIds = 2;
            } else {
                IrValue zero = emitConst(instructions, 0, nextValueId);
                CoercedValue coercedZero = coerceForExpectedType(instructions, zero, left.type(), nextValueId + 1);
                right = coercedZero.value();
                consumedIds = coercedZero.nextValueId() - nextValueId + 1;
            }
        } else if (isSingleReferenceOperandJump(jumpInsn.getOpcode())) {
            left = popReferenceLike(stack, methodNode);
            right = emitTypedNullConst(instructions, left.type(), nextValueId);
            consumedIds = 2;
        } else if (isDoubleReferenceOperandJump(jumpInsn.getOpcode())) {
            right = popReferenceLike(stack, methodNode);
            left = popReferenceLike(stack, methodNode);
            if (!left.type().equals(right.type())) {
                CoercedValue coercedRight = coerceForExpectedType(instructions, right, left.type(), nextValueId);
                right = coercedRight.value();
                consumedIds = coercedRight.nextValueId() - nextValueId + 1;
            } else {
                consumedIds = 1;
            }
        } else {
            right = popIntLike(stack, methodNode);
            left = popIntLike(stack, methodNode);
            if (!left.type().equals(right.type())) {
                CoercedValue coercedRight = coerceForExpectedType(instructions, right, left.type(), nextValueId);
                right = coercedRight.value();
                consumedIds = coercedRight.nextValueId() - nextValueId + 1;
            } else {
                consumedIds = 1;
            }
        }

        IrValue condition = new IrValue(nextValueId + consumedIds - 1, IrType.BOOLEAN, "cmp");
        instructions.add(new IrInstruction.Compare(condition, compareOpcode, left, right));
        return new ConditionEmission(condition, consumedIds);
    }

    private boolean isSingleIntOperandJump(int opcode) {
        return switch (opcode) {
            case Opcodes.IFEQ, Opcodes.IFNE, Opcodes.IFLT, Opcodes.IFGE, Opcodes.IFGT, Opcodes.IFLE -> true;
            case Opcodes.IF_ICMPEQ, Opcodes.IF_ICMPNE, Opcodes.IF_ICMPLT, Opcodes.IF_ICMPGE, Opcodes.IF_ICMPGT, Opcodes.IF_ICMPLE,
                    Opcodes.IF_ACMPEQ, Opcodes.IF_ACMPNE, Opcodes.IFNULL, Opcodes.IFNONNULL -> false;
            default -> throw new IllegalArgumentException("Unsupported conditional jump opcode: " + opcode);
        };
    }

    private boolean isSingleReferenceOperandJump(int opcode) {
        return switch (opcode) {
            case Opcodes.IFNULL, Opcodes.IFNONNULL -> true;
            case Opcodes.IFEQ, Opcodes.IFNE, Opcodes.IFLT, Opcodes.IFGE, Opcodes.IFGT, Opcodes.IFLE,
                    Opcodes.IF_ICMPEQ, Opcodes.IF_ICMPNE, Opcodes.IF_ICMPLT, Opcodes.IF_ICMPGE, Opcodes.IF_ICMPGT, Opcodes.IF_ICMPLE,
                    Opcodes.IF_ACMPEQ, Opcodes.IF_ACMPNE -> false;
            default -> throw new IllegalArgumentException("Unsupported conditional jump opcode: " + opcode);
        };
    }

    private boolean isDoubleReferenceOperandJump(int opcode) {
        return switch (opcode) {
            case Opcodes.IF_ACMPEQ, Opcodes.IF_ACMPNE -> true;
            case Opcodes.IFEQ, Opcodes.IFNE, Opcodes.IFLT, Opcodes.IFGE, Opcodes.IFGT, Opcodes.IFLE,
                    Opcodes.IF_ICMPEQ, Opcodes.IF_ICMPNE, Opcodes.IF_ICMPLT, Opcodes.IF_ICMPGE, Opcodes.IF_ICMPGT, Opcodes.IF_ICMPLE,
                    Opcodes.IFNULL, Opcodes.IFNONNULL -> false;
            default -> throw new IllegalArgumentException("Unsupported conditional jump opcode: " + opcode);
        };
    }

    private IrCompareOpcode lowerCompareOpcode(int opcode) {
        return switch (opcode) {
            case Opcodes.IFEQ, Opcodes.IF_ICMPEQ, Opcodes.IF_ACMPEQ, Opcodes.IFNULL -> IrCompareOpcode.EQ;
            case Opcodes.IFNE, Opcodes.IF_ICMPNE, Opcodes.IF_ACMPNE, Opcodes.IFNONNULL -> IrCompareOpcode.NE;
            case Opcodes.IFLT, Opcodes.IF_ICMPLT -> IrCompareOpcode.LT;
            case Opcodes.IFGE, Opcodes.IF_ICMPGE -> IrCompareOpcode.GE;
            case Opcodes.IFGT, Opcodes.IF_ICMPGT -> IrCompareOpcode.GT;
            case Opcodes.IFLE, Opcodes.IF_ICMPLE -> IrCompareOpcode.LE;
            default -> throw new IllegalArgumentException("Unsupported conditional jump opcode: " + opcode);
        };
    }

    private String requiredBlockLabel(MethodNode methodNode, IdentityHashMap<AbstractInsnNode, String> blockLabels, AbstractInsnNode instruction) {
        if (instruction == null) {
            throw new UnsupportedBytecodeException("Missing block target in " + methodNode.name + methodNode.desc);
        }
        String blockLabel = blockLabels.get(instruction);
        if (blockLabel == null) {
            throw new UnsupportedBytecodeException("Target instruction is not assigned to a block in " + methodNode.name + methodNode.desc);
        }
        return blockLabel;
    }

    private int handleStaticFieldInstruction(MethodNode methodNode, List<IrInstruction> currentInstructions, Deque<IrValue> stack,
                                             FieldInsnNode fieldInsn, int nextValueId) {
        IrType fieldType = lowerSupportedValueType(methodNode, Type.getType(fieldInsn.desc), "field");
        IrFieldRef fieldRef = new IrFieldRef(
                new IrClassRef(fieldInsn.owner),
                fieldInsn.name,
                fieldType,
                fieldInsn.getOpcode() == Opcodes.GETSTATIC || fieldInsn.getOpcode() == Opcodes.PUTSTATIC
        );

        return switch (fieldInsn.getOpcode()) {
            case Opcodes.GETSTATIC -> {
                IrValue result = new IrValue(nextValueId, fieldType, "field");
                currentInstructions.add(new IrInstruction.LoadStaticField(result, fieldRef));
                stack.push(result);
                yield nextValueId + 1;
            }
            case Opcodes.PUTSTATIC -> {
                CoercedValue coercedValue = coerceForExpectedType(
                        currentInstructions,
                        popValueOfExpectedType(stack, methodNode, fieldType, "putstatic value"),
                        fieldType,
                        nextValueId
                );
                IrValue value = coercedValue.value();
                currentInstructions.add(new IrInstruction.StoreStaticField(fieldRef, value));
                yield coercedValue.nextValueId();
            }
            case Opcodes.GETFIELD -> {
                IrValue owner = popReferenceLike(stack, methodNode);
                IrValue result = new IrValue(nextValueId, fieldType, "field");
                currentInstructions.add(new IrInstruction.LoadField(result, fieldRef, owner));
                stack.push(result);
                yield nextValueId + 1;
            }
            case Opcodes.PUTFIELD -> {
                CoercedValue coercedValue = coerceForExpectedType(
                        currentInstructions,
                        popValueOfExpectedType(stack, methodNode, fieldType, "putfield value"),
                        fieldType,
                        nextValueId
                );
                IrValue value = coercedValue.value();
                IrValue owner = popReferenceLike(stack, methodNode);
                currentInstructions.add(new IrInstruction.StoreField(fieldRef, owner, value));
                yield coercedValue.nextValueId();
            }
            default -> throw unsupported(methodNode, fieldInsn, "only GETSTATIC/PUTSTATIC/GETFIELD/PUTFIELD are supported in the current slice");
        };
    }

    private int handleInvoke(MethodNode methodNode, List<IrInstruction> currentInstructions, Deque<IrValue> stack,
                             MethodInsnNode methodInsn, int nextValueId) {
        Type asmMethodType = Type.getMethodType(methodInsn.desc);
        Type[] asmArgumentTypes = asmMethodType.getArgumentTypes();
        ArrayList<IrType> parameterTypes = new ArrayList<>(asmArgumentTypes.length);
        ArrayList<IrValue> arguments = new ArrayList<>(asmArgumentTypes.length);

        for (int index = asmArgumentTypes.length - 1; index >= 0; index--) {
            IrType parameterType = lowerSupportedValueType(methodNode, asmArgumentTypes[index], "invoke parameter");
            parameterTypes.add(0, parameterType);
            CoercedValue coercedValue = coerceForExpectedType(
                    currentInstructions,
                    popValueOfExpectedType(stack, methodNode, parameterType, "invoke argument"),
                    parameterType,
                    nextValueId
            );
            nextValueId = coercedValue.nextValueId();
            arguments.add(0, coercedValue.value());
        }

        IrMethodRef.CallKind callKind = switch (methodInsn.getOpcode()) {
            case Opcodes.INVOKESTATIC -> IrMethodRef.CallKind.STATIC;
            case Opcodes.INVOKEVIRTUAL -> IrMethodRef.CallKind.VIRTUAL;
            case Opcodes.INVOKESPECIAL -> IrMethodRef.CallKind.SPECIAL;
            case Opcodes.INVOKEINTERFACE -> IrMethodRef.CallKind.INTERFACE;
            default -> throw unsupported(methodNode, methodInsn, "invoke opcode is not supported in the current slice");
        };

        if (callKind != IrMethodRef.CallKind.STATIC) {
            arguments.add(0, popReferenceLike(stack, methodNode));
        }

        IrType returnType = lowerInvokeReturnType(methodNode, asmMethodType.getReturnType(), callKind);
        IrMethodRef methodRef = new IrMethodRef(
                new IrClassRef(methodInsn.owner),
                methodInsn.name,
                returnType,
                parameterTypes,
                callKind
        );

        IrValue result = new IrValue(nextValueId, returnType, returnType == IrType.VOID ? "void" : "call");
        currentInstructions.add(new IrInstruction.Invoke(result, methodRef, arguments));
        if (returnType != IrType.VOID) {
            stack.push(result);
            return nextValueId + 1;
        }
        return nextValueId;
    }

    private int handleInvokeDynamic(String ownerInternalName, MethodNode methodNode, List<IrInstruction> currentInstructions, Deque<IrValue> stack,
                                    InvokeDynamicInsnNode indyInsn, int nextValueId) {
        Handle bootstrap = indyInsn.bsm;
        if ("java/lang/invoke/StringConcatFactory".equals(bootstrap.getOwner())
                && "makeConcatWithConstants".equals(bootstrap.getName())) {
            return handleStringConcatInvokeDynamic(methodNode, currentInstructions, stack, indyInsn, nextValueId);
        }
        if ("java/lang/invoke/LambdaMetafactory".equals(bootstrap.getOwner())
                && "metafactory".equals(bootstrap.getName())) {
            return handleLambdaMetafactoryInvokeDynamic(ownerInternalName, methodNode, currentInstructions, stack, indyInsn, nextValueId);
        }
        if ("java/lang/runtime/SwitchBootstraps".equals(bootstrap.getOwner())
                && ("typeSwitch".equals(bootstrap.getName()) || "enumSwitch".equals(bootstrap.getName()))) {
            return handleSwitchInvokeDynamic(methodNode, currentInstructions, stack, indyInsn, nextValueId);
        }
        if ("java/lang/runtime/ObjectMethods".equals(bootstrap.getOwner())
                && "bootstrap".equals(bootstrap.getName())) {
            return handleRecordObjectMethodInvokeDynamic(methodNode, currentInstructions, stack, indyInsn, nextValueId);
        }
        throw unsupported(methodNode, indyInsn, "invokedynamic lowering is not implemented yet");
    }

    private int handleStringConcatInvokeDynamic(MethodNode methodNode, List<IrInstruction> currentInstructions,
                                                Deque<IrValue> stack, InvokeDynamicInsnNode indyInsn, int nextValueId) {
        Type asmMethodType = Type.getMethodType(indyInsn.desc);
        Type[] asmArgumentTypes = asmMethodType.getArgumentTypes();
        ArrayList<IrType> parameterTypes = new ArrayList<>(asmArgumentTypes.length);
        ArrayList<IrValue> arguments = new ArrayList<>(asmArgumentTypes.length);

        for (int index = asmArgumentTypes.length - 1; index >= 0; index--) {
            IrType parameterType = lowerSupportedValueType(methodNode, asmArgumentTypes[index], "concat parameter");
            parameterTypes.add(0, parameterType);
            CoercedValue coercedValue = coerceForExpectedType(
                    currentInstructions,
                    popValueOfExpectedType(stack, methodNode, parameterType, "concat argument"),
                    parameterType,
                    nextValueId
            );
            nextValueId = coercedValue.nextValueId();
            arguments.add(0, coercedValue.value());
        }

        IrType returnType = lowerType(asmMethodType.getReturnType());
        if (!returnType.equals(IrType.reference("java/lang/String"))) {
            throw unsupported(methodNode, indyInsn, "only String concat invokedynamic results are supported");
        }
        if (indyInsn.bsmArgs.length == 0 || !(indyInsn.bsmArgs[0] instanceof String recipe)) {
            throw unsupported(methodNode, indyInsn, "string concat bootstrap is missing a recipe");
        }
        if (recipe.indexOf('\u0002') >= 0) {
            throw unsupported(methodNode, indyInsn, "string concat constants placeholders are not implemented yet");
        }
        if (countRecipeArguments(recipe) != parameterTypes.size()) {
            throw unsupported(methodNode, indyInsn, "string concat recipe argument count does not match callsite");
        }

        IrValue result = new IrValue(nextValueId, returnType, "concat");
        currentInstructions.add(new IrInstruction.CallHelper(
                result,
                concatHelperName(recipe, parameterTypes),
                List.copyOf(arguments)
        ));
        stack.push(result);
        return nextValueId + 1;
    }

    private int handleLambdaMetafactoryInvokeDynamic(String ownerInternalName, MethodNode methodNode, List<IrInstruction> currentInstructions,
                                                     Deque<IrValue> stack, InvokeDynamicInsnNode indyInsn, int nextValueId) {
        Type indyMethodType = Type.getMethodType(indyInsn.desc);
        if (!(indyInsn.bsmArgs.length >= 3
                && indyInsn.bsmArgs[0] instanceof Type samMethodType
                && indyInsn.bsmArgs[1] instanceof Handle implMethod
                && indyInsn.bsmArgs[2] instanceof Type instantiatedMethodType)) {
            throw unsupported(methodNode, indyInsn, "lambda metafactory is missing implementation handle");
        }

        IrType interfaceType = lowerType(indyMethodType.getReturnType());
        if (interfaceType.isPrimitive() || interfaceType == IrType.VOID) {
            throw unsupported(methodNode, indyInsn, "lambda metafactory return type must be reference-like");
        }

        ArrayList<IrType> captureTypes = new ArrayList<>();
        ArrayList<IrValue> captureArguments = new ArrayList<>();
        Type[] captureAsmTypes = indyMethodType.getArgumentTypes();
        for (int index = captureAsmTypes.length - 1; index >= 0; index--) {
            IrType captureType = lowerSupportedValueType(methodNode, captureAsmTypes[index], "lambda capture");
            captureTypes.add(0, captureType);
            CoercedValue coercedValue = coerceForExpectedType(
                    currentInstructions,
                    popValueOfExpectedType(stack, methodNode, captureType, "lambda capture"),
                    captureType,
                    nextValueId
            );
            nextValueId = coercedValue.nextValueId();
            captureArguments.add(0, coercedValue.value());
        }

        IrValue result = new IrValue(nextValueId, interfaceType, "lambda");
        currentInstructions.add(new IrInstruction.CallHelper(
                result,
                lambdaHelperName(ownerInternalName, interfaceType, indyInsn.name, samMethodType, implMethod, instantiatedMethodType, captureTypes),
                List.copyOf(captureArguments)
        ));
        stack.push(result);
        return nextValueId + 1;
    }

    private int handleSwitchInvokeDynamic(MethodNode methodNode, List<IrInstruction> currentInstructions,
                                          Deque<IrValue> stack, InvokeDynamicInsnNode indyInsn, int nextValueId) {
        Type indyMethodType = Type.getMethodType(indyInsn.desc);
        Type[] argumentTypes = indyMethodType.getArgumentTypes();
        if (argumentTypes.length != 2 || argumentTypes[1].getSort() != Type.INT) {
            throw unsupported(methodNode, indyInsn, indyInsn.bsm.getName()
                    + " currently expects (reference, int) arguments");
        }

        CoercedValue stateValue = popPromotedInt(currentInstructions, stack, methodNode, nextValueId);
        IrValue state = stateValue.value();
        nextValueId = stateValue.nextValueId();
        String switchKind = indyInsn.bsm.getName();
        IrType subjectType = lowerSupportedValueType(methodNode, argumentTypes[0], switchKind + " subject");
        IrValue subject = popValueOfExpectedType(stack, methodNode, subjectType, switchKind + " subject");

        IrValue result = new IrValue(nextValueId, IrType.INT, switchKind);
        currentInstructions.add(new IrInstruction.CallHelper(
                result,
                switchHelperName(methodNode, indyInsn, argumentTypes[0]),
                List.of(subject, state)
        ));
        stack.push(result);
        return nextValueId + 1;
    }

    private int handleRecordObjectMethodInvokeDynamic(MethodNode methodNode, List<IrInstruction> currentInstructions,
                                                      Deque<IrValue> stack, InvokeDynamicInsnNode indyInsn, int nextValueId) {
        if (!(indyInsn.bsmArgs.length >= 2
                && indyInsn.bsmArgs[0] instanceof Type recordType
                && indyInsn.bsmArgs[1] instanceof String)) {
            throw unsupported(methodNode, indyInsn, "record ObjectMethods bootstrap metadata is malformed");
        }

        Type indyMethodType = Type.getMethodType(indyInsn.desc);
        Type[] asmArgumentTypes = indyMethodType.getArgumentTypes();
        ArrayList<IrValue> arguments = new ArrayList<>(asmArgumentTypes.length);
        for (int index = asmArgumentTypes.length - 1; index >= 0; index--) {
            IrType parameterType = lowerSupportedValueType(methodNode, asmArgumentTypes[index], "record helper argument");
            arguments.add(0, popValueOfExpectedType(stack, methodNode, parameterType, "record helper argument"));
        }

        IrType returnType = lowerType(indyMethodType.getReturnType());
        IrValue result = returnType == IrType.VOID
                ? null
                : new IrValue(nextValueId, returnType, "record");
        currentInstructions.add(new IrInstruction.CallHelper(
                result,
                recordObjectMethodHelperName(recordType, indyInsn.name, indyInsn.bsmArgs),
                List.copyOf(arguments)
        ));
        if (result != null) {
            stack.push(result);
            return nextValueId + 1;
        }
        return nextValueId;
    }

    private int handleTypeInstruction(MethodNode methodNode, List<IrInstruction> currentInstructions, Deque<IrValue> stack,
                                      TypeInsnNode typeInsn, int nextValueId) {
        if (typeInsn.getOpcode() == Opcodes.NEW) {
            IrType resultType = IrType.reference(typeInsn.desc);
            IrValue result = new IrValue(nextValueId, resultType, "obj");
            currentInstructions.add(new IrInstruction.NewObject(result, new IrClassRef(typeInsn.desc)));
            stack.push(result);
            return nextValueId + 1;
        }
        if (typeInsn.getOpcode() == Opcodes.ANEWARRAY) {
            CoercedValue sizeValue = popPromotedInt(currentInstructions, stack, methodNode, nextValueId);
            IrValue size = sizeValue.value();
            nextValueId = sizeValue.nextValueId();
            IrType elementType = typeInsn.desc.startsWith("[")
                    ? lowerType(Type.getType(typeInsn.desc))
                    : IrType.reference(typeInsn.desc);
            IrType arrayType = IrType.array(elementType);
            IrValue result = new IrValue(nextValueId, arrayType, "arr");
            currentInstructions.add(new IrInstruction.CallHelper(
                    result,
                    arrayCreationHelperName(arrayType),
                    List.of(size)
            ));
            stack.push(result);
            return nextValueId + 1;
        }
        if (typeInsn.getOpcode() == Opcodes.CHECKCAST) {
            IrValue value = popReferenceLike(stack, methodNode);
            IrType targetType = typeInsn.desc.startsWith("[")
                    ? lowerType(Type.getType(typeInsn.desc))
                    : IrType.reference(typeInsn.desc);
            IrValue casted = new IrValue(nextValueId, targetType, "cast");
            currentInstructions.add(new IrInstruction.Convert(casted, value));
            stack.push(casted);
            return nextValueId + 1;
        }
        if (typeInsn.getOpcode() == Opcodes.INSTANCEOF) {
            IrValue value = popReferenceLike(stack, methodNode);
            IrType targetType = typeInsn.desc.startsWith("[")
                    ? lowerType(Type.getType(typeInsn.desc))
                    : IrType.reference(typeInsn.desc);
            IrValue result = new IrValue(nextValueId, IrType.BOOLEAN, "instanceof");
            currentInstructions.add(new IrInstruction.CallHelper(
                    result,
                    instanceOfHelperName(targetType),
                    List.of(value)
            ));
            stack.push(result);
            return nextValueId + 1;
        }
        throw unsupported(methodNode, typeInsn, "only NEW/ANEWARRAY/CHECKCAST/INSTANCEOF are supported in the current slice");
    }

    private IrMethod createMethod(MethodNode methodNode, List<IrBlock> blocks, int maxLocals) {
        Type methodType = Type.getMethodType(methodNode.desc);
        ArrayList<IrType> parameterTypes = new ArrayList<>();
        for (Type argumentType : methodType.getArgumentTypes()) {
            parameterTypes.add(lowerType(argumentType));
        }

        return new IrMethod(
                methodNode.name,
                lowerType(methodType.getReturnType()),
                parameterTypes,
                maxLocals,
                (methodNode.access & Opcodes.ACC_STATIC) != 0,
                (methodNode.access & Opcodes.ACC_PRIVATE) != 0,
                (methodNode.access & Opcodes.ACC_FINAL) != 0,
                "entry",
                blocks
        );
    }

    private IrBinaryOpcode lowerBinaryOpcode(int opcode) {
        return switch (opcode) {
            case Opcodes.IADD, Opcodes.LADD, Opcodes.FADD, Opcodes.DADD -> IrBinaryOpcode.ADD;
            case Opcodes.ISUB, Opcodes.LSUB, Opcodes.FSUB, Opcodes.DSUB -> IrBinaryOpcode.SUB;
            case Opcodes.IMUL, Opcodes.LMUL, Opcodes.FMUL, Opcodes.DMUL -> IrBinaryOpcode.MUL;
            case Opcodes.IDIV, Opcodes.LDIV, Opcodes.FDIV, Opcodes.DDIV -> IrBinaryOpcode.DIV;
            case Opcodes.IREM, Opcodes.LREM, Opcodes.FREM, Opcodes.DREM -> IrBinaryOpcode.REM;
            case Opcodes.IAND, Opcodes.LAND -> IrBinaryOpcode.AND;
            case Opcodes.IOR, Opcodes.LOR -> IrBinaryOpcode.OR;
            case Opcodes.IXOR, Opcodes.LXOR -> IrBinaryOpcode.XOR;
            case Opcodes.ISHL, Opcodes.LSHL -> IrBinaryOpcode.SHL;
            case Opcodes.ISHR, Opcodes.LSHR -> IrBinaryOpcode.SHR;
            case Opcodes.IUSHR, Opcodes.LUSHR -> IrBinaryOpcode.USHR;
            default -> throw new IllegalArgumentException("Unsupported binary opcode: " + opcode);
        };
    }

    private IrType lowerType(Type type) {
        return switch (type.getSort()) {
            case Type.VOID -> IrType.VOID;
            case Type.BOOLEAN -> IrType.BOOLEAN;
            case Type.BYTE -> IrType.BYTE;
            case Type.SHORT -> IrType.SHORT;
            case Type.CHAR -> IrType.CHAR;
            case Type.INT -> IrType.INT;
            case Type.LONG -> IrType.LONG;
            case Type.FLOAT -> IrType.FLOAT;
            case Type.DOUBLE -> IrType.DOUBLE;
            case Type.ARRAY -> lowerArrayType(type);
            case Type.OBJECT -> IrType.reference(type.getInternalName());
            default -> throw new IllegalArgumentException("Unsupported ASM type sort: " + type.getSort());
        };
    }

    private IrType lowerArrayType(Type type) {
        IrType lowered = lowerType(type.getElementType());
        for (int dimension = 0; dimension < type.getDimensions(); dimension++) {
            lowered = IrType.array(lowered);
        }
        return lowered;
    }

    private IrType lowerSupportedValueType(MethodNode methodNode, Type asmType, String usage) {
        IrType type = lowerType(asmType);
        if (type == IrType.VOID) {
            throw new UnsupportedBytecodeException("Void " + usage + " types are not supported in "
                    + methodNode.name + methodNode.desc + " but found " + type.displayName());
        }
        return type;
    }

    private IrType lowerInvokeReturnType(MethodNode methodNode, Type asmType, IrMethodRef.CallKind callKind) {
        return lowerType(asmType);
    }

    private IrValue popInt(Deque<IrValue> stack, MethodNode methodNode) {
        if (stack.isEmpty()) {
            throw new UnsupportedBytecodeException("Operand stack underflow in " + methodNode.name + methodNode.desc);
        }
        IrValue value = stack.pop();
        if (value.type() != IrType.INT) {
            throw new UnsupportedBytecodeException("Expected int on operand stack in " + methodNode.name
                    + methodNode.desc + " but found " + value.type().displayName());
        }
        return value;
    }

    private IrValue popIntLike(Deque<IrValue> stack, MethodNode methodNode) {
        if (stack.isEmpty()) {
            throw new UnsupportedBytecodeException("Operand stack underflow in " + methodNode.name + methodNode.desc);
        }
        IrValue value = stack.pop();
        if (!isIntLike(value.type())) {
            throw new UnsupportedBytecodeException("Expected int-like value on operand stack in " + methodNode.name
                    + methodNode.desc + " but found " + value.type().displayName());
        }
        return value;
    }

    private CoercedValue popPromotedInt(List<IrInstruction> instructions, Deque<IrValue> stack, MethodNode methodNode,
                                        int nextValueId) {
        IrValue value = popIntLike(stack, methodNode);
        if (value.type() == IrType.INT) {
            return new CoercedValue(value, nextValueId);
        }
        return coerceForExpectedType(instructions, value, IrType.INT, nextValueId);
    }

    private IrValue popReferenceLike(Deque<IrValue> stack, MethodNode methodNode) {
        if (stack.isEmpty()) {
            throw new UnsupportedBytecodeException("Operand stack underflow in " + methodNode.name + methodNode.desc);
        }
        IrValue value = stack.pop();
        if (value.type().isPrimitive() || value.type() == IrType.VOID) {
            throw new UnsupportedBytecodeException("Expected reference-like value on operand stack in "
                    + methodNode.name + methodNode.desc + " but found " + value.type().displayName());
        }
        return value;
    }

    private IrValue popConversionSource(Deque<IrValue> stack, MethodNode methodNode, int opcode) {
        return switch (opcode) {
            case Opcodes.I2L, Opcodes.I2F, Opcodes.I2D, Opcodes.I2B, Opcodes.I2C, Opcodes.I2S -> popIntLike(stack, methodNode);
            case Opcodes.L2I, Opcodes.L2F, Opcodes.L2D -> popExactType(stack, methodNode, IrType.LONG);
            case Opcodes.F2I, Opcodes.F2L, Opcodes.F2D -> popExactType(stack, methodNode, IrType.FLOAT);
            case Opcodes.D2I, Opcodes.D2L, Opcodes.D2F -> popExactType(stack, methodNode, IrType.DOUBLE);
            default -> throw new IllegalArgumentException("Unsupported conversion opcode: " + opcode);
        };
    }

    private IrType lowerConvertTargetType(int opcode) {
        return switch (opcode) {
            case Opcodes.I2L, Opcodes.F2L, Opcodes.D2L -> IrType.LONG;
            case Opcodes.I2F, Opcodes.L2F, Opcodes.D2F -> IrType.FLOAT;
            case Opcodes.I2D, Opcodes.L2D, Opcodes.F2D -> IrType.DOUBLE;
            case Opcodes.I2B -> IrType.BYTE;
            case Opcodes.I2C -> IrType.CHAR;
            case Opcodes.I2S -> IrType.SHORT;
            case Opcodes.L2I, Opcodes.F2I, Opcodes.D2I -> IrType.INT;
            default -> throw new IllegalArgumentException("Unsupported conversion opcode: " + opcode);
        };
    }

    private IrValue popValueOfExpectedType(Deque<IrValue> stack, MethodNode methodNode, IrType expectedType, String usage) {
        IrValue value = expectedType.isPrimitive()
                ? (isIntLike(expectedType) ? popIntLike(stack, methodNode) : popExactType(stack, methodNode, expectedType))
                : popReferenceLike(stack, methodNode);
        if (expectedType.isPrimitive()) {
            if (isIntLike(expectedType) && !isIntLike(value.type())) {
                throw new UnsupportedBytecodeException("Expected " + expectedType.displayName() + " for " + usage
                        + " in " + methodNode.name + methodNode.desc + " but found " + value.type().displayName());
            }
            if (!isIntLike(expectedType) && value.type() != expectedType) {
                throw new UnsupportedBytecodeException("Expected " + expectedType.displayName() + " for " + usage
                        + " in " + methodNode.name + methodNode.desc + " but found " + value.type().displayName());
            }
            return value;
        }
        return value;
    }

    private IrValue popExactType(Deque<IrValue> stack, MethodNode methodNode, IrType expectedType) {
        if (stack.isEmpty()) {
            throw new UnsupportedBytecodeException("Operand stack underflow in " + methodNode.name + methodNode.desc);
        }
        IrValue value = stack.pop();
        if (value.type() != expectedType) {
            throw new UnsupportedBytecodeException("Expected " + expectedType.displayName() + " on operand stack in "
                    + methodNode.name + methodNode.desc + " but found " + value.type().displayName());
        }
        return value;
    }

    private IrValue popArray(Deque<IrValue> stack, MethodNode methodNode) {
        IrValue value = popReferenceLike(stack, methodNode);
        if (value.type().kind() != IrType.Kind.ARRAY) {
            throw new UnsupportedBytecodeException("Expected array on operand stack in " + methodNode.name
                    + methodNode.desc + " but found " + value.type().displayName());
        }
        return value;
    }

    private IrType requireLocalType(MethodNode methodNode, Map<Integer, IrType> localTypes, int slot) {
        IrType type = localTypes.get(slot);
        if (type == null) {
            throw new UnsupportedBytecodeException("Local slot " + slot + " has unknown type in " + methodNode.name + methodNode.desc);
        }
        return type;
    }

    private Map<Integer, Integer> initializeLocalStorageSlots(Map<Integer, IrType> localTypes) {
        HashMap<Integer, Integer> storageSlots = new HashMap<>();
        for (Integer slot : localTypes.keySet()) {
            storageSlots.put(slot, slot);
        }
        return storageSlots;
    }

    private int requireLocalStorageSlot(MethodNode methodNode, Map<Integer, Integer> localStorageSlots, int bytecodeSlot) {
        Integer storageSlot = localStorageSlots.get(bytecodeSlot);
        if (storageSlot == null) {
            throw new UnsupportedBytecodeException("Local slot " + bytecodeSlot + " has no storage mapping in "
                    + methodNode.name + methodNode.desc);
        }
        return storageSlot;
    }

    private int allocateStorageSlotForStore(Map<Integer, Integer> localStorageSlots, Map<Integer, IrType> localTypes,
                                            int bytecodeSlot, IrType newType, int nextStorageSlot) {
        Integer currentStorageSlot = localStorageSlots.get(bytecodeSlot);
        IrType previousType = localTypes.get(bytecodeSlot);
        if (currentStorageSlot == null || previousType == null) {
            localStorageSlots.put(bytecodeSlot, bytecodeSlot);
            return bytecodeSlot;
        }
        if (llvmStorageCompatible(previousType, newType)) {
            return currentStorageSlot;
        }
        localStorageSlots.put(bytecodeSlot, nextStorageSlot);
        return nextStorageSlot;
    }

    private boolean llvmStorageCompatible(IrType left, IrType right) {
        return left.equals(right)
                || (!left.isPrimitive() && left != IrType.VOID && !right.isPrimitive() && right != IrType.VOID);
    }

    private void duplicateTopOfStack(MethodNode methodNode, Deque<IrValue> stack) {
        if (stack.isEmpty()) {
            throw new UnsupportedBytecodeException("Operand stack underflow in " + methodNode.name + methodNode.desc);
        }
        stack.push(stack.peek());
    }

    private void duplicateTopOfStackAndInsertBelowOne(MethodNode methodNode, Deque<IrValue> stack) {
        if (stack.size() < 2) {
            throw new UnsupportedBytecodeException("Operand stack underflow in " + methodNode.name + methodNode.desc);
        }
        IrValue top = stack.pop();
        IrValue below = stack.pop();
        if (top.type().isWide() || below.type().isWide()) {
            throw new UnsupportedBytecodeException("DUP_X1 currently only supports category-1 values in "
                    + methodNode.name + methodNode.desc);
        }
        stack.push(top);
        stack.push(below);
        stack.push(top);
    }

    private void duplicateTopOfStackAndInsertBelowTwo(MethodNode methodNode, Deque<IrValue> stack) {
        if (stack.size() < 2) {
            throw new UnsupportedBytecodeException("Operand stack underflow in " + methodNode.name + methodNode.desc);
        }
        IrValue top = stack.pop();
        if (top.type().isWide()) {
            throw new UnsupportedBytecodeException("DUP_X2 currently only supports category-1 top values in "
                    + methodNode.name + methodNode.desc);
        }
        IrValue second = stack.pop();
        if (second.type().isWide()) {
            stack.push(top);
            stack.push(second);
            stack.push(top);
            return;
        }
        if (stack.isEmpty()) {
            throw new UnsupportedBytecodeException("Operand stack underflow in " + methodNode.name + methodNode.desc);
        }
        IrValue third = stack.pop();
        if (third.type().isWide()) {
            throw new UnsupportedBytecodeException("DUP_X2 does not support category-2 third values in "
                    + methodNode.name + methodNode.desc);
        }
        stack.push(top);
        stack.push(third);
        stack.push(second);
        stack.push(top);
    }

    private void duplicateTopTwoCategorySlots(MethodNode methodNode, Deque<IrValue> stack) {
        if (stack.isEmpty()) {
            throw new UnsupportedBytecodeException("Operand stack underflow in " + methodNode.name + methodNode.desc);
        }
        IrValue top = stack.pop();
        if (top.type().isWide()) {
            stack.push(top);
            stack.push(top);
            return;
        }
        if (stack.isEmpty()) {
            throw new UnsupportedBytecodeException("Operand stack underflow in " + methodNode.name + methodNode.desc);
        }
        IrValue second = stack.pop();
        if (second.type().isWide()) {
            throw new UnsupportedBytecodeException("DUP2 cannot duplicate category-1 over category-2 values in "
                    + methodNode.name + methodNode.desc);
        }
        stack.push(second);
        stack.push(top);
        stack.push(second);
        stack.push(top);
    }

    private void duplicateTopTwoCategorySlotsAndInsertBelowOne(MethodNode methodNode, Deque<IrValue> stack) {
        if (stack.size() < 2) {
            throw new UnsupportedBytecodeException("Operand stack underflow in " + methodNode.name + methodNode.desc);
        }
        IrValue top = stack.pop();
        if (top.type().isWide()) {
            IrValue below = stack.pop();
            if (below.type().isWide()) {
                throw new UnsupportedBytecodeException("DUP2_X1 does not support category-2 insert targets in "
                        + methodNode.name + methodNode.desc);
            }
            stack.push(top);
            stack.push(below);
            stack.push(top);
            return;
        }
        IrValue second = stack.pop();
        if (second.type().isWide()) {
            throw new UnsupportedBytecodeException("DUP2_X1 requires the top two values to be category-1 in "
                    + methodNode.name + methodNode.desc);
        }
        if (stack.isEmpty()) {
            throw new UnsupportedBytecodeException("Operand stack underflow in " + methodNode.name + methodNode.desc);
        }
        IrValue third = stack.pop();
        if (third.type().isWide()) {
            throw new UnsupportedBytecodeException("DUP2_X1 does not support category-2 insert targets in "
                    + methodNode.name + methodNode.desc);
        }
        stack.push(second);
        stack.push(top);
        stack.push(third);
        stack.push(second);
        stack.push(top);
    }

    private void popTwoCategorySlots(MethodNode methodNode, Deque<IrValue> stack) {
        if (stack.isEmpty()) {
            throw new UnsupportedBytecodeException("Operand stack underflow in " + methodNode.name + methodNode.desc);
        }
        IrValue top = stack.pop();
        if (top.type().isWide()) {
            return;
        }
        if (stack.isEmpty()) {
            throw new UnsupportedBytecodeException("Operand stack underflow in " + methodNode.name + methodNode.desc);
        }
        IrValue second = stack.pop();
        if (second.type().isWide()) {
            throw new UnsupportedBytecodeException("POP2 cannot consume category-1 over category-2 values in "
                    + methodNode.name + methodNode.desc);
        }
    }

    private int handleNegation(MethodNode methodNode, List<IrInstruction> currentInstructions, Deque<IrValue> stack,
                               IrType operandType, int nextValueId) {
        IrValue value = operandType == IrType.INT ? popIntLike(stack, methodNode) : popValueOfExpectedType(stack, methodNode, operandType, "negation");
        if (value.type() != operandType) {
            CoercedValue coercedValue = coerceForExpectedType(currentInstructions, value, operandType, nextValueId);
            value = coercedValue.value();
            nextValueId = coercedValue.nextValueId();
        }
        IrValue zero;
        if (operandType == IrType.INT) {
            zero = emitConst(currentInstructions, 0, nextValueId++);
        } else if (operandType == IrType.LONG) {
            zero = emitLongConst(currentInstructions, 0L, nextValueId++);
        } else if (operandType == IrType.FLOAT) {
            zero = emitFloatConst(currentInstructions, 0.0f, nextValueId++);
        } else if (operandType == IrType.DOUBLE) {
            zero = emitDoubleConst(currentInstructions, 0.0d, nextValueId++);
        } else {
            throw new IllegalArgumentException("Unsupported negation type: " + operandType.displayName());
        }
        IrValue result = new IrValue(nextValueId++, operandType, "tmp");
        currentInstructions.add(new IrInstruction.Binary(result, IrBinaryOpcode.SUB, zero, value));
        stack.push(result);
        return nextValueId;
    }

    private Map<Integer, IrType> initializeLocalTypes(String ownerInternalName, MethodNode methodNode) {
        HashMap<Integer, IrType> localTypes = new HashMap<>();
        int slot = 0;
        if ((methodNode.access & Opcodes.ACC_STATIC) == 0) {
            if (ownerInternalName == null || ownerInternalName.isBlank()) {
                throw new UnsupportedBytecodeException("Instance method " + methodNode.name + methodNode.desc
                        + " requires owner metadata for local typing");
            }
            localTypes.put(slot++, IrType.reference(ownerInternalName));
        }

        for (Type argumentType : Type.getArgumentTypes(methodNode.desc)) {
            IrType irType = lowerType(argumentType);
            localTypes.put(slot, irType);
            slot += slotWidth(irType);
        }
        return localTypes;
    }

    private int slotWidth(IrType type) {
        return type.isWide() ? 2 : 1;
    }

    private void ensureEmptyStackAtBoundary(MethodNode methodNode, Deque<IrValue> stack, String boundary) {
        if (!stack.isEmpty()) {
            throw new UnsupportedBytecodeException("Operand stack must be empty at " + boundary + " in "
                    + methodNode.name + methodNode.desc + " but found " + stack.size() + " value(s)");
        }
    }

    private void ensureStackShape(MethodNode methodNode, Deque<IrValue> stack, int expectedSize, String opcode) {
        if (stack.size() != expectedSize) {
            throw new UnsupportedBytecodeException("Expected stack size " + expectedSize + " before " + opcode
                    + " in " + methodNode.name + methodNode.desc + " but found " + stack.size());
        }
    }

    private UnsupportedBytecodeException unsupported(MethodNode methodNode, AbstractInsnNode instruction, String detail) {
        return new UnsupportedBytecodeException("Unsupported opcode " + opcodeName(instruction.getOpcode())
                + " in " + methodNode.name + methodNode.desc + ": " + detail);
    }

    private int handleNewPrimitiveArray(MethodNode methodNode, List<IrInstruction> currentInstructions, Deque<IrValue> stack,
                                        IntInsnNode intInsn, int nextValueId) {
        CoercedValue sizeValue = popPromotedInt(currentInstructions, stack, methodNode, nextValueId);
        IrValue size = sizeValue.value();
        nextValueId = sizeValue.nextValueId();
        IrType elementType = switch (intInsn.operand) {
            case Opcodes.T_BOOLEAN -> IrType.BOOLEAN;
            case Opcodes.T_BYTE -> IrType.BYTE;
            case Opcodes.T_CHAR -> IrType.CHAR;
            case Opcodes.T_SHORT -> IrType.SHORT;
            case Opcodes.T_INT -> IrType.INT;
            case Opcodes.T_LONG -> IrType.LONG;
            case Opcodes.T_FLOAT -> IrType.FLOAT;
            case Opcodes.T_DOUBLE -> IrType.DOUBLE;
            default -> throw unsupported(methodNode, intInsn, "unsupported primitive array element type");
        };
        IrType arrayType = IrType.array(elementType);
        IrValue result = new IrValue(nextValueId, arrayType, "arr");
        currentInstructions.add(new IrInstruction.CallHelper(
                result,
                arrayCreationHelperName(arrayType),
                List.of(size)
        ));
        stack.push(result);
        return nextValueId + 1;
    }

    private int handleMultiNewArray(MethodNode methodNode, List<IrInstruction> currentInstructions, Deque<IrValue> stack,
                                    MultiANewArrayInsnNode multiArrayInsn, int nextValueId) {
        IrType arrayType = lowerType(Type.getType(multiArrayInsn.desc));
        ArrayList<IrValue> dimensions = new ArrayList<>(multiArrayInsn.dims);
        for (int index = 0; index < multiArrayInsn.dims; index++) {
            CoercedValue dimValue = popPromotedInt(currentInstructions, stack, methodNode, nextValueId);
            dimensions.add(0, dimValue.value());
            nextValueId = dimValue.nextValueId();
        }
        IrValue result = new IrValue(nextValueId, arrayType, "arr");
        currentInstructions.add(new IrInstruction.CallHelper(
                result,
                multiArrayCreationHelperName(arrayType),
                List.copyOf(dimensions)
        ));
        stack.push(result);
        return nextValueId + 1;
    }

    private int handleArrayLoad(MethodNode methodNode, List<IrInstruction> currentInstructions, Deque<IrValue> stack,
                                IrType expectedElementType, IrType resultType, int nextValueId) {
        CoercedValue indexValue = popPromotedInt(currentInstructions, stack, methodNode, nextValueId);
        IrValue index = indexValue.value();
        nextValueId = indexValue.nextValueId();
        IrValue array = popArray(stack, methodNode);
        IrType elementType = arrayElementType(array.type());
        if (!matchesArrayOpcodeElementType(expectedElementType, elementType)) {
            throw new UnsupportedBytecodeException("Expected " + expectedElementType.displayName() + "[] for array load in "
                    + methodNode.name + methodNode.desc + " but found " + array.type().displayName());
        }
        IrValue result = new IrValue(nextValueId, resultType, "elem");
        currentInstructions.add(new IrInstruction.CallHelper(
                result,
                arrayLoadHelperName(array.type()),
                List.of(array, index)
        ));
        stack.push(result);
        return nextValueId + 1;
    }

    private int handleReferenceArrayLoad(MethodNode methodNode, List<IrInstruction> currentInstructions, Deque<IrValue> stack,
                                         int nextValueId) {
        CoercedValue indexValue = popPromotedInt(currentInstructions, stack, methodNode, nextValueId);
        IrValue index = indexValue.value();
        nextValueId = indexValue.nextValueId();
        IrValue array = popArray(stack, methodNode);
        IrType elementType = arrayElementType(array.type());
        if (elementType.isPrimitive()) {
            throw new UnsupportedBytecodeException("AALOAD requires reference-like element type in "
                    + methodNode.name + methodNode.desc + " but found " + elementType.displayName());
        }
        IrValue result = new IrValue(nextValueId, elementType, "elem");
        currentInstructions.add(new IrInstruction.CallHelper(
                result,
                arrayLoadHelperName(array.type()),
                List.of(array, index)
        ));
        stack.push(result);
        return nextValueId + 1;
    }

    private int handleArrayStore(MethodNode methodNode, List<IrInstruction> currentInstructions, Deque<IrValue> stack,
                                 IrType expectedElementType, int nextValueId) {
        IrValue value = expectedElementType == IrType.BOOLEAN
                || expectedElementType == IrType.BYTE
                || expectedElementType == IrType.SHORT
                || expectedElementType == IrType.CHAR
                ? popIntLike(stack, methodNode)
                : popValueOfExpectedType(stack, methodNode, expectedElementType, "array store");
        if (value.type() != expectedElementType) {
            CoercedValue coercedValue = coerceForExpectedType(currentInstructions, value, expectedElementType, nextValueId);
            value = coercedValue.value();
            nextValueId = coercedValue.nextValueId();
        }
        CoercedValue indexValue = popPromotedInt(currentInstructions, stack, methodNode, nextValueId);
        IrValue index = indexValue.value();
        nextValueId = indexValue.nextValueId();
        IrValue array = popArray(stack, methodNode);
        IrType elementType = arrayElementType(array.type());
        if (!matchesArrayOpcodeElementType(expectedElementType, elementType)) {
            throw new UnsupportedBytecodeException("Expected " + expectedElementType.displayName() + "[] for array store in "
                    + methodNode.name + methodNode.desc + " but found " + array.type().displayName());
        }
        currentInstructions.add(new IrInstruction.CallHelperVoid(
                arrayStoreHelperName(array.type()),
                List.of(array, index, value)
        ));
        return nextValueId;
    }

    private int handleReferenceArrayStore(MethodNode methodNode, List<IrInstruction> currentInstructions, Deque<IrValue> stack,
                                          int nextValueId) {
        IrValue value = popReferenceLike(stack, methodNode);
        CoercedValue indexValue = popPromotedInt(currentInstructions, stack, methodNode, nextValueId);
        IrValue index = indexValue.value();
        nextValueId = indexValue.nextValueId();
        IrValue array = popArray(stack, methodNode);
        IrType elementType = arrayElementType(array.type());
        if (elementType.isPrimitive()) {
            throw new UnsupportedBytecodeException("AASTORE requires reference-like element type in "
                    + methodNode.name + methodNode.desc + " but found " + elementType.displayName());
        }
        currentInstructions.add(new IrInstruction.CallHelperVoid(
                arrayStoreHelperName(array.type()),
                List.of(array, index, value)
        ));
        return nextValueId;
    }

    private IrTerminator.Switch lowerLookupSwitch(MethodNode methodNode, IdentityHashMap<AbstractInsnNode, String> blockLabels,
                                                  LookupSwitchInsnNode switchInsn, IrValue selector) {
        HashMap<Integer, String> targetByKey = new HashMap<>();
        for (int index = 0; index < switchInsn.keys.size(); index++) {
            targetByKey.put(
                    switchInsn.keys.get(index),
                    requiredBlockLabel(methodNode, blockLabels, nextExecutable(switchInsn.labels.get(index)))
            );
        }
        return new IrTerminator.Switch(
                selector,
                targetByKey,
                requiredBlockLabel(methodNode, blockLabels, nextExecutable(switchInsn.dflt))
        );
    }

    private IrTerminator.Switch lowerTableSwitch(MethodNode methodNode, IdentityHashMap<AbstractInsnNode, String> blockLabels,
                                                 TableSwitchInsnNode switchInsn, IrValue selector) {
        HashMap<Integer, String> targetByKey = new HashMap<>();
        for (int index = 0; index < switchInsn.labels.size(); index++) {
            targetByKey.put(
                    switchInsn.min + index,
                    requiredBlockLabel(methodNode, blockLabels, nextExecutable(switchInsn.labels.get(index)))
            );
        }
        return new IrTerminator.Switch(
                selector,
                targetByKey,
                requiredBlockLabel(methodNode, blockLabels, nextExecutable(switchInsn.dflt))
        );
    }

    private List<String> switchTargets(IrTerminator.Switch switchTerminator) {
        ArrayList<String> targets = new ArrayList<>(switchTerminator.targetByKey().size() + 1);
        targets.add(switchTerminator.defaultTarget());
        targets.addAll(switchTerminator.targetByKey().values());
        return List.copyOf(targets);
    }

    private boolean isIntLike(IrType type) {
        return type == IrType.BOOLEAN
                || type == IrType.BYTE
                || type == IrType.SHORT
                || type == IrType.CHAR
                || type == IrType.INT;
    }

    private boolean isReferenceLike(IrType type) {
        return !type.isPrimitive() && type != IrType.VOID;
    }

    private boolean isNumericPrimitive(IrType type) {
        return type.isPrimitive() && type != IrType.BOOLEAN;
    }

    private boolean matchesArrayOpcodeElementType(IrType opcodeElementType, IrType actualElementType) {
        if (opcodeElementType.equals(actualElementType)) {
            return true;
        }
        return opcodeElementType == IrType.BYTE && actualElementType == IrType.BOOLEAN;
    }

    private IrType arrayElementType(IrType arrayType) {
        if (arrayType.kind() != IrType.Kind.ARRAY || !arrayType.displayName().endsWith("[]")) {
            throw new IllegalArgumentException("Not an array type: " + arrayType.displayName());
        }
        String elementDisplayName = arrayType.displayName().substring(0, arrayType.displayName().length() - 2);
        return switch (elementDisplayName) {
            case "boolean" -> IrType.BOOLEAN;
            case "byte" -> IrType.BYTE;
            case "short" -> IrType.SHORT;
            case "char" -> IrType.CHAR;
            case "int" -> IrType.INT;
            case "long" -> IrType.LONG;
            case "float" -> IrType.FLOAT;
            case "double" -> IrType.DOUBLE;
            default -> {
                if (elementDisplayName.endsWith("[]")) {
                    yield IrType.array(arrayElementType(new IrType(IrType.Kind.ARRAY, elementDisplayName)));
                }
                yield IrType.reference(elementDisplayName);
            }
        };
    }

    private String stringConstantHelperName(String value) {
        StringBuilder builder = new StringBuilder("ir_rt_ldc_string__");
        for (byte current : value.getBytes(java.nio.charset.StandardCharsets.UTF_8)) {
            builder.append(String.format("%02x", current & 0xff));
        }
        return builder.toString();
    }

    private String classConstantHelperName(Type type) {
        IrType loweredType = lowerType(type);
        return "ir_rt_ldc_class__" + encodeHelperToken(loweredType.displayName());
    }

    private String arrayCreationHelperName(IrType arrayType) {
        return "ir_rt_new_array__" + encodeHelperToken(arrayType.displayName());
    }

    private String multiArrayCreationHelperName(IrType arrayType) {
        return "ir_rt_multi_new_array__" + encodeHelperToken(arrayType.displayName());
    }

    private String arrayLoadHelperName(IrType arrayType) {
        return "ir_rt_array_load__" + encodeHelperToken(arrayType.displayName());
    }

    private String arrayStoreHelperName(IrType arrayType) {
        return "ir_rt_array_store__" + encodeHelperToken(arrayType.displayName());
    }

    private String compareHelperName(int opcode) {
        return switch (opcode) {
            case Opcodes.LCMP -> "ir_rt_lcmp";
            case Opcodes.FCMPL -> "ir_rt_fcmpl";
            case Opcodes.FCMPG -> "ir_rt_fcmpg";
            case Opcodes.DCMPL -> "ir_rt_dcmpl";
            case Opcodes.DCMPG -> "ir_rt_dcmpg";
            default -> throw new IllegalArgumentException("Unsupported compare helper opcode: " + opcode);
        };
    }

    private String concatHelperName(String recipe, List<IrType> parameterTypes) {
        StringBuilder builder = new StringBuilder("ir_rt_concat__");
        for (byte current : recipe.getBytes(java.nio.charset.StandardCharsets.UTF_8)) {
            builder.append(String.format("%02x", current & 0xff));
        }
        for (IrType parameterType : parameterTypes) {
            builder.append("__").append(encodeHelperToken(parameterType.displayName()));
        }
        return builder.toString();
    }

    private int countRecipeArguments(String recipe) {
        int count = 0;
        for (int index = 0; index < recipe.length(); index++) {
            if (recipe.charAt(index) == '\u0001') {
                count++;
            }
        }
        return count;
    }

    private String lambdaHelperName(String ownerInternalName,
                                    IrType interfaceType,
                                    String samMethodName,
                                    Type samMethodType,
                                    Handle implMethod,
                                    Type instantiatedMethodType,
                                    List<IrType> captureTypes) {
        String callerOwner = ownerInternalName == null || ownerInternalName.isBlank()
                ? implMethod.getOwner()
                : ownerInternalName;
        StringBuilder builder = new StringBuilder("ir_rt_lambda__");
        builder.append(encodeHexUtf8(interfaceType.displayName()));
        builder.append("__").append(encodeHexUtf8(samMethodName));
        builder.append("__").append(encodeHexUtf8(samMethodType.getDescriptor()));
        builder.append("__").append(encodeHexUtf8(callerOwner));
        builder.append("__").append(encodeHexUtf8(implMethod.getOwner()));
        builder.append("__").append(encodeHexUtf8(implMethod.getName()));
        builder.append("__").append(encodeHexUtf8(implMethod.getDesc()));
        builder.append("__").append(encodeHexUtf8(instantiatedMethodType.getDescriptor()));
        builder.append("__").append(encodeHexUtf8(lambdaInvokeKindToken(implMethod)));
        for (IrType captureType : captureTypes) {
            builder.append("__").append(encodeHexUtf8(captureType.displayName()));
        }
        return builder.toString();
    }

    private String lambdaInvokeKindToken(Handle implMethod) {
        return switch (implMethod.getTag()) {
            case Opcodes.H_INVOKESTATIC -> "static";
            case Opcodes.H_INVOKEVIRTUAL -> "virtual";
            case Opcodes.H_INVOKEINTERFACE -> "interface";
            case Opcodes.H_INVOKESPECIAL -> "special";
            case Opcodes.H_NEWINVOKESPECIAL -> "constructor";
            default -> throw new IllegalArgumentException("Unsupported lambda implementation handle tag: " + implMethod.getTag());
        };
    }

    private String instanceOfHelperName(IrType targetType) {
        return "ir_rt_instanceof__" + encodeHelperToken(targetType.displayName());
    }

    private String switchHelperName(MethodNode methodNode, InvokeDynamicInsnNode indyInsn, Type subjectAsmType) {
        if ("enumSwitch".equals(indyInsn.bsm.getName())) {
            return enumSwitchHelperName(methodNode, indyInsn, subjectAsmType);
        }
        StringBuilder builder = new StringBuilder("ir_rt_type_switch");
        int caseCount = 0;
        for (Object bootstrapArgument : indyInsn.bsmArgs) {
            builder.append("__").append(encodeHelperToken(resolveTypeSwitchCaseToken(methodNode, indyInsn, bootstrapArgument)));
            caseCount++;
        }
        if (caseCount == 0) {
            throw unsupported(methodNode, indyInsn, "typeSwitch bootstrap is missing case literals");
        }
        return builder.toString();
    }

    private String enumSwitchHelperName(MethodNode methodNode, InvokeDynamicInsnNode indyInsn, Type subjectAsmType) {
        if (subjectAsmType.getSort() != Type.OBJECT) {
            throw unsupported(methodNode, indyInsn, "enumSwitch subject must be an enum reference type");
        }
        String ownerInternalName = subjectAsmType.getInternalName();
        StringBuilder builder = new StringBuilder("ir_rt_type_switch");
        int caseCount = 0;
        for (Object bootstrapArgument : indyInsn.bsmArgs) {
            if (!(bootstrapArgument instanceof String constantName)) {
                throw unsupported(methodNode, indyInsn, "enumSwitch bootstrap arguments must be enum constant names");
            }
            builder.append("__").append(encodeHelperToken("enum:" + ownerInternalName + ":" + constantName));
            caseCount++;
        }
        if (caseCount == 0) {
            throw unsupported(methodNode, indyInsn, "enumSwitch bootstrap is missing enum constants");
        }
        return builder.toString();
    }

    private String resolveTypeSwitchCaseToken(MethodNode methodNode, InvokeDynamicInsnNode indyInsn, Object bootstrapArgument) {
        if (bootstrapArgument instanceof Type caseType) {
            if (caseType.getSort() != Type.OBJECT && caseType.getSort() != Type.ARRAY) {
                throw unsupported(methodNode, indyInsn, "typeSwitch case literals must be reference-like");
            }
            return lowerType(caseType).displayName();
        }
        if (bootstrapArgument instanceof ConstantDynamic constantDynamic) {
            return resolveTypeSwitchDynamicCase(methodNode, indyInsn, constantDynamic);
        }
        throw unsupported(methodNode, indyInsn, "typeSwitch bootstrap arguments must be class literals or enum descriptors");
    }

    private String resolveTypeSwitchDynamicCase(MethodNode methodNode, InvokeDynamicInsnNode indyInsn, ConstantDynamic dynamic) {
        if (!"Ljava/lang/Enum$EnumDesc;".equals(dynamic.getDescriptor())) {
            throw unsupported(methodNode, indyInsn, "typeSwitch dynamic cases currently support only enum descriptors");
        }
        Handle bootstrap = dynamic.getBootstrapMethod();
        if (!"java/lang/invoke/ConstantBootstraps".equals(bootstrap.getOwner())
                || !"invoke".equals(bootstrap.getName())
                || dynamic.getBootstrapMethodArgumentCount() < 3
                || !(dynamic.getBootstrapMethodArgument(0) instanceof Handle enumFactory)
                || !"java/lang/Enum$EnumDesc".equals(enumFactory.getOwner())
                || !"of".equals(enumFactory.getName())
                || !(dynamic.getBootstrapMethodArgument(2) instanceof String constantName)) {
            throw unsupported(methodNode, indyInsn, "unsupported enum descriptor bootstrap in typeSwitch");
        }
        String ownerInternalName = resolveEnumClassDescToken(methodNode, indyInsn, dynamic.getBootstrapMethodArgument(1));
        return "enum:" + ownerInternalName + ":" + constantName;
    }

    private String resolveEnumClassDescToken(MethodNode methodNode, InvokeDynamicInsnNode indyInsn, Object descriptorToken) {
        if (descriptorToken instanceof Type type) {
            if (type.getSort() != Type.OBJECT) {
                throw unsupported(methodNode, indyInsn, "enum typeSwitch descriptors must reference enum classes");
            }
            return type.getInternalName();
        }
        if (!(descriptorToken instanceof ConstantDynamic constantDynamic)) {
            throw unsupported(methodNode, indyInsn, "enum typeSwitch descriptors must be class descriptors");
        }
        Handle bootstrap = constantDynamic.getBootstrapMethod();
        if (!"Ljava/lang/constant/ClassDesc;".equals(constantDynamic.getDescriptor())
                || !"java/lang/invoke/ConstantBootstraps".equals(bootstrap.getOwner())
                || !"invoke".equals(bootstrap.getName())
                || constantDynamic.getBootstrapMethodArgumentCount() < 2
                || !(constantDynamic.getBootstrapMethodArgument(0) instanceof Handle classFactory)
                || !"java/lang/constant/ClassDesc".equals(classFactory.getOwner())
                || !"of".equals(classFactory.getName())
                || !(constantDynamic.getBootstrapMethodArgument(1) instanceof String className)) {
            throw unsupported(methodNode, indyInsn, "unsupported class descriptor bootstrap in enum typeSwitch");
        }
        return className.replace('.', '/');
    }

    private String recordObjectMethodHelperName(Type recordType, String operation, Object[] bootstrapArguments) {
        StringBuilder builder = new StringBuilder("ir_rt_record__");
        builder.append(encodeHelperToken(recordType.getInternalName()));
        builder.append("__").append(encodeHelperToken(operation));
        String componentLabels = bootstrapArguments.length >= 2 && bootstrapArguments[1] instanceof String labels
                ? labels
                : "";
        builder.append("__").append(encodeHexUtf8(componentLabels));
        for (int index = 2; index < bootstrapArguments.length; index++) {
            if (!(bootstrapArguments[index] instanceof Handle componentHandle)) {
                continue;
            }
            builder.append("__").append(encodeHelperToken(componentHandle.getName()));
            builder.append("__").append(encodeHelperToken(lowerType(Type.getType(componentHandle.getDesc())).displayName()));
        }
        return builder.toString();
    }

    private String encodeHelperToken(String value) {
        return encodeHexUtf8(value);
    }

    private String encodeHexUtf8(String value) {
        StringBuilder builder = new StringBuilder(value.length() * 2);
        for (byte current : value.getBytes(java.nio.charset.StandardCharsets.UTF_8)) {
            builder.append(String.format("%02x", current & 0xff));
        }
        return builder.toString();
    }

    private CoercedValue coerceForExpectedType(List<IrInstruction> instructions, IrValue value, IrType expectedType, int nextValueId) {
        if (value.type().equals(expectedType)) {
            return new CoercedValue(value, nextValueId);
        }
        if (!expectedType.isPrimitive()) {
            if (value.type().isPrimitive() || value.type() == IrType.VOID) {
                throw new UnsupportedBytecodeException("Cannot coerce " + value.type().displayName()
                        + " to " + expectedType.displayName());
            }
            IrValue converted = new IrValue(nextValueId, expectedType, "cast");
            instructions.add(new IrInstruction.Convert(converted, value));
            return new CoercedValue(converted, nextValueId + 1);
        }
        if (!(isIntLike(value.type()) && isIntLike(expectedType))
                && !(isNumericPrimitive(value.type()) && isNumericPrimitive(expectedType))) {
            throw new UnsupportedBytecodeException("Cannot coerce " + value.type().displayName()
                    + " to " + expectedType.displayName());
        }
        IrValue converted = new IrValue(nextValueId, expectedType, "conv");
        instructions.add(new IrInstruction.Convert(converted, value));
        return new CoercedValue(converted, nextValueId + 1);
    }

    private record ConditionEmission(IrValue condition, int consumedIds) {
    }

    private record ProtectedExceptionEdgeResult(int nextStorageSlot, int nextValueId, boolean terminatedBlock) {
    }

    private record ExceptionHandlerEdge(String targetBlock, IrType catchType, boolean isBroad) {
    }

    private record CoercedValue(IrValue value, int nextValueId) {
    }

    private String opcodeName(int opcode) {
        return switch (opcode) {
            case -1 -> "pseudo";
            case Opcodes.NOP -> "NOP";
            case Opcodes.ICONST_M1 -> "ICONST_M1";
            case Opcodes.ICONST_0 -> "ICONST_0";
            case Opcodes.ICONST_1 -> "ICONST_1";
            case Opcodes.ICONST_2 -> "ICONST_2";
            case Opcodes.ICONST_3 -> "ICONST_3";
            case Opcodes.ICONST_4 -> "ICONST_4";
            case Opcodes.ICONST_5 -> "ICONST_5";
            case Opcodes.BIPUSH -> "BIPUSH";
            case Opcodes.SIPUSH -> "SIPUSH";
            case Opcodes.ILOAD -> "ILOAD";
            case Opcodes.LLOAD -> "LLOAD";
            case Opcodes.FLOAD -> "FLOAD";
            case Opcodes.DLOAD -> "DLOAD";
            case Opcodes.ALOAD -> "ALOAD";
            case Opcodes.ISTORE -> "ISTORE";
            case Opcodes.LSTORE -> "LSTORE";
            case Opcodes.FSTORE -> "FSTORE";
            case Opcodes.DSTORE -> "DSTORE";
            case Opcodes.ASTORE -> "ASTORE";
            case Opcodes.DUP -> "DUP";
            case Opcodes.DUP_X1 -> "DUP_X1";
            case Opcodes.POP -> "POP";
            case Opcodes.POP2 -> "POP2";
            case Opcodes.GETSTATIC -> "GETSTATIC";
            case Opcodes.PUTSTATIC -> "PUTSTATIC";
            case Opcodes.GETFIELD -> "GETFIELD";
            case Opcodes.PUTFIELD -> "PUTFIELD";
            case Opcodes.NEW -> "NEW";
            case Opcodes.ANEWARRAY -> "ANEWARRAY";
            case Opcodes.CHECKCAST -> "CHECKCAST";
            case Opcodes.INVOKESTATIC -> "INVOKESTATIC";
            case Opcodes.INVOKEVIRTUAL -> "INVOKEVIRTUAL";
            case Opcodes.INVOKESPECIAL -> "INVOKESPECIAL";
            case Opcodes.INVOKEINTERFACE -> "INVOKEINTERFACE";
            case Opcodes.ARETURN -> "ARETURN";
            case Opcodes.ATHROW -> "ATHROW";
            case Opcodes.IALOAD -> "IALOAD";
            case Opcodes.BALOAD -> "BALOAD";
            case Opcodes.CALOAD -> "CALOAD";
            case Opcodes.SALOAD -> "SALOAD";
            case Opcodes.LALOAD -> "LALOAD";
            case Opcodes.FALOAD -> "FALOAD";
            case Opcodes.DALOAD -> "DALOAD";
            case Opcodes.AALOAD -> "AALOAD";
            case Opcodes.IASTORE -> "IASTORE";
            case Opcodes.BASTORE -> "BASTORE";
            case Opcodes.CASTORE -> "CASTORE";
            case Opcodes.SASTORE -> "SASTORE";
            case Opcodes.LASTORE -> "LASTORE";
            case Opcodes.FASTORE -> "FASTORE";
            case Opcodes.DASTORE -> "DASTORE";
            case Opcodes.AASTORE -> "AASTORE";
            case Opcodes.ARRAYLENGTH -> "ARRAYLENGTH";
            case Opcodes.IINC -> "IINC";
            case Opcodes.IADD -> "IADD";
            case Opcodes.ISUB -> "ISUB";
            case Opcodes.IMUL -> "IMUL";
            case Opcodes.IDIV -> "IDIV";
            case Opcodes.IREM -> "IREM";
            case Opcodes.INEG -> "INEG";
            case Opcodes.IFEQ -> "IFEQ";
            case Opcodes.IFNE -> "IFNE";
            case Opcodes.IFLT -> "IFLT";
            case Opcodes.IFGE -> "IFGE";
            case Opcodes.IFGT -> "IFGT";
            case Opcodes.IFLE -> "IFLE";
            case Opcodes.IF_ICMPEQ -> "IF_ICMPEQ";
            case Opcodes.IF_ICMPNE -> "IF_ICMPNE";
            case Opcodes.IF_ICMPLT -> "IF_ICMPLT";
            case Opcodes.IF_ICMPGE -> "IF_ICMPGE";
            case Opcodes.IF_ICMPGT -> "IF_ICMPGT";
            case Opcodes.IF_ICMPLE -> "IF_ICMPLE";
            case Opcodes.IF_ACMPEQ -> "IF_ACMPEQ";
            case Opcodes.IF_ACMPNE -> "IF_ACMPNE";
            case Opcodes.IFNULL -> "IFNULL";
            case Opcodes.IFNONNULL -> "IFNONNULL";
            case Opcodes.GOTO -> "GOTO";
            case Opcodes.LCONST_0 -> "LCONST_0";
            case Opcodes.LCONST_1 -> "LCONST_1";
            case Opcodes.LNEG -> "LNEG";
            case Opcodes.FCONST_0 -> "FCONST_0";
            case Opcodes.FCONST_1 -> "FCONST_1";
            case Opcodes.FCONST_2 -> "FCONST_2";
            case Opcodes.FNEG -> "FNEG";
            case Opcodes.DCONST_0 -> "DCONST_0";
            case Opcodes.DCONST_1 -> "DCONST_1";
            case Opcodes.DNEG -> "DNEG";
            case Opcodes.INSTANCEOF -> "INSTANCEOF";
            case Opcodes.MONITORENTER -> "MONITORENTER";
            case Opcodes.MONITOREXIT -> "MONITOREXIT";
            case Opcodes.IRETURN -> "IRETURN";
            case Opcodes.LRETURN -> "LRETURN";
            case Opcodes.FRETURN -> "FRETURN";
            case Opcodes.DRETURN -> "DRETURN";
            case Opcodes.RETURN -> "RETURN";
            default -> "opcode#" + opcode;
        };
    }
}
