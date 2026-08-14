package xyz.melodysky.toolchain;

import java.util.List;
import java.util.Set;
import xyz.melodysky.backend.llvm.LlvmFunctionAbi;
import xyz.melodysky.ir.model.IrInstruction;
import xyz.melodysky.ir.model.IrMethod;
import xyz.melodysky.ir.model.IrOpcode;
import xyz.melodysky.ir.model.IrTerminator;
import xyz.melodysky.ir.model.IrTerminatorKind;
import xyz.melodysky.ir.model.IrType;
import xyz.melodysky.ir.model.IrValue;
import xyz.melodysky.packaging.MethodRewriteStrategy;
import xyz.melodysky.runtime.jni.JniTypeMapper;

/** Fail-closed policy for the first direct-JNI-entry fusion slice. */
final class NativeJniEntryEligibility {
    private static final Set<IrOpcode> PURE_SCALAR_OPCODES = Set.of(
            IrOpcode.CONST_INT,
            IrOpcode.CONST_LONG,
            IrOpcode.CONST_FLOAT,
            IrOpcode.CONST_DOUBLE,
            IrOpcode.ADD_I32,
            IrOpcode.SUB_I32,
            IrOpcode.MUL_I32,
            IrOpcode.NEG_I32,
            IrOpcode.SHL_I32,
            IrOpcode.SHR_I32,
            IrOpcode.USHR_I32,
            IrOpcode.AND_I32,
            IrOpcode.OR_I32,
            IrOpcode.XOR_I32,
            IrOpcode.BITCAST_I32_TO_F32,
            IrOpcode.CMP_EQ_I32,
            IrOpcode.CMP_NE_I32,
            IrOpcode.CMP_LT_I32,
            IrOpcode.CMP_LE_I32,
            IrOpcode.CMP_GT_I32,
            IrOpcode.CMP_GE_I32,
            IrOpcode.LCMP,
            IrOpcode.FCMPL,
            IrOpcode.FCMPG,
            IrOpcode.DCMPL,
            IrOpcode.DCMPG,
            IrOpcode.ADD_I64,
            IrOpcode.SUB_I64,
            IrOpcode.MUL_I64,
            IrOpcode.NEG_I64,
            IrOpcode.SHL_I64,
            IrOpcode.SHR_I64,
            IrOpcode.USHR_I64,
            IrOpcode.AND_I64,
            IrOpcode.OR_I64,
            IrOpcode.XOR_I64,
            IrOpcode.BITCAST_I64_TO_F64,
            IrOpcode.ADD_F32,
            IrOpcode.SUB_F32,
            IrOpcode.MUL_F32,
            IrOpcode.DIV_F32,
            IrOpcode.REM_F32,
            IrOpcode.NEG_F32,
            IrOpcode.ADD_F64,
            IrOpcode.SUB_F64,
            IrOpcode.MUL_F64,
            IrOpcode.DIV_F64,
            IrOpcode.REM_F64,
            IrOpcode.NEG_F64,
            IrOpcode.I2L,
            IrOpcode.I2F,
            IrOpcode.I2D,
            IrOpcode.I2B,
            IrOpcode.I2C,
            IrOpcode.I2S,
            IrOpcode.L2I,
            IrOpcode.L2F,
            IrOpcode.L2D,
            IrOpcode.F2I,
            IrOpcode.F2L,
            IrOpcode.F2D,
            IrOpcode.D2I,
            IrOpcode.D2L,
            IrOpcode.D2F);

    private final JniTypeMapper typeMapper = new JniTypeMapper();

    Decision assess(
            NativeMethodImplementation implementation,
            IrMethod method,
            boolean hasNativeCaller,
            boolean requiresLocalReferenceSemantics) {
        if (implementation.path()
                        != NativeImplementationPath.LLVM_NATIVE_PATH
                || !implementation.emitsStandaloneLlvmBody()) {
            return Decision.rejected("LLVM_JNI_PROXY_NOT_STANDALONE_LLVM");
        }
        if (implementation.decision().strategy()
                != MethodRewriteStrategy.NATIVE_ORIGINAL) {
            return Decision.rejected("LLVM_JNI_PROXY_SPECIAL_REWRITE");
        }
        if (implementation.decision().method().accessFlags().isSynchronized()) {
            return Decision.rejected("LLVM_JNI_PROXY_SYNCHRONIZED");
        }
        if (implementation.passesJniEnv()
                || implementation.passesOwnerClass()) {
            return Decision.rejected("LLVM_JNI_PROXY_SEMANTIC_JNI_ABI");
        }
        if (hasNativeCaller) {
            return Decision.rejected("LLVM_JNI_PROXY_HAS_NATIVE_CALLER");
        }
        if (requiresLocalReferenceSemantics) {
            return Decision.rejected("LLVM_JNI_PROXY_LOCAL_REFERENCE_PLAN");
        }
        if (NativeJniEntryImplementationFacts.hasRuntimeMetadata(
                implementation)) {
            return Decision.rejected("LLVM_JNI_PROXY_RUNTIME_METADATA");
        }
        if (method == null
                || !method.methodKey().equals(implementation.methodKey())) {
            return Decision.rejected("LLVM_JNI_PROXY_IR_MISSING");
        }
        String descriptor = implementation.decision().method().descriptor();
        if (!NativeJniEntryDescriptorPolicy.supports(descriptor)
                || !descriptorMatchesMethod(
                        descriptor,
                        implementation.decision()
                                .method()
                                .accessFlags()
                                .isStatic(),
                        method)) {
            return Decision.rejected("LLVM_JNI_PROXY_UNSAFE_DESCRIPTOR");
        }
        if (!isPureScalarBody(method)) {
            return Decision.rejected("LLVM_JNI_PROXY_NON_SCALAR_IR");
        }
        boolean staticMethod = implementation.decision()
                .method()
                .accessFlags()
                .isStatic();
        return Decision.approved(
                LlvmFunctionAbi.physicalJniEntry(staticMethod));
    }

    private boolean descriptorMatchesMethod(
            String descriptor,
            boolean staticMethod,
            IrMethod method) {
        List<String> parameterDescriptors =
                typeMapper.parameterDescriptors(descriptor);
        int offset = staticMethod ? 0 : 1;
        if (method.parameters().size()
                != parameterDescriptors.size() + offset) {
            return false;
        }
        if (!staticMethod
                && method.parameters().get(0).type()
                        != IrType.REFERENCE) {
            return false;
        }
        for (int index = 0; index < parameterDescriptors.size(); index++) {
            if (method.parameters().get(index + offset).type()
                    != irType(parameterDescriptors.get(index))) {
                return false;
            }
        }
        String returnDescriptor = typeMapper.returnDescriptor(descriptor);
        return method.returnType() == (returnDescriptor.equals("V")
                ? IrType.VOID
                : irType(returnDescriptor));
    }

    private IrType irType(String descriptor) {
        return switch (descriptor) {
            case "I" -> IrType.I32;
            case "J" -> IrType.I64;
            case "F" -> IrType.F32;
            case "D" -> IrType.F64;
            default -> throw new IllegalArgumentException(
                    "unsupported LLVM JNI proxy descriptor: "
                            + descriptor);
        };
    }

    private boolean isPureScalarBody(IrMethod method) {
        for (var block : method.blocks()) {
            if (!block.exceptionCatchTypes().isEmpty()
                    || !block.exceptionEdges().isEmpty()
                    || block.parameters().stream()
                            .map(IrValue::type)
                            .anyMatch(this::notScalar)) {
                return false;
            }
            for (IrInstruction instruction : block.instructions()) {
                if (!PURE_SCALAR_OPCODES.contains(instruction.opcode())
                        || !instruction.exceptionSites().isEmpty()
                        || instruction.result()
                                .map(IrValue::type)
                                .filter(this::notScalar)
                                .isPresent()
                        || instruction.operands().stream()
                                .map(IrValue::type)
                                .anyMatch(this::notScalar)) {
                    return false;
                }
            }
            if (!pureScalarTerminator(block.terminator())) {
                return false;
            }
        }
        return !method.blocks().isEmpty();
    }

    private boolean pureScalarTerminator(IrTerminator terminator) {
        if (terminator.kind() == IrTerminatorKind.THROW
                || terminator.value()
                        .map(IrValue::type)
                        .filter(this::notScalar)
                        .isPresent()
                || terminator.condition()
                        .map(IrValue::type)
                        .filter(type -> type != IrType.I1)
                        .isPresent()
                || terminator.switchValue()
                        .map(IrValue::type)
                        .filter(type -> type != IrType.I32)
                        .isPresent()) {
            return false;
        }
        return scalarValues(terminator.targetArguments())
                && scalarValues(terminator.trueTargetArguments())
                && scalarValues(terminator.falseTargetArguments())
                && scalarValues(terminator.defaultTargetArguments())
                && terminator.switchCases().stream()
                        .allMatch(switchCase ->
                                scalarValues(switchCase.arguments()));
    }

    private boolean scalarValues(List<IrValue> values) {
        return values.stream().map(IrValue::type).noneMatch(this::notScalar);
    }

    private boolean notScalar(IrType type) {
        return type == IrType.REFERENCE || type == IrType.VOID;
    }

    record Decision(
            boolean approved,
            LlvmFunctionAbi physicalAbi,
            String reasonCode) {
        private static Decision approved(LlvmFunctionAbi physicalAbi) {
            return new Decision(
                    true,
                    physicalAbi,
                    "LLVM_JNI_PROXY_PURE_SCALAR");
        }

        private static Decision rejected(String reasonCode) {
            return new Decision(
                    false,
                    new LlvmFunctionAbi(false, false),
                    reasonCode);
        }
    }
}
