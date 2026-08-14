package xyz.melodysky.toolchain;

import java.util.List;
import xyz.melodysky.backend.llvm.model.LlvmFunction;
import xyz.melodysky.backend.llvm.model.LlvmFunctionAttribute;
import xyz.melodysky.backend.llvm.model.LlvmLinkage;
import xyz.melodysky.backend.llvm.model.LlvmNativeUnwindSemantics;
import xyz.melodysky.backend.llvm.model.LlvmParameter;
import xyz.melodysky.backend.llvm.model.LlvmType;
import xyz.melodysky.backend.llvm.model.LlvmVisibility;

/** Linkage, visibility, unwind and descriptor-ABI checks for proxy functions. */
final class NativeJniProxyFunctionVerifier {
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

    private void add(List<String> issues, String methodKey, String reasonCode) {
        issues.add(methodKey + ":" + reasonCode);
    }

    record Signature(LlvmType returnType, List<LlvmType> parameterTypes) {
        Signature {
            parameterTypes = List.copyOf(parameterTypes);
        }
    }
}
