package xyz.melodysky.toolchain;

import java.util.Objects;
import java.util.Optional;
import xyz.melodysky.backend.llvm.LlvmFunctionAbi;

/**
 * Final physical entry selected for one registered Java native method.
 *
 * <p>The plan separates the physical JNI proxy from the semantic LLVM body.
 * The proxy owns the JVM implicit parameters and a bounded local-ABI topology;
 * the body retains its original compiler-internal ABI.</p>
 */
public record NativeJniEntryPlan(
        Kind kind,
        String functionSymbol,
        LlvmFunctionAbi physicalLlvmAbi,
        Optional<String> semanticBodySymbol,
        LlvmFunctionAbi semanticLlvmAbi,
        Optional<NativeJniEntryTopology> topology,
        String reasonCode) {
    public NativeJniEntryPlan {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(functionSymbol, "functionSymbol");
        Objects.requireNonNull(physicalLlvmAbi, "physicalLlvmAbi");
        semanticBodySymbol = Objects.requireNonNull(
                semanticBodySymbol,
                "semanticBodySymbol");
        Objects.requireNonNull(semanticLlvmAbi, "semanticLlvmAbi");
        topology = Objects.requireNonNull(topology, "topology");
        Objects.requireNonNull(reasonCode, "reasonCode");
        if (!functionSymbol.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw new IllegalArgumentException(
                    "JNI entry function symbol must be a C identifier");
        }
        if (reasonCode.isBlank()) {
            throw new IllegalArgumentException(
                    "JNI entry reason code must not be blank");
        }
        boolean proxy = kind == Kind.LLVM_JNI_PROXY;
        if (proxy != physicalLlvmAbi.isPhysicalJniEntry()
                || proxy != semanticBodySymbol.isPresent()
                || proxy != topology.isPresent()) {
            throw new IllegalArgumentException(
                    "JNI entry kind, physical ABI, semantic body and topology must agree");
        }
        if (semanticLlvmAbi.isPhysicalJniEntry()) {
            throw new IllegalArgumentException(
                    "semantic LLVM body cannot use the physical JNI-entry ABI");
        }
        if (proxy && functionSymbol.equals(semanticBodySymbol.orElseThrow())) {
            throw new IllegalArgumentException(
                    "LLVM JNI proxy and semantic body symbols must differ");
        }
    }

    public static NativeJniEntryPlan wrapped(
            String wrapperSymbol,
            LlvmFunctionAbi semanticAbi,
            String reasonCode) {
        return new NativeJniEntryPlan(
                Kind.GENERATED_C_WRAPPER,
                wrapperSymbol,
                semanticAbi,
                Optional.empty(),
                semanticAbi,
                Optional.empty(),
                reasonCode);
    }

    public static NativeJniEntryPlan llvmProxy(
            String proxySymbol,
            LlvmFunctionAbi physicalAbi,
            String semanticBodySymbol,
            LlvmFunctionAbi semanticAbi,
            NativeJniEntryTopology topology) {
        return llvmProxy(
                proxySymbol,
                physicalAbi,
                semanticBodySymbol,
                semanticAbi,
                topology,
                "LLVM_JNI_PROXY_PURE_SCALAR");
    }

    public static NativeJniEntryPlan llvmProxy(
            String proxySymbol,
            LlvmFunctionAbi physicalAbi,
            String semanticBodySymbol,
            LlvmFunctionAbi semanticAbi,
            NativeJniEntryTopology topology,
            String reasonCode) {
        return new NativeJniEntryPlan(
                Kind.LLVM_JNI_PROXY,
                proxySymbol,
                physicalAbi,
                Optional.of(semanticBodySymbol),
                semanticAbi,
                Optional.of(topology),
                reasonCode);
    }

    public boolean llvmJniProxy() {
        return kind == Kind.LLVM_JNI_PROXY;
    }

    public enum Kind {
        GENERATED_C_WRAPPER("generatedCWrapper"),
        LLVM_JNI_PROXY("llvmJniProxy");

        private final String wireName;

        Kind(String wireName) {
            this.wireName = wireName;
        }

        public String wireName() {
            return wireName;
        }
    }
}
