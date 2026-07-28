package xyz.melodysky.toolchain;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/**
 * Build-scoped call topology between one JNI wrapper and its LLVM body.
 *
 * <p>Every bridge only permutes the existing native parameters. No shape
 * adds persistent function-pointer state, JNI work, reference ownership or
 * exception behavior.</p>
 */
record NativeLocalAbiPlan(
        Shape shape,
        int parameterCount,
        List<String> bridgeSymbols,
        List<List<Integer>> parameterOrders,
        int branchSalt) {
    NativeLocalAbiPlan {
        Objects.requireNonNull(shape, "shape");
        if (parameterCount < 0) {
            throw new IllegalArgumentException(
                    "parameterCount must not be negative");
        }
        bridgeSymbols = List.copyOf(
                Objects.requireNonNull(
                        bridgeSymbols,
                        "bridgeSymbols"));
        parameterOrders = Objects.requireNonNull(
                        parameterOrders,
                        "parameterOrders")
                .stream()
                .map(List::copyOf)
                .toList();
        if (bridgeSymbols.size() != shape.bridgeCount()
                || parameterOrders.size() != shape.bridgeCount()) {
            throw new IllegalArgumentException(
                    "local ABI shape must have the planned bridge count");
        }
        if (!shape.branched() && branchSalt != 0) {
            throw new IllegalArgumentException(
                    "only a branched local ABI shape may carry a branch salt");
        }
        HashSet<String> symbols = new HashSet<>();
        for (String symbol : bridgeSymbols) {
            requireIdentifier(symbol, "bridgeSymbol");
            if (!symbols.add(symbol)) {
                throw new IllegalArgumentException(
                        "local ABI bridge symbols must be unique");
            }
        }
        for (List<Integer> order : parameterOrders) {
            if (order.size() != parameterCount) {
                throw new IllegalArgumentException(
                        "local ABI parameter order has the wrong arity");
            }
            HashSet<Integer> indices = new HashSet<>();
            for (int index : order) {
                if (index < 0
                        || index >= parameterCount
                        || !indices.add(index)) {
                    throw new IllegalArgumentException(
                            "local ABI parameter order must be a permutation");
                }
            }
        }
    }

    private static void requireIdentifier(String value, String label) {
        Objects.requireNonNull(value, label);
        if (!value.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw new IllegalArgumentException(
                    label + " must be a C identifier");
        }
    }

    enum Shape {
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

        int bridgeCount() {
            return bridgeCount;
        }

        boolean branched() {
            return branched;
        }
    }
}
