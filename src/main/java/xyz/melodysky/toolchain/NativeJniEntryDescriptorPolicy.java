package xyz.melodysky.toolchain;

import java.util.Optional;
import xyz.melodysky.backend.llvm.model.LlvmType;
import xyz.melodysky.ir.model.IrType;
import xyz.melodysky.runtime.jni.JniTypeMapper;

/** Descriptor closed set for JVM-to-LLVM proxy entry points. */
final class NativeJniEntryDescriptorPolicy {
    private static final JniTypeMapper TYPE_MAPPER = new JniTypeMapper();

    private NativeJniEntryDescriptorPolicy() {}

    static boolean supports(String descriptor) {
        return TYPE_MAPPER.parameterDescriptors(descriptor).stream()
                        .allMatch(NativeJniEntryDescriptorPolicy
                                ::supportsValue)
                && (TYPE_MAPPER.returnDescriptor(descriptor).equals("V")
                        || supportsValue(
                                TYPE_MAPPER.returnDescriptor(descriptor)));
    }

    static boolean hasReferenceSurface(String descriptor) {
        return TYPE_MAPPER.parameterDescriptors(descriptor).stream()
                        .anyMatch(NativeJniEntryDescriptorPolicy::isReference)
                || isReference(TYPE_MAPPER.returnDescriptor(descriptor));
    }

    static Optional<LlvmType> llvmType(
            String descriptor,
            boolean allowVoid) {
        return switch (descriptor) {
            case "V" -> allowVoid
                    ? Optional.of(LlvmType.VOID)
                    : Optional.empty();
            case "I" -> Optional.of(LlvmType.I32);
            case "J" -> Optional.of(LlvmType.I64);
            case "F" -> Optional.of(LlvmType.F32);
            case "D" -> Optional.of(LlvmType.F64);
            default -> isReference(descriptor)
                    ? Optional.of(LlvmType.PTR)
                    : Optional.empty();
        };
    }

    static Optional<IrType> irType(String descriptor) {
        return switch (descriptor) {
            case "I" -> Optional.of(IrType.I32);
            case "J" -> Optional.of(IrType.I64);
            case "F" -> Optional.of(IrType.F32);
            case "D" -> Optional.of(IrType.F64);
            default -> isReference(descriptor)
                    ? Optional.of(IrType.REFERENCE)
                    : Optional.empty();
        };
    }

    private static boolean supportsValue(String descriptor) {
        return descriptor.equals("I")
                || descriptor.equals("J")
                || descriptor.equals("F")
                || descriptor.equals("D")
                || isReference(descriptor);
    }

    private static boolean isReference(String descriptor) {
        return descriptor.startsWith("L") || descriptor.startsWith("[");
    }
}
