package xyz.melodysky.toolchain;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/** Immutable LLVM proxy-to-semantic-body topology. */
public record NativeJniEntryTopology(
        Shape shape,
        int parameterCount,
        List<String> bridgeSymbols,
        List<List<Integer>> parameterOrders,
        int branchSalt) {
    public NativeJniEntryTopology {
        Objects.requireNonNull(shape, "shape");
        if (parameterCount < 0) {
            throw new IllegalArgumentException(
                    "JNI proxy topology parameter count must not be negative");
        }
        bridgeSymbols = List.copyOf(
                Objects.requireNonNull(bridgeSymbols, "bridgeSymbols"));
        parameterOrders = Objects.requireNonNull(
                        parameterOrders,
                        "parameterOrders")
                .stream()
                .map(List::copyOf)
                .toList();
        if (bridgeSymbols.size() != shape.bridgeCount()
                || parameterOrders.size() != shape.bridgeCount()) {
            throw new IllegalArgumentException(
                    "JNI proxy topology has the wrong bridge count");
        }
        if (!shape.branched() && branchSalt != 0) {
            throw new IllegalArgumentException(
                    "only branched JNI proxy topology may carry a salt");
        }
        HashSet<String> uniqueSymbols = new HashSet<>();
        for (String symbol : bridgeSymbols) {
            requireIdentifier(symbol);
            if (!uniqueSymbols.add(symbol)) {
                throw new IllegalArgumentException(
                        "JNI proxy bridge symbols must be unique");
            }
        }
        for (List<Integer> order : parameterOrders) {
            if (order.size() != parameterCount
                    || new HashSet<>(order).size() != parameterCount
                    || order.stream().anyMatch(index ->
                            index < 0 || index >= parameterCount)) {
                throw new IllegalArgumentException(
                        "JNI proxy parameter order must be a full permutation");
            }
        }
    }

    static NativeJniEntryTopology from(
            NativeLocalAbiPlan plan,
            List<String> hashOnlyBridgeSymbols) {
        Objects.requireNonNull(plan, "plan");
        return new NativeJniEntryTopology(
                Shape.valueOf(plan.shape().name()),
                plan.parameterCount(),
                hashOnlyBridgeSymbols,
                plan.parameterOrders(),
                plan.branchSalt());
    }

    public enum Shape {
        DIRECT_CANONICAL(0, false),
        SINGLE_PERMUTING_BRIDGE(1, false),
        DOUBLE_PERMUTING_BRIDGE(2, false),
        BRANCHED_PERMUTING_BRIDGE(3, true);

        private final int bridgeCount;
        private final boolean branched;

        Shape(int bridgeCount, boolean branched) {
            this.bridgeCount = bridgeCount;
            this.branched = branched;
        }

        public int bridgeCount() {
            return bridgeCount;
        }

        public boolean branched() {
            return branched;
        }
    }

    private static void requireIdentifier(String symbol) {
        Objects.requireNonNull(symbol, "bridgeSymbol");
        if (!symbol.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw new IllegalArgumentException(
                    "JNI proxy bridge symbol must be a C identifier");
        }
    }
}
