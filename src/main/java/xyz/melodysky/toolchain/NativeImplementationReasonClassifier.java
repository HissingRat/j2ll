package xyz.melodysky.toolchain;

import java.util.List;

/** Stable lowering reason classification for an approved LLVM implementation. */
final class NativeImplementationReasonClassifier {
    String classify(Facts facts) {
        if (facts.synchronizedMethod() && facts.monitorHelper()) {
            return "LLVM_SYNCHRONIZED_METHOD_HELPER_IR";
        }
        if (!facts.directCallTargets().isEmpty()) {
            return "LLVM_DIRECT_CALL_IR";
        }
        if (!facts.staticCallKeys().isEmpty()) {
            return "LLVM_STATIC_CALL_HELPER_IR";
        }
        if (!facts.dispatchKeys().isEmpty()) {
            return "LLVM_DISPATCH_HELPER_IR";
        }
        if (!facts.stringHelperSymbols().isEmpty()) {
            if (facts.stringHelperSymbols().stream().anyMatch(
                    symbol -> symbol.startsWith(
                            "j2ll_rt_string_constant_"))) {
                return "LLVM_STRING_CONCAT_CONSTANTS_HELPER_IR";
            }
            if (facts.stringHelperSymbols().stream().allMatch(
                    symbol -> symbol.startsWith(
                            "j2ll_rt_string_builder_"))) {
                return "LLVM_STRING_BUILDER_HELPER_IR";
            }
            return "LLVM_STRING_HELPER_IR";
        }
        if (facts.runtimeMetadataHelper()) {
            return "LLVM_REFLECTION_HELPER_IR";
        }
        if (facts.jdkScalarHelper()) {
            return "LLVM_JDK_INTRINSIC_HELPER_IR";
        }
        if (facts.varHandleHelper()) {
            return "LLVM_VARHANDLE_HELPER_IR";
        }
        if (facts.lambdaHelper()) {
            return "LLVM_LAMBDA_METAFACTORY_HELPER_IR";
        }
        if (facts.unsafeHelper()) {
            return "LLVM_UNSAFE_HELPER_IR";
        }
        if (facts.constructorCallHelper()) {
            return "LLVM_CONSTRUCTOR_CALL_HELPER_IR";
        }
        if (facts.allocationHelper()) {
            return "LLVM_ALLOCATION_HELPER_IR";
        }
        if (facts.typeHelper()) {
            return "LLVM_TYPE_HELPER_IR";
        }
        if (facts.arrayHelper()) {
            return "LLVM_ARRAY_HELPER_IR";
        }
        if (facts.arraycopyHelper()) {
            return "LLVM_ARRAYCOPY_HELPER_IR";
        }
        if (facts.arithmeticExceptionHelper()) {
            return "LLVM_DIV_REM_EXCEPTION_HELPER_IR";
        }
        if (facts.jvmNumericHelper()) {
            return "LLVM_JVM_NUMERIC_HELPER_IR";
        }
        if (facts.monitorHelper()) {
            return "LLVM_MONITOR_HELPER_IR";
        }
        if (facts.exceptionHelper()) {
            return "LLVM_EXCEPTION_HELPER_IR";
        }
        if (!facts.fieldKeys().isEmpty()) {
            return "LLVM_FIELD_HELPER_IR";
        }
        return "LLVM_PRIMITIVE_SCALAR_IR";
    }

    record Facts(
            List<String> fieldKeys,
            List<String> directCallTargets,
            List<String> staticCallKeys,
            List<String> dispatchKeys,
            List<String> stringHelperSymbols,
            boolean jdkScalarHelper,
            boolean allocationHelper,
            boolean typeHelper,
            boolean constructorCallHelper,
            boolean arithmeticExceptionHelper,
            boolean jvmNumericHelper,
            boolean arrayHelper,
            boolean arraycopyHelper,
            boolean varHandleHelper,
            boolean lambdaHelper,
            boolean unsafeHelper,
            boolean monitorHelper,
            boolean exceptionHelper,
            boolean runtimeMetadataHelper,
            boolean synchronizedMethod) {
        Facts {
            fieldKeys = List.copyOf(fieldKeys);
            directCallTargets = List.copyOf(directCallTargets);
            staticCallKeys = List.copyOf(staticCallKeys);
            dispatchKeys = List.copyOf(dispatchKeys);
            stringHelperSymbols = List.copyOf(stringHelperSymbols);
        }
    }
}
