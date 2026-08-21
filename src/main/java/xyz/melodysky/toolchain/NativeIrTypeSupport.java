package xyz.melodysky.toolchain;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import xyz.melodysky.ir.model.IrMethod;
import xyz.melodysky.ir.model.IrTerminator;
import xyz.melodysky.ir.model.IrTerminatorKind;
import xyz.melodysky.ir.model.IrType;
import xyz.melodysky.ir.model.IrValue;
import xyz.melodysky.packaging.MethodRewriteDecision;
import xyz.melodysky.runtime.jni.JniTypeMapper;

/** Closed JVM descriptor and SSA value-type policy for LLVM native bodies. */
final class NativeIrTypeSupport {
    private static final Set<String> LLVM_SCALAR_DESCRIPTORS =
            Set.of("Z", "B", "C", "S", "I", "J", "F", "D");

    private final JniTypeMapper typeMapper = new JniTypeMapper();

    boolean supportsJvmHostedDescriptor(String descriptor) {
        if (!typeMapper.parameterDescriptors(descriptor).stream()
                .allMatch(this::isSupportedDescriptor)) {
            return false;
        }
        String returnDescriptor = typeMapper.returnDescriptor(descriptor);
        return returnDescriptor.equals("V")
                || isSupportedDescriptor(returnDescriptor);
    }

    boolean supportsTerminator(IrTerminator terminator) {
        if (terminator.kind() == IrTerminatorKind.THROW) {
            return terminator.value()
                    .map(IrValue::type)
                    .filter(type -> type == IrType.REFERENCE)
                    .isPresent();
        }
        if (terminator.value().map(IrValue::type)
                .filter(type -> !isSupportedValueType(type)).isPresent()) {
            return false;
        }
        if (terminator.condition().map(IrValue::type)
                .filter(type -> type != IrType.I1).isPresent()) {
            return false;
        }
        return supportedArguments(terminator.targetArguments())
                && supportedArguments(terminator.trueTargetArguments())
                && supportedArguments(terminator.falseTargetArguments())
                && supportedArguments(terminator.defaultTargetArguments())
                && terminator.switchCases().stream()
                        .allMatch(switchCase ->
                                supportedArguments(switchCase.arguments()));
    }

    boolean isSupportedReturnType(IrType type) {
        return type == IrType.VOID || isSupportedValueType(type);
    }

    boolean isSupportedValueType(IrType type) {
        return isPrimitiveScalar(type) || type == IrType.REFERENCE;
    }

    boolean isPrimitiveScalar(IrType type) {
        return type == IrType.I1
                || type == IrType.I32
                || type == IrType.I64
                || type == IrType.F32
                || type == IrType.F64;
    }

    boolean supportsParameters(
            MethodRewriteDecision decision,
            IrMethod method) {
        int start = decision.method().accessFlags().isStatic() ? 0 : 1;
        if (!decision.method().accessFlags().isStatic()
                && (method.parameters().isEmpty()
                        || method.parameters().get(0).type()
                                != IrType.REFERENCE)) {
            return false;
        }
        return method.parameters().stream()
                .skip(start)
                .map(IrValue::type)
                .allMatch(this::isSupportedValueType);
    }

    boolean operandsMatchDescriptor(
            String descriptor,
            List<IrValue> operands) {
        List<String> parameterDescriptors =
                typeMapper.parameterDescriptors(descriptor);
        if (parameterDescriptors.size() != operands.size()) {
            return false;
        }
        for (int index = 0; index < parameterDescriptors.size(); index++) {
            if (descriptorType(parameterDescriptors.get(index))
                    != operands.get(index).type()) {
                return false;
            }
        }
        return true;
    }

    IrType descriptorType(String descriptor) {
        return switch (descriptor.charAt(0)) {
            case 'Z', 'B', 'C', 'S', 'I' -> IrType.I32;
            case 'J' -> IrType.I64;
            case 'F' -> IrType.F32;
            case 'D' -> IrType.F64;
            case '[', 'L' -> IrType.REFERENCE;
            default -> throw new IllegalArgumentException(
                    "unsupported descriptor type: " + descriptor);
        };
    }

    Optional<String> methodDescriptor(String methodKey) {
        int separator = methodKey.indexOf('!');
        if (separator < 0 || separator == methodKey.length() - 1) {
            return Optional.empty();
        }
        return Optional.of(methodKey.substring(separator + 1));
    }

    String returnDescriptor(String descriptor) {
        return typeMapper.returnDescriptor(descriptor);
    }

    private boolean isSupportedDescriptor(String descriptor) {
        return LLVM_SCALAR_DESCRIPTORS.contains(descriptor)
                || descriptor.equals("[Z")
                || descriptor.equals("[B")
                || descriptor.equals("[S")
                || descriptor.equals("[C")
                || descriptor.equals("[I")
                || descriptor.equals("[J")
                || descriptor.equals("[F")
                || descriptor.equals("[D")
                || descriptor.startsWith("[L")
                || descriptor.startsWith("L");
    }

    private boolean supportedArguments(List<IrValue> values) {
        return values.stream().map(IrValue::type)
                .allMatch(this::isSupportedValueType);
    }
}
