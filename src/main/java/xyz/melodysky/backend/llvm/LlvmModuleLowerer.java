package xyz.melodysky.backend.llvm;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import xyz.melodysky.backend.llvm.model.LlvmBasicBlock;
import xyz.melodysky.backend.llvm.model.LlvmDeclaration;
import xyz.melodysky.backend.llvm.model.LlvmFunction;
import xyz.melodysky.backend.llvm.model.LlvmInstruction;
import xyz.melodysky.backend.llvm.model.LlvmLinkage;
import xyz.melodysky.backend.llvm.model.LlvmModule;
import xyz.melodysky.backend.llvm.model.LlvmParameter;
import xyz.melodysky.backend.llvm.model.LlvmSwitchCase;
import xyz.melodysky.backend.llvm.model.LlvmTerminator;
import xyz.melodysky.backend.llvm.model.LlvmType;
import xyz.melodysky.backend.llvm.model.LlvmVisibility;
import xyz.melodysky.ir.model.IrBlock;
import xyz.melodysky.ir.model.IrClass;
import xyz.melodysky.ir.model.IrMethod;
import xyz.melodysky.ir.model.IrOpcode;
import xyz.melodysky.ir.model.IrTerminatorKind;
import xyz.melodysky.ir.model.IrValue;
import xyz.melodysky.runtime.ClassIdentityToken;
import xyz.melodysky.runtime.FieldIdentityToken;
import xyz.melodysky.runtime.MethodIdentityToken;
import xyz.melodysky.runtime.RuntimeHelperCatalog;
import xyz.melodysky.runtime.RuntimeHelperKind;

public final class LlvmModuleLowerer {
    private final LlvmNameMangler nameMangler;
    private final LlvmTypeLowerer typeLowerer = new LlvmTypeLowerer();
    private final RuntimeHelperCatalog runtimeHelpers = RuntimeHelperCatalog.defaultCatalog();

    public LlvmModuleLowerer() {
        this(new LlvmNameMangler());
    }

    public LlvmModuleLowerer(LlvmNameMangler nameMangler) {
        this.nameMangler = nameMangler;
    }

    public LlvmModule lowerClass(IrClass irClass) {
        return lowerClass(irClass, LlvmLinkage.EXTERNAL, LlvmVisibility.HIDDEN);
    }

    public LlvmModule lowerClass(IrClass irClass, LlvmLinkage linkage, LlvmVisibility visibility) {
        return lowerClass(irClass, linkage, visibility, Set.of());
    }

    public LlvmModule lowerClass(
            IrClass irClass,
            LlvmLinkage linkage,
            LlvmVisibility visibility,
            Set<String> directCallMethodKeys) {
        return new LlvmModule(
                irClass.internalName(),
                runtimeHelperDeclarations(),
                irClass.methods().stream()
                        .map(method -> lowerMethod(method, linkage, visibility, directCallMethodKeys))
                        .toList());
    }

    private List<LlvmDeclaration> runtimeHelperDeclarations() {
        return runtimeHelpers.helpers().stream()
                .map(helper -> new LlvmDeclaration(
                        helper.llvmSymbol(),
                        helper.llvmReturnType(),
                        helper.llvmParameterTypes(),
                        helper.name()))
                .toList();
    }

    private LlvmFunction lowerMethod(
            IrMethod method,
            LlvmLinkage linkage,
            LlvmVisibility visibility,
            Set<String> directCallMethodKeys) {
        ArrayList<LlvmParameter> parameters = new ArrayList<>();
        if (methodNeedsJniEnv(method)) {
            parameters.add(new LlvmParameter(LlvmType.PTR, "%j2ll_env"));
        }
        if (methodNeedsOwnerClass(method)) {
            parameters.add(new LlvmParameter(LlvmType.PTR, "%j2ll_owner"));
        }
        method.parameters().stream()
                .map(parameter -> new LlvmParameter(typeLowerer.lower(parameter.type()), parameter.name()))
                .forEach(parameters::add);
        Map<String, List<PhiIncoming>> phiIncoming = phiIncoming(method);
        List<LlvmBasicBlock> blocks = method.blocks().stream()
                .map(block -> lowerBlock(
                        block,
                        phiIncoming.getOrDefault(block.name(), List.of()),
                        directCallMethodKeys))
                .toList();
        return new LlvmFunction(
                nameMangler.functionName(method),
                linkage,
                visibility,
                typeLowerer.lower(method.returnType()),
                parameters,
                blocks);
    }

    private LlvmBasicBlock lowerBlock(
            IrBlock block,
            List<PhiIncoming> phiIncoming,
            Set<String> directCallMethodKeys) {
        ArrayList<LlvmInstruction> instructions = new ArrayList<>();
        for (int index = 0; index < block.parameters().size(); index++) {
            IrValue parameter = block.parameters().get(index);
            ArrayList<String> incoming = new ArrayList<>();
            for (PhiIncoming predecessor : phiIncoming) {
                incoming.add("[ " + predecessor.arguments().get(index).name()
                        + ", %" + predecessor.predecessorBlock() + " ]");
            }
            instructions.add(LlvmInstruction.raw(
                    Optional.of(parameter.name()),
                    "phi " + typeLowerer.lower(parameter.type()).text() + " "
                            + String.join(", ", incoming)));
        }
        for (xyz.melodysky.ir.model.IrInstruction instruction : block.instructions()) {
            instructions.add(lowerInstruction(instruction, directCallMethodKeys));
        }
        LlvmTerminator terminator = lowerTerminator(block);
        return new LlvmBasicBlock(
                block.name(),
                instructions,
                terminator);
    }

    private Map<String, List<PhiIncoming>> phiIncoming(IrMethod method) {
        HashMap<String, ArrayList<PhiIncoming>> incoming = new HashMap<>();
        for (IrBlock block : method.blocks()) {
            switch (block.terminator().kind()) {
                case GOTO -> addPhiIncoming(
                        incoming,
                        block.terminator().target().orElseThrow(),
                        block.name(),
                        block.terminator().targetArguments());
                case BRANCH -> {
                    addPhiIncoming(
                            incoming,
                            block.terminator().trueTarget().orElseThrow(),
                            block.name(),
                            block.terminator().trueTargetArguments());
                    addPhiIncoming(
                            incoming,
                            block.terminator().falseTarget().orElseThrow(),
                            block.name(),
                            block.terminator().falseTargetArguments());
                }
                case SWITCH -> {
                    addPhiIncoming(
                            incoming,
                            block.terminator().defaultTarget().orElseThrow(),
                            block.name(),
                            block.terminator().defaultTargetArguments());
                    for (var switchCase : block.terminator().switchCases()) {
                        addPhiIncoming(incoming, switchCase.target(), block.name(), switchCase.arguments());
                    }
                }
                case RETURN, THROW -> {
                }
            }
        }
        return incoming.entrySet().stream()
                .collect(java.util.stream.Collectors.toUnmodifiableMap(
                        Map.Entry::getKey,
                        entry -> List.copyOf(entry.getValue())));
    }

    private void addPhiIncoming(
            Map<String, ArrayList<PhiIncoming>> incoming,
            String target,
            String predecessor,
            List<IrValue> arguments) {
        if (!arguments.isEmpty()) {
            incoming.computeIfAbsent(target, ignored -> new ArrayList<>())
                    .add(new PhiIncoming(predecessor, arguments));
        }
    }

    private LlvmTerminator lowerTerminator(IrBlock block) {
        if (block.terminator().kind() == IrTerminatorKind.GOTO) {
            return LlvmTerminator.gotoBlock(block.terminator().target().orElseThrow());
        }
        if (block.terminator().kind() == IrTerminatorKind.BRANCH) {
            return LlvmTerminator.branch(
                    block.terminator().condition().orElseThrow().name(),
                    block.terminator().trueTarget().orElseThrow(),
                    block.terminator().falseTarget().orElseThrow());
        }
        if (block.terminator().kind() == IrTerminatorKind.SWITCH) {
            return LlvmTerminator.switchOn(
                    block.terminator().switchValue().orElseThrow().name(),
                    block.terminator().defaultTarget().orElseThrow(),
                    block.terminator().switchCases().stream()
                            .map(switchCase -> new LlvmSwitchCase(switchCase.key(), switchCase.target()))
                            .toList());
        }
        if (block.terminator().kind() == IrTerminatorKind.THROW) {
            return LlvmTerminator.throwValue(block.terminator().value().orElseThrow().name());
        }
        LlvmType returnType = block.terminator().value()
                .map(value -> typeLowerer.lower(value.type()))
                .orElse(LlvmType.VOID);
        return new LlvmTerminator(returnType, block.terminator().value().map(IrValue::name));
    }

    private LlvmInstruction lowerInstruction(
            xyz.melodysky.ir.model.IrInstruction instruction,
            Set<String> directCallMethodKeys) {
        if (isConversion(instruction.opcode())) {
            return lowerConversion(instruction);
        }
        if (instruction.opcode() == IrOpcode.CONST_NULL) {
            return LlvmInstruction.raw(
                    Optional.of(instruction.result().orElseThrow().name()),
                    "inttoptr i64 0 to ptr");
        }
        if (isSymbolicConstant(instruction.opcode())) {
            return lowerSymbolicConstant(instruction);
        }
        if (isPrimitiveCompare(instruction.opcode())) {
            return LlvmInstruction.raw(
                    Optional.of(instruction.result().orElseThrow().name()),
                    "icmp " + comparePredicate(instruction.opcode()) + " i32 "
                            + instruction.operands().get(0).name() + ", "
                            + instruction.operands().get(1).name());
        }
        if (isReferenceCompare(instruction.opcode())) {
            return LlvmInstruction.raw(
                    Optional.of(instruction.result().orElseThrow().name()),
                    "icmp " + comparePredicate(instruction.opcode()) + " ptr "
                            + instruction.operands().get(0).name() + ", "
                            + instruction.operands().get(1).name());
        }
        if (isJvmComparisonHelper(instruction.opcode())) {
            return lowerHelperCall(instruction, helperName(instruction.opcode()));
        }
        if (isArithmeticExceptionHelper(instruction.opcode())) {
            return lowerEnvBackedHelperCall(instruction, helperName(instruction.opcode()));
        }
        if (isMemoryFence(instruction.opcode())) {
            return lowerMemoryFence(instruction);
        }
        if (isArrayHelper(instruction)) {
            return lowerEnvBackedHelperCall(instruction, arrayHelperName(instruction));
        }
        if (isAllocationHelper(instruction)) {
            return lowerAllocationHelper(instruction);
        }
        if (isTypeHelper(instruction)) {
            return lowerTypeHelper(instruction);
        }
        if (isRuntimeModelHelper(instruction.opcode())) {
            return lowerRuntimeModelHelper(instruction);
        }
        if (isFieldAccess(instruction.opcode())) {
            return lowerFieldAccess(instruction);
        }
        if (isCall(instruction.opcode())) {
            return lowerCall(instruction, directCallMethodKeys);
        }
        String opcode = switch (instruction.opcode()) {
            case CONST_INT -> "add";
            case CONST_LONG -> "add";
            case CONST_FLOAT -> "fadd";
            case CONST_DOUBLE -> "fadd";
            case CONST_STRING, CONST_CLASS, CONST_METHOD_TYPE, CONST_METHOD_HANDLE ->
                    throw new IllegalStateException("handled earlier");
            case CLASS_OBJECT, CLASS_INIT_GUARD, CLASS_INIT_BEGIN, CLASS_INIT_END, CLASS_INIT_FAILED ->
                    throw new IllegalStateException("handled earlier");
            case CLASS_INIT_HAPPENS_BEFORE -> throw new IllegalStateException("handled earlier");
            case ADD_I32 -> "add";
            case SUB_I32 -> "sub";
            case MUL_I32 -> "mul";
            case DIV_I32, REM_I32 -> throw new IllegalStateException("handled earlier");
            case NEG_I32 -> "sub";
            case SHL_I32 -> "shl";
            case SHR_I32 -> "ashr";
            case USHR_I32 -> "lshr";
            case AND_I32 -> "and";
            case OR_I32 -> "or";
            case XOR_I32 -> "xor";
            case CMP_EQ_I32, CMP_NE_I32, CMP_LT_I32, CMP_LE_I32, CMP_GT_I32, CMP_GE_I32,
                    CMP_EQ_REF, CMP_NE_REF ->
                    throw new IllegalStateException("handled earlier");
            case ADD_I64 -> "add";
            case SUB_I64 -> "sub";
            case MUL_I64 -> "mul";
            case DIV_I64, REM_I64 -> throw new IllegalStateException("handled earlier");
            case NEG_I64 -> "sub";
            case SHL_I64 -> "shl";
            case SHR_I64 -> "ashr";
            case USHR_I64 -> "lshr";
            case AND_I64 -> "and";
            case OR_I64 -> "or";
            case XOR_I64 -> "xor";
            case ADD_F32, ADD_F64 -> "fadd";
            case SUB_F32, SUB_F64 -> "fsub";
            case MUL_F32, MUL_F64 -> "fmul";
            case DIV_F32, DIV_F64 -> "fdiv";
            case REM_F32, REM_F64 -> "frem";
            case NEG_F32, NEG_F64 -> "fsub";
            case LCMP, FCMPL, FCMPG, DCMPL, DCMPG -> throw new IllegalStateException("handled earlier");
            case CONST_NULL, I2L, I2F, I2D, I2B, I2C, I2S, L2I, L2F, L2D,
                    F2I, F2L, F2D, D2I, D2L, D2F -> throw new IllegalStateException("handled earlier");
            case NEW_OBJECT, NEW_ARRAY, NEW_MULTI_ARRAY, ARRAY_LENGTH,
                    ARRAY_LOAD_I32, ARRAY_LOAD_I64, ARRAY_LOAD_F32, ARRAY_LOAD_F64, ARRAY_LOAD_REF,
                    ARRAY_STORE_I32, ARRAY_STORE_I64, ARRAY_STORE_F32, ARRAY_STORE_F64, ARRAY_STORE_REF,
                    CHECKCAST, INSTANCEOF -> throw new IllegalStateException("handled earlier");
            case GET_STATIC, PUT_STATIC, GET_FIELD, PUT_FIELD,
                    CALL_STATIC, CALL_SPECIAL, CALL_VIRTUAL, CALL_INTERFACE, CALL_DYNAMIC, CALL_RUNTIME_HELPER ->
                    throw new IllegalStateException("handled earlier");
            case MONITOR_ENTER, MONITOR_EXIT, MONITOR_EXIT_ON_EXCEPTION -> throw new IllegalStateException("handled earlier");
            case VOLATILE_READ_BARRIER, VOLATILE_WRITE_BARRIER, FINAL_FIELD_PUBLICATION,
                    MONITOR_HAPPENS_BEFORE, THREAD_START_HAPPENS_BEFORE, THREAD_JOIN_HAPPENS_BEFORE ->
                    throw new IllegalStateException("handled earlier");
        };
        List<String> operands = operands(instruction);
        return new LlvmInstruction(
                Optional.of(instruction.result().orElseThrow().name()),
                typeLowerer.lower(instruction.result().orElseThrow().type()),
                opcode,
                operands);
    }

    private List<String> operands(xyz.melodysky.ir.model.IrInstruction instruction) {
        return switch (instruction.opcode()) {
            case CONST_INT -> List.of("0", Integer.toString(instruction.intLiteral().orElseThrow()));
            case CONST_LONG -> List.of("0", Long.toString(instruction.longLiteral().orElseThrow()));
            case CONST_FLOAT -> List.of("0.0", Float.toString(instruction.floatLiteral().orElseThrow()));
            case CONST_DOUBLE -> List.of("0.0", Double.toString(instruction.doubleLiteral().orElseThrow()));
            case NEG_I32, NEG_I64 -> List.of("0", instruction.operands().get(0).name());
            case NEG_F32, NEG_F64 -> List.of("-0.0", instruction.operands().get(0).name());
            default -> instruction.operands().stream().map(IrValue::name).toList();
        };
    }

    private boolean isPrimitiveCompare(IrOpcode opcode) {
        return opcode == IrOpcode.CMP_EQ_I32
                || opcode == IrOpcode.CMP_NE_I32
                || opcode == IrOpcode.CMP_LT_I32
                || opcode == IrOpcode.CMP_LE_I32
                || opcode == IrOpcode.CMP_GT_I32
                || opcode == IrOpcode.CMP_GE_I32;
    }

    private boolean isReferenceCompare(IrOpcode opcode) {
        return opcode == IrOpcode.CMP_EQ_REF || opcode == IrOpcode.CMP_NE_REF;
    }

    private boolean isJvmComparisonHelper(IrOpcode opcode) {
        return opcode == IrOpcode.LCMP
                || opcode == IrOpcode.FCMPL
                || opcode == IrOpcode.FCMPG
                || opcode == IrOpcode.DCMPL
                || opcode == IrOpcode.DCMPG;
    }

    private boolean isArithmeticExceptionHelper(IrOpcode opcode) {
        return opcode == IrOpcode.DIV_I32
                || opcode == IrOpcode.REM_I32
                || opcode == IrOpcode.DIV_I64
                || opcode == IrOpcode.REM_I64;
    }

    private boolean isArrayHelper(xyz.melodysky.ir.model.IrInstruction instruction) {
        if (instruction.opcode() == IrOpcode.ARRAY_LENGTH
                || instruction.opcode() == IrOpcode.ARRAY_LOAD_REF
                || instruction.opcode() == IrOpcode.ARRAY_STORE_REF
                || instruction.opcode() == IrOpcode.ARRAY_LOAD_I64
                || instruction.opcode() == IrOpcode.ARRAY_STORE_I64
                || instruction.opcode() == IrOpcode.ARRAY_LOAD_F32
                || instruction.opcode() == IrOpcode.ARRAY_STORE_F32
                || instruction.opcode() == IrOpcode.ARRAY_LOAD_F64
                || instruction.opcode() == IrOpcode.ARRAY_STORE_F64) {
            return true;
        }
        if (instruction.opcode() != IrOpcode.ARRAY_LOAD_I32
                && instruction.opcode() != IrOpcode.ARRAY_STORE_I32) {
            return false;
        }
        return instruction.symbol()
                .map(symbol -> symbol.equals("int")
                        || symbol.equals("byteOrBoolean")
                        || symbol.equals("short")
                        || symbol.equals("char"))
                .orElse(false);
    }

    private boolean isAllocationHelper(xyz.melodysky.ir.model.IrInstruction instruction) {
        if (instruction.opcode() == IrOpcode.NEW_OBJECT) {
            return true;
        }
        if (instruction.opcode() != IrOpcode.NEW_ARRAY) {
            return false;
        }
        return instruction.symbol()
                .map(symbol -> symbol.startsWith("primitiveArray:") || symbol.startsWith("referenceArray:"))
                .orElse(false);
    }

    private boolean isSymbolicConstant(IrOpcode opcode) {
        return opcode == IrOpcode.CONST_STRING
                || opcode == IrOpcode.CONST_CLASS
                || opcode == IrOpcode.CONST_METHOD_TYPE
                || opcode == IrOpcode.CONST_METHOD_HANDLE;
    }

    private boolean isConversion(IrOpcode opcode) {
        return opcode == IrOpcode.I2L
                || opcode == IrOpcode.I2F
                || opcode == IrOpcode.I2D
                || opcode == IrOpcode.I2B
                || opcode == IrOpcode.I2C
                || opcode == IrOpcode.I2S
                || opcode == IrOpcode.L2I
                || opcode == IrOpcode.L2F
                || opcode == IrOpcode.L2D
                || opcode == IrOpcode.F2I
                || opcode == IrOpcode.F2L
                || opcode == IrOpcode.F2D
                || opcode == IrOpcode.D2I
                || opcode == IrOpcode.D2L
                || opcode == IrOpcode.D2F;
    }

    private boolean isRuntimeModelHelper(IrOpcode opcode) {
        return opcode == IrOpcode.CLASS_OBJECT
                || opcode == IrOpcode.CLASS_INIT_GUARD
                || opcode == IrOpcode.CLASS_INIT_BEGIN
                || opcode == IrOpcode.CLASS_INIT_END
                || opcode == IrOpcode.CLASS_INIT_FAILED
                || opcode == IrOpcode.NEW_MULTI_ARRAY
                || opcode == IrOpcode.ARRAY_LENGTH
                || opcode == IrOpcode.ARRAY_LOAD_I32
                || opcode == IrOpcode.ARRAY_LOAD_I64
                || opcode == IrOpcode.ARRAY_LOAD_F32
                || opcode == IrOpcode.ARRAY_LOAD_F64
                || opcode == IrOpcode.ARRAY_LOAD_REF
                || opcode == IrOpcode.ARRAY_STORE_I32
                || opcode == IrOpcode.ARRAY_STORE_I64
                || opcode == IrOpcode.ARRAY_STORE_F32
                || opcode == IrOpcode.ARRAY_STORE_F64
                || opcode == IrOpcode.ARRAY_STORE_REF
                || opcode == IrOpcode.CHECKCAST
                || opcode == IrOpcode.INSTANCEOF
                || opcode == IrOpcode.MONITOR_ENTER
                || opcode == IrOpcode.MONITOR_EXIT
                || opcode == IrOpcode.MONITOR_EXIT_ON_EXCEPTION
                || opcode == IrOpcode.THREAD_START_HAPPENS_BEFORE
                || opcode == IrOpcode.THREAD_JOIN_HAPPENS_BEFORE;
    }

    private boolean isMemoryFence(IrOpcode opcode) {
        return opcode == IrOpcode.VOLATILE_READ_BARRIER
                || opcode == IrOpcode.VOLATILE_WRITE_BARRIER
                || opcode == IrOpcode.FINAL_FIELD_PUBLICATION
                || opcode == IrOpcode.MONITOR_HAPPENS_BEFORE
                || opcode == IrOpcode.CLASS_INIT_HAPPENS_BEFORE;
    }

    private boolean isMonitorHelper(IrOpcode opcode) {
        return opcode == IrOpcode.MONITOR_ENTER
                || opcode == IrOpcode.MONITOR_EXIT
                || opcode == IrOpcode.MONITOR_EXIT_ON_EXCEPTION;
    }

    private boolean isClassInitHelper(IrOpcode opcode) {
        return opcode == IrOpcode.CLASS_OBJECT
                || opcode == IrOpcode.CLASS_INIT_GUARD
                || opcode == IrOpcode.CLASS_INIT_BEGIN
                || opcode == IrOpcode.CLASS_INIT_END
                || opcode == IrOpcode.CLASS_INIT_FAILED;
    }

    private boolean isEnvBackedRuntimeModelHelper(IrOpcode opcode) {
        return isMonitorHelper(opcode) || isClassInitHelper(opcode);
    }

    private boolean isCall(IrOpcode opcode) {
        return opcode == IrOpcode.CALL_STATIC
                || opcode == IrOpcode.CALL_SPECIAL
                || opcode == IrOpcode.CALL_VIRTUAL
                || opcode == IrOpcode.CALL_INTERFACE
                || opcode == IrOpcode.CALL_DYNAMIC
                || opcode == IrOpcode.CALL_RUNTIME_HELPER;
    }

    private boolean isFieldAccess(IrOpcode opcode) {
        return opcode == IrOpcode.GET_STATIC
                || opcode == IrOpcode.PUT_STATIC
                || opcode == IrOpcode.GET_FIELD
                || opcode == IrOpcode.PUT_FIELD;
    }

    private LlvmInstruction lowerFieldAccess(xyz.melodysky.ir.model.IrInstruction instruction) {
        String helper = fieldHelper(instruction);
        String tokenOperand = "i64 " + FieldIdentityToken.token(instruction.symbol().orElseThrow());
        if (instruction.opcode() == IrOpcode.GET_STATIC) {
            String type = typeLowerer.lower(instruction.result().orElseThrow().type()).text();
            return LlvmInstruction.raw(
                    Optional.of(instruction.result().orElseThrow().name()),
                    "call " + type + " @" + helper + "(ptr %j2ll_env, ptr %j2ll_owner, " + tokenOperand + ")");
        }
        if (instruction.opcode() == IrOpcode.PUT_STATIC) {
            IrValue value = instruction.operands().get(0);
            return LlvmInstruction.raw(
                    Optional.empty(),
                    "call void @" + helper + "(ptr %j2ll_env, ptr %j2ll_owner, " + tokenOperand
                            + ", " + typedOperand(value) + ")");
        }
        if (instruction.opcode() == IrOpcode.GET_FIELD) {
            String type = typeLowerer.lower(instruction.result().orElseThrow().type()).text();
            return LlvmInstruction.raw(
                    Optional.of(instruction.result().orElseThrow().name()),
                    "call " + type + " @" + helper + "(ptr %j2ll_env, "
                            + typedOperand(instruction.operands().get(0)) + ", " + tokenOperand + ")");
        }
        IrValue receiver = instruction.operands().get(0);
        IrValue value = instruction.operands().get(1);
        return LlvmInstruction.raw(
                Optional.empty(),
                "call void @" + helper + "(ptr %j2ll_env, "
                        + typedOperand(receiver) + ", " + tokenOperand + ", " + typedOperand(value) + ")");
    }

    private LlvmInstruction lowerCall(
            xyz.melodysky.ir.model.IrInstruction instruction,
            Set<String> directCallMethodKeys) {
        if ((instruction.opcode() == IrOpcode.CALL_STATIC || isDirectSpecialCallInstruction(instruction))
                && instruction.symbol().filter(directCallMethodKeys::contains).isPresent()) {
            String target = nameMangler.functionName(instruction.symbol().orElseThrow());
            String args = typedOperands(instruction.operands());
            if (instruction.result().isPresent()) {
                String type = typeLowerer.lower(instruction.result().orElseThrow().type()).text();
                return LlvmInstruction.raw(
                        Optional.of(instruction.result().orElseThrow().name()),
                        "call " + type + " @" + target + "(" + args + ")");
            }
            return LlvmInstruction.raw(Optional.empty(), "call void @" + target + "(" + args + ")");
        }
        if (isConstructorCallHelperInstruction(instruction)) {
            String token = "i64 " + MethodIdentityToken.token(instruction.symbol().orElseThrow());
            if (instruction.symbol().orElseThrow().endsWith("!()V")) {
                return LlvmInstruction.raw(
                        Optional.empty(),
                        "call void @j2ll_rt_call_constructor_void(ptr %j2ll_env, "
                                + typedOperand(instruction.operands().get(0)) + ", " + token + ")");
            }
            return LlvmInstruction.raw(
                    Optional.empty(),
                    "call void @j2ll_rt_call_constructor_void_i32_i32(ptr %j2ll_env, "
                            + typedOperand(instruction.operands().get(0)) + ", " + token + ", "
                            + typedOperand(instruction.operands().get(1)) + ", "
                            + typedOperand(instruction.operands().get(2)) + ")");
        }
        if (isDispatchHelperInstruction(instruction)) {
            String helper = instruction.opcode() == IrOpcode.CALL_INTERFACE
                    ? "j2ll_rt_call_interface_i32"
                    : "j2ll_rt_call_virtual_i32";
            String receiver = typedOperand(instruction.operands().get(0));
            String token = "i64 " + MethodIdentityToken.token(instruction.symbol().orElseThrow());
            return LlvmInstruction.raw(
                    Optional.of(instruction.result().orElseThrow().name()),
                    "call i32 @" + helper + "(ptr %j2ll_env, " + receiver + ", " + token + ", ptr null)");
        }
        String symbol = instruction.opcode() == IrOpcode.CALL_DYNAMIC
                ? stableHash(instruction.symbol().orElseThrow())
                : safeSymbol(instruction.symbol().orElseThrow());
        if (instruction.opcode() == IrOpcode.CALL_RUNTIME_HELPER) {
            symbol = runtimeHelperBaseSymbol(instruction.symbol().orElseThrow());
        }
        if (instruction.opcode() == IrOpcode.CALL_RUNTIME_HELPER
                && isEnvBackedRuntimeHelperSymbol(symbol)) {
            return lowerEnvBackedRuntimeCall(instruction, symbol);
        }
        String args = typedOperands(instruction.operands());
        String prefix = callPrefix(instruction.opcode());
        if (instruction.result().isPresent()) {
            String type = typeLowerer.lower(instruction.result().orElseThrow().type()).text();
            return LlvmInstruction.raw(
                    Optional.of(instruction.result().orElseThrow().name()),
                    "call " + type + " @" + prefix + symbol + "(" + args + ")");
        }
        return LlvmInstruction.raw(Optional.empty(), "call void @" + prefix + symbol + "(" + args + ")");
    }

    private LlvmInstruction lowerAllocationHelper(xyz.melodysky.ir.model.IrInstruction instruction) {
        String symbol = instruction.symbol().orElseThrow();
        if (instruction.opcode() == IrOpcode.NEW_OBJECT) {
            long token = ClassIdentityToken.token(allocationClassIdentity(symbol));
            return LlvmInstruction.raw(
                    Optional.of(instruction.result().orElseThrow().name()),
                    "call ptr @j2ll_rt_alloc_object(ptr %j2ll_env, i64 " + token + ")");
        }
        if (symbol.equals("primitiveArray:byte") || symbol.equals("primitiveArray:boolean")) {
            return LlvmInstruction.raw(
                    Optional.of(instruction.result().orElseThrow().name()),
                    "call ptr @j2ll_rt_new_byte_array(ptr %j2ll_env, " + typedOperand(instruction.operands().get(0)) + ")");
        }
        if (symbol.equals("primitiveArray:short")) {
            return LlvmInstruction.raw(
                    Optional.of(instruction.result().orElseThrow().name()),
                    "call ptr @j2ll_rt_new_short_array(ptr %j2ll_env, " + typedOperand(instruction.operands().get(0)) + ")");
        }
        if (symbol.equals("primitiveArray:char")) {
            return LlvmInstruction.raw(
                    Optional.of(instruction.result().orElseThrow().name()),
                    "call ptr @j2ll_rt_new_char_array(ptr %j2ll_env, " + typedOperand(instruction.operands().get(0)) + ")");
        }
        if (symbol.equals("primitiveArray:int")) {
            return LlvmInstruction.raw(
                    Optional.of(instruction.result().orElseThrow().name()),
                    "call ptr @j2ll_rt_new_int_array(ptr %j2ll_env, " + typedOperand(instruction.operands().get(0)) + ")");
        }
        if (symbol.equals("primitiveArray:long")) {
            return LlvmInstruction.raw(
                    Optional.of(instruction.result().orElseThrow().name()),
                    "call ptr @j2ll_rt_new_long_array(ptr %j2ll_env, " + typedOperand(instruction.operands().get(0)) + ")");
        }
        if (symbol.equals("primitiveArray:float")) {
            return LlvmInstruction.raw(
                    Optional.of(instruction.result().orElseThrow().name()),
                    "call ptr @j2ll_rt_new_float_array(ptr %j2ll_env, " + typedOperand(instruction.operands().get(0)) + ")");
        }
        if (symbol.equals("primitiveArray:double")) {
            return LlvmInstruction.raw(
                    Optional.of(instruction.result().orElseThrow().name()),
                    "call ptr @j2ll_rt_new_double_array(ptr %j2ll_env, " + typedOperand(instruction.operands().get(0)) + ")");
        }
        long token = ClassIdentityToken.token(allocationClassIdentity(symbol));
        return LlvmInstruction.raw(
                Optional.of(instruction.result().orElseThrow().name()),
                "call ptr @j2ll_rt_new_object_array(ptr %j2ll_env, i64 " + token + ", "
                        + typedOperand(instruction.operands().get(0)) + ")");
    }

    private LlvmInstruction lowerTypeHelper(xyz.melodysky.ir.model.IrInstruction instruction) {
        long token = ClassIdentityToken.token(typeClassIdentity(instruction.symbol().orElseThrow()));
        if (instruction.opcode() == IrOpcode.CHECKCAST) {
            return LlvmInstruction.raw(
                    Optional.of(instruction.result().orElseThrow().name()),
                    "call ptr @j2ll_rt_checkcast(ptr %j2ll_env, "
                            + typedOperand(instruction.operands().get(0)) + ", i64 " + token + ")");
        }
        return LlvmInstruction.raw(
                Optional.of(instruction.result().orElseThrow().name()),
                "call i32 @j2ll_rt_instanceof(ptr %j2ll_env, "
                        + typedOperand(instruction.operands().get(0)) + ", i64 " + token + ")");
    }

    private LlvmInstruction lowerEnvBackedRuntimeCall(
            xyz.melodysky.ir.model.IrInstruction instruction,
            String symbol) {
        String args = typedOperands(instruction.operands());
        String arguments = args.isEmpty() ? "ptr %j2ll_env" : "ptr %j2ll_env, " + args;
        if (instruction.result().isPresent()) {
            String type = typeLowerer.lower(instruction.result().orElseThrow().type()).text();
            return LlvmInstruction.raw(
                    Optional.of(instruction.result().orElseThrow().name()),
                    "call " + type + " @" + symbol + "(" + arguments + ")");
        }
        return LlvmInstruction.raw(Optional.empty(), "call void @" + symbol + "(" + arguments + ")");
    }

    private LlvmInstruction lowerConversion(xyz.melodysky.ir.model.IrInstruction instruction) {
        IrValue operand = instruction.operands().get(0);
        String operandType = typeLowerer.lower(operand.type()).text();
        String resultName = instruction.result().orElseThrow().name();
        return switch (instruction.opcode()) {
            case I2L -> LlvmInstruction.raw(Optional.of(resultName), "sext i32 " + operand.name() + " to i64");
            case I2F -> LlvmInstruction.raw(Optional.of(resultName), "sitofp i32 " + operand.name() + " to float");
            case I2D -> LlvmInstruction.raw(Optional.of(resultName), "sitofp i32 " + operand.name() + " to double");
            case L2I -> LlvmInstruction.raw(Optional.of(resultName), "trunc i64 " + operand.name() + " to i32");
            case L2F -> LlvmInstruction.raw(Optional.of(resultName), "sitofp i64 " + operand.name() + " to float");
            case L2D -> LlvmInstruction.raw(Optional.of(resultName), "sitofp i64 " + operand.name() + " to double");
            case F2D -> LlvmInstruction.raw(Optional.of(resultName), "fpext float " + operand.name() + " to double");
            case D2F -> LlvmInstruction.raw(Optional.of(resultName), "fptrunc double " + operand.name() + " to float");
            case I2B, I2C, I2S, F2I, F2L, D2I, D2L -> LlvmInstruction.raw(
                    Optional.of(resultName),
                    "call " + typeLowerer.lower(instruction.result().orElseThrow().type()).text()
                            + " @" + helperName(instruction.opcode()) + "(" + operandType + " " + operand.name() + ")");
            default -> throw new IllegalArgumentException("not a conversion opcode: " + instruction.opcode());
        };
    }

    private LlvmInstruction lowerSymbolicConstant(xyz.melodysky.ir.model.IrInstruction instruction) {
        String helper = "j2ll_const_" + constantKind(instruction.opcode()) + "_" + stableHash(instruction.symbol().orElseThrow());
        return LlvmInstruction.raw(
                Optional.of(instruction.result().orElseThrow().name()),
                "call ptr @" + helper + "()");
    }

    private LlvmInstruction lowerRuntimeModelHelper(xyz.melodysky.ir.model.IrInstruction instruction) {
        String helper = runtimeModelHelperName(instruction);
        String args = typedOperands(instruction.operands());
        if (isEnvBackedRuntimeModelHelper(instruction.opcode())) {
            String arguments = args.isEmpty() ? "ptr %j2ll_env" : "ptr %j2ll_env, " + args;
            if (instruction.result().isPresent()) {
                String type = typeLowerer.lower(instruction.result().orElseThrow().type()).text();
                return LlvmInstruction.raw(
                        Optional.of(instruction.result().orElseThrow().name()),
                        "call " + type + " @" + helper + "(" + arguments + ")");
            }
            return LlvmInstruction.raw(Optional.empty(), "call void @" + helper + "(" + arguments + ")");
        }
        if (instruction.result().isPresent()) {
            String type = typeLowerer.lower(instruction.result().orElseThrow().type()).text();
            return LlvmInstruction.raw(
                    Optional.of(instruction.result().orElseThrow().name()),
                    "call " + type + " @" + helper + "(" + args + ")");
        }
        return LlvmInstruction.raw(Optional.empty(), "call void @" + helper + "(" + args + ")");
    }

    private LlvmInstruction lowerMemoryFence(xyz.melodysky.ir.model.IrInstruction instruction) {
        String ordering = switch (instruction.opcode()) {
            case VOLATILE_READ_BARRIER -> "acquire";
            case VOLATILE_WRITE_BARRIER, FINAL_FIELD_PUBLICATION -> "release";
            case MONITOR_HAPPENS_BEFORE -> instruction.symbol().orElse("").equals("monitorEnter")
                    ? "acquire"
                    : "release";
            case CLASS_INIT_HAPPENS_BEFORE -> (instruction.symbol().orElse("").equals("classInitEnd")
                            || instruction.symbol().orElse("").equals("classInitFailed"))
                    ? "release"
                    : "acquire";
            default -> throw new IllegalArgumentException("not a memory fence opcode: " + instruction.opcode());
        };
        return LlvmInstruction.raw(Optional.empty(), "fence " + ordering);
    }

    private LlvmInstruction lowerHelperCall(xyz.melodysky.ir.model.IrInstruction instruction, String helperName) {
        String resultType = typeLowerer.lower(instruction.result().orElseThrow().type()).text();
        return LlvmInstruction.raw(
                Optional.of(instruction.result().orElseThrow().name()),
                "call " + resultType + " @" + helperName + "(" + typedOperands(instruction.operands()) + ")");
    }

    private LlvmInstruction lowerEnvBackedHelperCall(
            xyz.melodysky.ir.model.IrInstruction instruction,
            String helperName) {
        String args = typedOperands(instruction.operands());
        String arguments = args.isEmpty() ? "ptr %j2ll_env" : "ptr %j2ll_env, " + args;
        if (instruction.result().isPresent()) {
            String resultType = typeLowerer.lower(instruction.result().orElseThrow().type()).text();
            return LlvmInstruction.raw(
                    Optional.of(instruction.result().orElseThrow().name()),
                    "call " + resultType + " @" + helperName + "(" + arguments + ")");
        }
        return LlvmInstruction.raw(Optional.empty(), "call void @" + helperName + "(" + arguments + ")");
    }

    private String typedOperands(List<IrValue> operands) {
        return operands.stream()
                .map(this::typedOperand)
                .collect(java.util.stream.Collectors.joining(", "));
    }

    private String typedOperand(IrValue operand) {
        return typeLowerer.lower(operand.type()).text() + " " + operand.name();
    }

    private String safeSymbol(String value) {
        StringBuilder result = new StringBuilder();
        for (int index = 0; index < value.length(); index++) {
            char ch = value.charAt(index);
            if ((ch >= 'a' && ch <= 'z')
                    || (ch >= 'A' && ch <= 'Z')
                    || (ch >= '0' && ch <= '9')) {
                result.append(ch);
            } else {
                result.append('_');
            }
        }
        return result.toString();
    }

    private String comparePredicate(IrOpcode opcode) {
        return switch (opcode) {
            case CMP_EQ_I32 -> "eq";
            case CMP_NE_I32 -> "ne";
            case CMP_LT_I32 -> "slt";
            case CMP_LE_I32 -> "sle";
            case CMP_GT_I32 -> "sgt";
            case CMP_GE_I32 -> "sge";
            case CMP_EQ_REF -> "eq";
            case CMP_NE_REF -> "ne";
            default -> throw new IllegalArgumentException("not a primitive compare opcode: " + opcode);
        };
    }

    private String helperName(IrOpcode opcode) {
        return switch (opcode) {
            case I2B -> "j2ll_rt_i2b";
            case I2C -> "j2ll_rt_i2c";
            case I2S -> "j2ll_rt_i2s";
            case F2I -> "j2ll_rt_f2i";
            case F2L -> "j2ll_rt_f2l";
            case D2I -> "j2ll_rt_d2i";
            case D2L -> "j2ll_rt_d2l";
            case LCMP -> "j2ll_rt_lcmp";
            case FCMPL -> "j2ll_rt_fcmpl";
            case FCMPG -> "j2ll_rt_fcmpg";
            case DCMPL -> "j2ll_rt_dcmpl";
            case DCMPG -> "j2ll_rt_dcmpg";
            case DIV_I32 -> "j2ll_rt_div_i32";
            case REM_I32 -> "j2ll_rt_rem_i32";
            case DIV_I64 -> "j2ll_rt_div_i64";
            case REM_I64 -> "j2ll_rt_rem_i64";
            case ARRAY_LENGTH -> "j2ll_rt_array_length_i32";
            case ARRAY_LOAD_I32 -> "j2ll_rt_array_load_i32";
            case ARRAY_STORE_I32 -> "j2ll_rt_array_store_i32";
            default -> throw new IllegalArgumentException("opcode has no runtime helper: " + opcode);
        };
    }

    private String arrayHelperName(xyz.melodysky.ir.model.IrInstruction instruction) {
        return switch (instruction.opcode()) {
            case ARRAY_LENGTH -> "j2ll_rt_array_length_i32";
            case ARRAY_LOAD_REF -> "j2ll_rt_array_load_ref";
            case ARRAY_STORE_REF -> "j2ll_rt_array_store_ref";
            case ARRAY_LOAD_I32 -> instruction.symbol().orElse("").equals("byteOrBoolean")
                    ? "j2ll_rt_array_load_i8"
                    : instruction.symbol().orElse("").equals("short")
                            ? "j2ll_rt_array_load_i16"
                            : instruction.symbol().orElse("").equals("char")
                                    ? "j2ll_rt_array_load_u16"
                                    : "j2ll_rt_array_load_i32";
            case ARRAY_STORE_I32 -> instruction.symbol().orElse("").equals("byteOrBoolean")
                    ? "j2ll_rt_array_store_i8"
                    : instruction.symbol().orElse("").equals("short")
                            ? "j2ll_rt_array_store_i16"
                            : instruction.symbol().orElse("").equals("char")
                                    ? "j2ll_rt_array_store_u16"
                                    : "j2ll_rt_array_store_i32";
            case ARRAY_LOAD_I64 -> "j2ll_rt_array_load_i64";
            case ARRAY_STORE_I64 -> "j2ll_rt_array_store_i64";
            case ARRAY_LOAD_F32 -> "j2ll_rt_array_load_f32";
            case ARRAY_STORE_F32 -> "j2ll_rt_array_store_f32";
            case ARRAY_LOAD_F64 -> "j2ll_rt_array_load_f64";
            case ARRAY_STORE_F64 -> "j2ll_rt_array_store_f64";
            default -> throw new IllegalArgumentException("not an array helper opcode: " + instruction.opcode());
        };
    }

    private String runtimeModelHelperName(xyz.melodysky.ir.model.IrInstruction instruction) {
        IrOpcode opcode = instruction.opcode();
        if (opcode == IrOpcode.ARRAY_LENGTH) {
            return "j2ll_rt_array_length";
        }
        if (opcode == IrOpcode.CLASS_OBJECT) {
            return "j2ll_rt_class_object";
        }
        if (opcode == IrOpcode.CLASS_INIT_GUARD) {
            return "j2ll_rt_class_init_guard";
        }
        if (opcode == IrOpcode.CLASS_INIT_BEGIN) {
            return "j2ll_rt_class_init_begin";
        }
        if (opcode == IrOpcode.CLASS_INIT_END) {
            return "j2ll_rt_class_init_end";
        }
        if (opcode == IrOpcode.CLASS_INIT_FAILED) {
            return "j2ll_rt_class_init_failed";
        }
        if (opcode == IrOpcode.MONITOR_ENTER) {
            return "j2ll_rt_monitor_enter";
        }
        if (opcode == IrOpcode.MONITOR_EXIT) {
            return "j2ll_rt_monitor_exit";
        }
        if (opcode == IrOpcode.MONITOR_EXIT_ON_EXCEPTION) {
            return "j2ll_rt_monitor_exit_on_exception";
        }
        if (opcode == IrOpcode.THREAD_START_HAPPENS_BEFORE) {
            return "j2ll_rt_thread_start_happens_before";
        }
        if (opcode == IrOpcode.THREAD_JOIN_HAPPENS_BEFORE) {
            return "j2ll_rt_thread_join_happens_before";
        }
        return "j2ll_rt_" + runtimeModelKind(opcode) + "_" + stableHash(instruction.symbol().orElseThrow());
    }

    private String runtimeModelKind(IrOpcode opcode) {
        return switch (opcode) {
            case NEW_OBJECT -> "new_object";
            case NEW_ARRAY -> "new_array";
            case NEW_MULTI_ARRAY -> "new_multi_array";
            case ARRAY_LOAD_I32 -> "array_load_i32";
            case ARRAY_LOAD_I64 -> "array_load_i64";
            case ARRAY_LOAD_F32 -> "array_load_f32";
            case ARRAY_LOAD_F64 -> "array_load_f64";
            case ARRAY_LOAD_REF -> "array_load_ref";
            case ARRAY_STORE_I32 -> "array_store_i32";
            case ARRAY_STORE_I64 -> "array_store_i64";
            case ARRAY_STORE_F32 -> "array_store_f32";
            case ARRAY_STORE_F64 -> "array_store_f64";
            case ARRAY_STORE_REF -> "array_store_ref";
            case CHECKCAST -> "checkcast";
            case INSTANCEOF -> "instanceof";
            case MONITOR_ENTER, MONITOR_EXIT, MONITOR_EXIT_ON_EXCEPTION,
                    THREAD_START_HAPPENS_BEFORE, THREAD_JOIN_HAPPENS_BEFORE ->
                    throw new IllegalStateException("fixed JMM helper handled earlier");
            default -> throw new IllegalArgumentException("not a runtime model helper opcode: " + opcode);
        };
    }

    private String callPrefix(IrOpcode opcode) {
        return switch (opcode) {
            case CALL_STATIC, CALL_SPECIAL -> "j2ll_call_";
            case CALL_VIRTUAL -> "j2ll_call_virtual_";
            case CALL_INTERFACE -> "j2ll_call_interface_";
            case CALL_DYNAMIC -> "j2ll_call_dynamic_";
            case CALL_RUNTIME_HELPER -> "";
            default -> throw new IllegalArgumentException("not a call opcode: " + opcode);
        };
    }

    private boolean isEnvBackedRuntimeHelperSymbol(String symbol) {
        String base = runtimeHelperBaseSymbol(symbol);
        return base.equals("j2ll_rt_string_length")
                || base.equals("j2ll_rt_string_equals")
                || base.equals("j2ll_rt_string_is_empty")
                || base.equals("j2ll_rt_string_char_at")
                || base.equals("j2ll_rt_string_starts_with")
                || base.equals("j2ll_rt_string_ends_with")
                || base.equals("j2ll_rt_string_substring")
                || base.equals("j2ll_rt_string_substring_range")
                || base.equals("j2ll_rt_string_constant")
                || base.startsWith("j2ll_rt_string_builder_")
                || base.equals("j2ll_rt_system_arraycopy")
                || base.startsWith("j2ll_rt_integer_")
                || base.startsWith("j2ll_rt_long_")
                || base.startsWith("j2ll_rt_boolean_")
                || base.startsWith("j2ll_rt_double_")
                || base.startsWith("j2ll_rt_objects_")
                || base.equals("j2ll_rt_lambda_new")
                || base.equals("j2ll_rt_class_for_name_static")
                || base.equals("j2ll_rt_get_declared_method")
                || base.equals("j2ll_rt_get_declared_field")
                || base.equals("j2ll_rt_get_declared_constructor")
                || base.equals("j2ll_rt_reflect_invoke")
                || base.equals("j2ll_rt_reflect_new_instance")
                || base.startsWith("j2ll_rt_reflect_field_")
                || base.startsWith("j2ll_rt_unsafe_")
                || base.startsWith("j2ll_rt_var_handle_");
    }

    private String runtimeHelperBaseSymbol(String symbol) {
        int separator = symbol.indexOf('|');
        return separator < 0 ? symbol : symbol.substring(0, separator);
    }

    private boolean isDispatchHelperInstruction(xyz.melodysky.ir.model.IrInstruction instruction) {
        return (instruction.opcode() == IrOpcode.CALL_VIRTUAL
                        || instruction.opcode() == IrOpcode.CALL_INTERFACE)
                && instruction.result().map(IrValue::type).filter(type -> type == xyz.melodysky.ir.model.IrType.I32).isPresent()
                && instruction.operands().size() == 1
                && instruction.operands().get(0).type() == xyz.melodysky.ir.model.IrType.REFERENCE
                && instruction.symbol().map(symbol -> symbol.endsWith("!()I")).orElse(false);
    }

    private boolean isConstructorCallHelperInstruction(xyz.melodysky.ir.model.IrInstruction instruction) {
        return instruction.opcode() == IrOpcode.CALL_SPECIAL
                && instruction.symbol().map(symbol -> symbol.contains("#<init>!")).orElse(false)
                && (instruction.operands().size() == 1 || instruction.operands().size() == 3);
    }

    private boolean isDirectSpecialCallInstruction(xyz.melodysky.ir.model.IrInstruction instruction) {
        return instruction.opcode() == IrOpcode.CALL_SPECIAL
                && instruction.symbol().map(symbol -> !symbol.contains("#<init>!")).orElse(false);
    }

    private boolean isTypeHelper(xyz.melodysky.ir.model.IrInstruction instruction) {
        return instruction.opcode() == IrOpcode.CHECKCAST || instruction.opcode() == IrOpcode.INSTANCEOF;
    }

    private String allocationClassIdentity(String symbol) {
        if (symbol.startsWith("object:")) {
            return "L" + symbol.substring("object:".length()) + ";";
        }
        if (symbol.startsWith("referenceArray:")) {
            String component = symbol.substring("referenceArray:".length());
            return component.startsWith("[") ? component : "L" + component + ";";
        }
        throw new IllegalArgumentException("not an allocation class symbol: " + symbol);
    }

    private String typeClassIdentity(String symbol) {
        if (symbol.startsWith("checkcast:")) {
            return classIdentity(symbol.substring("checkcast:".length()));
        }
        if (symbol.startsWith("instanceof:")) {
            return classIdentity(symbol.substring("instanceof:".length()));
        }
        throw new IllegalArgumentException("unsupported type helper symbol " + symbol);
    }

    private String classIdentity(String internalOrDescriptor) {
        if (internalOrDescriptor.startsWith("[")) {
            return internalOrDescriptor;
        }
        if (internalOrDescriptor.startsWith("L") && internalOrDescriptor.endsWith(";")) {
            return internalOrDescriptor;
        }
        return "L" + internalOrDescriptor + ";";
    }

    private String constantKind(IrOpcode opcode) {
        return switch (opcode) {
            case CONST_STRING -> "string";
            case CONST_CLASS -> "class";
            case CONST_METHOD_TYPE -> "method_type";
            case CONST_METHOD_HANDLE -> "method_handle";
            default -> throw new IllegalArgumentException("not a symbolic constant opcode: " + opcode);
        };
    }

    private String stableHash(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder();
            for (int index = 0; index < 8; index++) {
                result.append(String.format("%02x", hash[index]));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 digest is unavailable", exception);
        }
    }

    private boolean methodNeedsJniEnv(IrMethod method) {
        return method.blocks().stream()
                .anyMatch(block -> block.terminator().kind() == IrTerminatorKind.THROW)
                || method.blocks().stream()
                .flatMap(block -> block.instructions().stream())
                .anyMatch(instruction -> isFieldAccess(instruction.opcode())
                        || isArithmeticExceptionHelper(instruction.opcode())
                        || isArrayHelper(instruction)
                        || isAllocationHelper(instruction)
                        || isTypeHelper(instruction)
                        || isMonitorHelper(instruction.opcode())
                        || isClassInitHelper(instruction.opcode())
                        || isConstructorCallHelperInstruction(instruction)
                        || isDispatchHelperInstruction(instruction)
                        || (instruction.opcode() == IrOpcode.CALL_RUNTIME_HELPER
                                && instruction.symbol().map(this::isEnvBackedRuntimeHelperSymbol).orElse(false)));
    }

    private boolean methodNeedsOwnerClass(IrMethod method) {
        return method.blocks().stream()
                .flatMap(block -> block.instructions().stream())
                .anyMatch(instruction -> instruction.opcode() == IrOpcode.GET_STATIC
                        || instruction.opcode() == IrOpcode.PUT_STATIC);
    }

    private String fieldHelper(xyz.melodysky.ir.model.IrInstruction instruction) {
        RuntimeHelperKind kind = switch (instruction.opcode()) {
            case GET_STATIC -> switch (instruction.result().orElseThrow().type()) {
                case I32 -> RuntimeHelperKind.FIELD_GET_STATIC_I32;
                case I64 -> RuntimeHelperKind.FIELD_GET_STATIC_I64;
                case REFERENCE -> RuntimeHelperKind.FIELD_GET_STATIC_REF;
                default -> throw new IllegalArgumentException("unsupported static field get type "
                        + instruction.result().orElseThrow().type());
            };
            case PUT_STATIC -> switch (instruction.operands().get(0).type()) {
                case I32 -> RuntimeHelperKind.FIELD_PUT_STATIC_I32;
                case I64 -> RuntimeHelperKind.FIELD_PUT_STATIC_I64;
                case REFERENCE -> RuntimeHelperKind.FIELD_PUT_STATIC_REF;
                default -> throw new IllegalArgumentException("unsupported static field put type "
                        + instruction.operands().get(0).type());
            };
            case GET_FIELD -> switch (instruction.result().orElseThrow().type()) {
                case I32 -> RuntimeHelperKind.FIELD_GET_FIELD_I32;
                case I64 -> RuntimeHelperKind.FIELD_GET_FIELD_I64;
                case REFERENCE -> RuntimeHelperKind.FIELD_GET_FIELD_REF;
                default -> throw new IllegalArgumentException("unsupported field get type "
                        + instruction.result().orElseThrow().type());
            };
            case PUT_FIELD -> switch (instruction.operands().get(1).type()) {
                case I32 -> RuntimeHelperKind.FIELD_PUT_FIELD_I32;
                case I64 -> RuntimeHelperKind.FIELD_PUT_FIELD_I64;
                case REFERENCE -> RuntimeHelperKind.FIELD_PUT_FIELD_REF;
                default -> throw new IllegalArgumentException("unsupported field put type "
                        + instruction.operands().get(1).type());
            };
            default -> throw new IllegalArgumentException("not a field opcode: " + instruction.opcode());
        };
        return runtimeHelpers.helper(kind).orElseThrow().llvmSymbol();
    }

    private record PhiIncoming(String predecessorBlock, List<IrValue> arguments) {
        private PhiIncoming {
            arguments = List.copyOf(arguments);
        }
    }
}
