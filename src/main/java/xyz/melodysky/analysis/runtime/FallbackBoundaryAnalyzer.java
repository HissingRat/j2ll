package xyz.melodysky.analysis.runtime;

import java.util.List;
import java.util.Optional;
import xyz.melodysky.ir.model.IrOpcode;
import xyz.melodysky.ir.ssa.SsaMethodResult;
import xyz.melodysky.pipeline.LoweringStatus;

/** Classifies why a half-lowered method must retain JVM bytecode semantics. */
public final class FallbackBoundaryAnalyzer {
    private final JvmCallBoundaryClassifier callBoundaries = new JvmCallBoundaryClassifier();

    public Optional<FallbackBoundarySite> analyze(SsaMethodResult result) {
        if (result.status() != LoweringStatus.HALF_LOWERED) {
            return Optional.empty();
        }
        return Optional.of(new FallbackBoundarySite(
                -1,
                result.sourceMethod().methodKey(),
                reasonCode(result),
                "nativeEmbeddedClassBlob"));
    }

    public String reasonCode(SsaMethodResult result) {
        if (result.irMethod().isPresent()) {
            List<String> symbols = result.irMethod().orElseThrow().blocks().stream()
                    .flatMap(block -> block.instructions().stream())
                    .flatMap(instruction -> instruction.symbol().stream())
                    .toList();
            if (symbols.stream().anyMatch(this::isReflectionScan)) {
                return "REFLECTION_UNSUPPORTED_SCAN";
            }
            if (symbols.stream().anyMatch(symbol -> symbol.contains("java/lang/Class#forName")
                    || symbol.contains("java/lang/Class#getDeclared"))) {
                return "REFLECTION_DYNAMIC_FALLBACK";
            }
            if (symbols.stream().anyMatch(symbol -> symbol.contains("#") && symbol.contains("!"))
                    && symbols.stream().anyMatch(symbol -> symbol.contains("default interface super")
                            || symbol.contains("CallNonvirtual"))) {
                return "DEFAULT_INTERFACE_SUPER_FALLBACK";
            }
            boolean hasDynamicCall = result.irMethod().orElseThrow().blocks().stream()
                    .flatMap(block -> block.instructions().stream())
                    .anyMatch(instruction -> instruction.opcode() == IrOpcode.CALL_DYNAMIC);
            if (hasDynamicCall) {
                if (symbols.stream().anyMatch(symbol -> symbol.contains("altMetafactory"))) {
                    return "ALT_METAFACTORY_FALLBACK";
                }
                if (symbols.stream().anyMatch(symbol -> symbol.contains("LambdaMetafactory"))) {
                    return "LAMBDA_UNSUPPORTED_FALLBACK";
                }
                return "METHOD_HANDLE_CHAIN_FALLBACK";
            }
            if (symbols.stream().anyMatch(symbol -> symbol.contains("MethodHandle")
                    || symbol.contains("method_handle"))) {
                return methodHandleReason(symbols);
            }
            if (symbols.stream().anyMatch(this::isJdkHelperFallback)) {
                return "JDK_HELPER_FALLBACK";
            }
            if (symbols.stream().anyMatch(callBoundaries::isWaitNotifyCall)) {
                return "WAIT_NOTIFY_FALLBACK";
            }
            if (symbols.stream().anyMatch(callBoundaries::isThreadCall)) {
                return "THREAD_HELPER_FALLBACK";
            }
            if (symbols.stream().anyMatch(callBoundaries::isThrowableCall)) {
                return "THROWABLE_HELPER_FALLBACK";
            }
        }
        return reasonTextCode(result);
    }

    private String reasonTextCode(SsaMethodResult result) {
        String reason = result.reason() == null ? "" : result.reason();
        if (reason.contains("dynamic Class.forName") || reason.contains("dynamic reflection")) {
            return "REFLECTION_DYNAMIC_FALLBACK";
        }
        if (reason.contains("reflection member scan")) {
            return "REFLECTION_UNSUPPORTED_SCAN";
        }
        if (reason.contains("altMetafactory")) {
            return "ALT_METAFACTORY_FALLBACK";
        }
        if (reason.contains("LambdaMetafactory")) {
            return "LAMBDA_UNSUPPORTED_FALLBACK";
        }
        if (reason.contains("MethodHandle") || reason.contains("method handle")) {
            return "METHOD_HANDLE_CHAIN_FALLBACK";
        }
        if (reason.contains("default interface super")) {
            return "DEFAULT_INTERFACE_SUPER_FALLBACK";
        }
        if (reason.contains("JDK_COLLECTION_HELPER_FALLBACK")
                || reason.contains("JDK_ARRAYS_HELPER_FALLBACK")
                || reason.contains("JDK_OPTIONAL_HELPER_FALLBACK")
                || reason.contains("JDK_FORMAT_HELPER_FALLBACK")
                || reason.contains("ArrayList")
                || reason.contains("HashMap")
                || reason.contains("Arrays.")) {
            return "JDK_HELPER_FALLBACK";
        }
        if (reason.contains("WAIT_NOTIFY_FALLBACK")) {
            return "WAIT_NOTIFY_FALLBACK";
        }
        if (reason.contains("THREAD_HELPER_FALLBACK")) {
            return "THREAD_HELPER_FALLBACK";
        }
        if (reason.contains("THROWABLE_HELPER_FALLBACK")) {
            return "THROWABLE_HELPER_FALLBACK";
        }
        return result.reasonCode();
    }

    private boolean isReflectionScan(String symbol) {
        return symbol.contains("java/lang/Class#getDeclaredMethods")
                || symbol.contains("java/lang/Class#getMethods")
                || symbol.contains("java/lang/Class#getDeclaredFields")
                || symbol.contains("java/lang/Class#getFields")
                || symbol.contains("java/lang/Class#getDeclaredConstructors")
                || symbol.contains("java/lang/Class#getConstructors");
    }

    private boolean isJdkHelperFallback(String symbol) {
        return symbol.contains("java/util/ArrayList")
                || symbol.contains("java/util/HashMap")
                || symbol.contains("java/util/Arrays")
                || symbol.contains("java/util/Collections")
                || symbol.contains("java/util/Optional")
                || symbol.contains("java/lang/String#format");
    }

    private String methodHandleReason(List<String> symbols) {
        if (symbols.stream().anyMatch(symbol -> symbol.contains("java/lang/invoke/MethodHandles#permuteArguments"))) {
            return "METHOD_HANDLE_PERMUTE_FALLBACK";
        }
        if (symbols.stream().anyMatch(symbol -> symbol.contains("java/lang/invoke/MethodHandles#filterArguments")
                || symbol.contains("java/lang/invoke/MethodHandles#filterReturnValue"))) {
            return "METHOD_HANDLE_FILTER_FALLBACK";
        }
        if (symbols.stream().anyMatch(symbol -> symbol.contains("java/lang/invoke/MethodHandles#foldArguments"))) {
            return "METHOD_HANDLE_FOLD_FALLBACK";
        }
        if (symbols.stream().anyMatch(symbol -> symbol.contains("java/lang/invoke/MethodHandle#asCollector")
                || symbol.contains("java/lang/invoke/MethodHandle#asSpreader")
                || symbol.contains("java/lang/invoke/MethodHandles#collectArguments"))) {
            return "METHOD_HANDLE_COLLECTOR_UNSUPPORTED";
        }
        return "METHOD_HANDLE_CHAIN_FALLBACK";
    }
}
