package xyz.melodysky.toolchain;

import java.util.Set;
import xyz.melodysky.ir.model.IrInstruction;
import xyz.melodysky.ir.model.IrMethod;
import xyz.melodysky.ir.model.IrOpcode;
import xyz.melodysky.ir.model.IrType;

/** Composes the closed instruction-support policies for one LLVM native body. */
final class NativeLlvmInstructionSupport {
    private final NativeIrTypeSupport types;
    private final NativeFieldInstructionSupport fields =
            new NativeFieldInstructionSupport();
    private final NativeArrayInstructionSupport arrays =
            new NativeArrayInstructionSupport();
    private final NativeJvmInstructionSupport jvm =
            new NativeJvmInstructionSupport();
    private final NativeCallInstructionSupport calls;
    private final NativeStringInstructionSupport strings =
            new NativeStringInstructionSupport();
    private final NativeJdkInstructionSupport jdk =
            new NativeJdkInstructionSupport();
    private final NativeDynamicInstructionSupport dynamic =
            new NativeDynamicInstructionSupport();
    private final NativeRuntimeMetadataInstructionSupport metadata =
            new NativeRuntimeMetadataInstructionSupport();

    NativeLlvmInstructionSupport(NativeIrTypeSupport types) {
        this.types = java.util.Objects.requireNonNull(types, "types");
        this.calls = new NativeCallInstructionSupport(types);
    }

    boolean supports(
            IrInstruction instruction,
            Set<String> directCallTargets,
            Set<String> availableProgramMethods) {
        if (jvm.isThrowableSemanticUnsupportedCall(instruction)) {
            return false;
        }
        if (fields.isAccess(instruction.opcode())) {
            return fields.supports(instruction);
        }
        if (arrays.isArrayHelper(instruction)) {
            return arrays.supportsArray(instruction);
        }
        if (arrays.isAllocationHelper(instruction)) {
            return arrays.supportsAllocation(instruction);
        }
        if (jvm.isClassInitHelper(instruction)) {
            return jvm.supportsClassInitHelper(instruction);
        }
        if (jvm.isTypeHelper(instruction)) {
            return jvm.supportsTypeHelper(instruction);
        }
        if (calls.isConstructorCall(instruction)) {
            return calls.supportsConstructorCall(instruction);
        }
        if (strings.isStringHelper(instruction)) {
            return strings.supportsStringHelper(instruction);
        }
        if (strings.isStringBuilderHelper(instruction)) {
            return strings.supportsStringBuilderHelper(instruction);
        }
        if (arrays.isArraycopyHelper(instruction)) {
            return arrays.supportsArraycopy(instruction);
        }
        if (metadata.isHelper(instruction)) {
            return metadata.supports(instruction);
        }
        if (dynamic.isVarHandleHelper(instruction)) {
            return dynamic.supportsVarHandleHelper(instruction);
        }
        if (dynamic.isLambdaHelper(instruction)) {
            return dynamic.supportsLambdaHelper(instruction);
        }
        if (dynamic.isUnsafeHelper(instruction)) {
            return dynamic.supportsUnsafeHelper(instruction);
        }
        if (jdk.isPureNativeHelper(instruction)) {
            return jdk.supportsPureNativeHelper(instruction);
        }
        if (jdk.isScalarHelper(instruction)) {
            return jdk.supportsScalarHelper(instruction);
        }
        if (instruction.opcode() == IrOpcode.CALL_DIRECT) {
            return supportsDirectCall(instruction, directCallTargets)
                    || calls.supportsDispatch(instruction);
        }
        if (calls.isDispatch(instruction)) {
            return calls.supportsDispatch(instruction);
        }
        if (jvm.isMonitorHelper(instruction)) {
            return jvm.supportsMonitorHelper(instruction);
        }
        if (instruction.opcode() == IrOpcode.CONST_NULL) {
            return instruction.result().map(result -> result.type())
                            .filter(type -> type == IrType.REFERENCE)
                            .isPresent()
                    && instruction.operands().isEmpty();
        }
        if (jvm.isSymbolicConstant(instruction)) {
            return instruction.result().map(result -> result.type())
                            .filter(type -> type == IrType.REFERENCE)
                            .isPresent()
                    && instruction.operands().isEmpty()
                    && instruction.symbol().isPresent();
        }
        if (instruction.opcode() == IrOpcode.CALL_STATIC) {
            return supportsDirectCall(instruction, directCallTargets)
                    || calls.supportsStaticBridge(instruction);
        }
        if (calls.isDirectSpecialCall(instruction)) {
            return supportsDirectCall(instruction, directCallTargets);
        }
        if (instruction.opcode() == IrOpcode.CMP_EQ_REF
                || instruction.opcode() == IrOpcode.CMP_NE_REF) {
            return instruction.result().map(result -> result.type())
                            .filter(type -> type == IrType.I1).isPresent()
                    && instruction.operands().size() == 2
                    && instruction.operands().stream().allMatch(
                            operand -> operand.type() == IrType.REFERENCE);
        }
        if (jvm.isArithmeticExceptionHelper(instruction)
                || jvm.isNumericHelper(instruction)) {
            return instruction.result().map(result -> result.type())
                            .filter(types::isPrimitiveScalar).isPresent()
                    && instruction.operands().stream()
                            .allMatch(operand -> types.isPrimitiveScalar(
                                    operand.type()));
        }
        if (jvm.isMemoryFence(instruction)) {
            return instruction.result().isEmpty()
                    && instruction.operands().stream()
                            .allMatch(operand -> types.isSupportedValueType(
                                    operand.type()));
        }
        if (instruction.result().map(result -> result.type())
                .filter(type -> !types.isPrimitiveScalar(type)).isPresent()) {
            return false;
        }
        if (instruction.operands().stream()
                .anyMatch(operand -> !types.isPrimitiveScalar(
                        operand.type()))) {
            return false;
        }
        return supportsPrimitiveOpcode(instruction.opcode());
    }

    boolean matchesEvidenceKind(
            NativeImplementationEvidenceKind kind,
            IrInstruction instruction,
            Set<String> availableProgramMethods) {
        return switch (kind) {
            case FIELD_ACCESS -> fields.isAccess(instruction.opcode());
            case SUPPORTED_ALLOCATION -> arrays.supportsAllocation(instruction);
            case ALLOCATION_HELPER -> arrays.isAllocationHelper(instruction);
            case SUPPORTED_TYPE -> jvm.supportsTypeHelper(instruction);
            case TYPE_HELPER -> jvm.isTypeHelper(instruction);
            case SUPPORTED_CONSTRUCTOR_CALL ->
                    calls.supportsConstructorCall(instruction);
            case CONSTRUCTOR_CALL_HELPER -> calls.isConstructorCall(instruction);
            case STRING_HELPER -> strings.isStringHelper(instruction);
            case STRING_BUILDER_HELPER -> strings.isStringBuilderHelper(instruction);
            case JDK_SCALAR_HELPER -> jdk.isScalarHelper(instruction);
            case PURE_NATIVE_JDK_HELPER -> jdk.isPureNativeHelper(instruction);
            case UNSAFE_HELPER -> dynamic.isUnsafeHelper(instruction);
            case VAR_HANDLE_HELPER -> dynamic.isVarHandleHelper(instruction);
            case LAMBDA_HELPER -> dynamic.isLambdaHelper(instruction);
            case ARITHMETIC_EXCEPTION_HELPER ->
                    jvm.isArithmeticExceptionHelper(instruction);
            case JVM_NUMERIC_HELPER -> jvm.isNumericHelper(instruction);
            case ARRAY_HELPER -> arrays.isArrayHelper(instruction);
            case ARRAYCOPY_HELPER -> arrays.isArraycopyHelper(instruction);
            case MONITOR_HELPER -> jvm.isMonitorHelper(instruction);
            case RUNTIME_METADATA_HELPER -> metadata.isHelper(instruction);
            case CLASS_INIT_HELPER -> jvm.isClassInitHelper(instruction);
            case DISPATCH_HELPER -> calls.isDispatch(instruction);
            case SUPPORTED_DISPATCH -> calls.supportsDispatch(instruction);
            case DIRECT_SPECIAL_CALL -> calls.isDirectSpecialCall(instruction);
            case SUPPORTED_STATIC_BRIDGE -> calls.supportsStaticBridge(instruction);
        };
    }

    boolean hasMonitorHelper(IrMethod method) {
        return method.blocks().stream()
                .flatMap(block -> block.instructions().stream())
                .anyMatch(jvm::isMonitorHelper);
    }

    private boolean supportsDirectCall(
            IrInstruction instruction,
            Set<String> directCallTargets) {
        return instruction.symbol().filter(directCallTargets::contains).isPresent()
                && instruction.result().map(result -> result.type())
                        .filter(type -> !types.isSupportedValueType(type)).isEmpty()
                && instruction.operands().stream()
                        .allMatch(operand -> types.isSupportedValueType(
                                operand.type()));
    }

    private boolean supportsPrimitiveOpcode(IrOpcode opcode) {
        return switch (opcode) {
            case CONST_INT, CONST_LONG, CONST_FLOAT, CONST_DOUBLE,
                    ADD_I32, SUB_I32, MUL_I32,
                    ADD_I64, SUB_I64, MUL_I64,
                    SHL_I32, SHR_I32, USHR_I32,
                    AND_I32, OR_I32, XOR_I32,
                    SHL_I64, SHR_I64, USHR_I64,
                    AND_I64, OR_I64, XOR_I64,
                    BITCAST_I32_TO_F32, BITCAST_I64_TO_F64,
                    ADD_F32, SUB_F32, MUL_F32, DIV_F32, REM_F32, NEG_F32,
                    ADD_F64, SUB_F64, MUL_F64, DIV_F64, REM_F64, NEG_F64,
                    NEG_I32, NEG_I64,
                    CMP_EQ_I32, CMP_NE_I32, CMP_LT_I32, CMP_LE_I32,
                    CMP_GT_I32, CMP_GE_I32,
                    I2L, I2F, I2D, L2I, L2F, L2D, F2D, D2F -> true;
            default -> false;
        };
    }
}
