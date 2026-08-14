package xyz.melodysky.toolchain;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import xyz.melodysky.backend.llvm.model.LlvmFunction;
import xyz.melodysky.backend.llvm.model.LlvmFunctionAttribute;
import xyz.melodysky.backend.llvm.model.LlvmLinkage;
import xyz.melodysky.backend.llvm.model.LlvmNativeUnwindSemantics;
import xyz.melodysky.backend.llvm.model.LlvmParameter;
import xyz.melodysky.backend.llvm.model.LlvmType;
import xyz.melodysky.backend.llvm.model.LlvmVisibility;
import xyz.melodysky.runtime.jni.JniTypeMapper;

/** Linkage, visibility, unwind and descriptor-ABI checks for proxy functions. */
final class NativeJniProxyFunctionVerifier {
    private final JniTypeMapper typeMapper = new JniTypeMapper();

    void verifySurface(
            String methodKey,
            LlvmFunction function,
            LlvmLinkage linkage,
            LlvmVisibility visibility,
            String prefix,
            List<String> issues) {
        if (function.linkage() != linkage) {
            add(issues, methodKey, prefix + "_LINKAGE_MISMATCH");
        }
        if (function.visibility() != visibility) {
            add(issues, methodKey, prefix + "_VISIBILITY_MISMATCH");
        }
        if (!function.attributes().contains(LlvmFunctionAttribute.NOINLINE)) {
            add(issues, methodKey, prefix + "_NOINLINE_MISSING");
        }
        if (function.nativeUnwindSemantics()
                != LlvmNativeUnwindSemantics.PROVEN_ABSENT) {
            add(issues, methodKey, prefix + "_UNWIND_EVIDENCE_MISMATCH");
        }
    }

    void verifySignature(
            String methodKey,
            LlvmFunction function,
            Signature expected,
            String prefix,
            List<String> issues) {
        if (function.returnType() != expected.returnType()) {
            add(issues, methodKey, prefix + "_RETURN_TYPE_MISMATCH");
        }
        if (!function.parameters().stream().map(LlvmParameter::type).toList()
                .equals(expected.parameterTypes())) {
            add(issues, methodKey, prefix + "_PARAMETER_TYPE_MISMATCH");
        }
    }

    Optional<Signature> semanticSignature(
            String descriptor,
            boolean staticMethod) {
        Optional<LlvmType> returnType = llvmType(
                typeMapper.returnDescriptor(descriptor),
                true);
        ArrayList<LlvmType> parameters = new ArrayList<>();
        if (!staticMethod) {
            parameters.add(LlvmType.PTR);
        }
        for (String descriptorPart : typeMapper.parameterDescriptors(descriptor)) {
            Optional<LlvmType> type = llvmType(descriptorPart, false);
            if (type.isEmpty()) {
                return Optional.empty();
            }
            parameters.add(type.orElseThrow());
        }
        return returnType.map(type -> new Signature(type, parameters));
    }

    private Optional<LlvmType> llvmType(String descriptor, boolean allowVoid) {
        return switch (descriptor) {
            case "V" -> allowVoid ? Optional.of(LlvmType.VOID) : Optional.empty();
            case "I" -> Optional.of(LlvmType.I32);
            case "J" -> Optional.of(LlvmType.I64);
            case "F" -> Optional.of(LlvmType.F32);
            case "D" -> Optional.of(LlvmType.F64);
            default -> Optional.empty();
        };
    }

    private void add(List<String> issues, String methodKey, String reasonCode) {
        issues.add(methodKey + ":" + reasonCode);
    }

    record Signature(LlvmType returnType, List<LlvmType> parameterTypes) {
        Signature {
            parameterTypes = List.copyOf(parameterTypes);
        }
    }
}
