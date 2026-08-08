package xyz.melodysky.toolchain.nativetext;

import java.util.List;
import java.util.Objects;

/**
 * Proven lifetime and decode placement for one function-local native-text
 * record.
 *
 * <p>A single use and a tuple whose components are all direct arguments of
 * one call have one statically identified decode point. Only records reused
 * across calls or assignments need a lazy-ready guard.</p>
 */
record NativeTextUsePlan(
        Lifetime lifetime,
        int literalUseCount,
        int decodeLiteralIndex,
        int argumentListStart) {
    NativeTextUsePlan {
        Objects.requireNonNull(lifetime, "lifetime");
        if (literalUseCount <= 0) {
            throw new IllegalArgumentException(
                    "native-text use count must be positive");
        }
        if (lifetime == Lifetime.REUSED) {
            if (decodeLiteralIndex != -1 || argumentListStart != -1) {
                throw new IllegalArgumentException(
                        "reused native text must decode lazily at each use");
            }
        } else if (decodeLiteralIndex < 0) {
            throw new IllegalArgumentException(
                    "single-decode native text requires a decode literal");
        }
        if (lifetime == Lifetime.DIRECT_CALL_ARGUMENTS
                && argumentListStart < 0) {
            throw new IllegalArgumentException(
                    "direct-call native text requires an argument-list identity");
        }
        if (lifetime != Lifetime.DIRECT_CALL_ARGUMENTS
                && argumentListStart != -1) {
            throw new IllegalArgumentException(
                    "only a direct-call native-text plan may retain an argument-list identity");
        }
    }

    static NativeTextUsePlan plan(
            List<Integer> literalIndexes,
            List<GeneratedCFragmentLexer.StringLiteral> literals) {
        Objects.requireNonNull(literalIndexes, "literalIndexes");
        Objects.requireNonNull(literals, "literals");
        if (literalIndexes.isEmpty()) {
            throw new IllegalArgumentException(
                    "native-text use plan requires at least one literal");
        }
        int decodeLiteral = literalIndexes.get(0);
        if (literalIndexes.size() == 1) {
            return new NativeTextUsePlan(
                    Lifetime.SINGLE_USE,
                    1,
                    decodeLiteral,
                    -1);
        }
        GeneratedCFragmentLexer.StringLiteral first =
                literal(literals, decodeLiteral);
        int call = first.argumentListStart();
        boolean oneDirectCall = call >= 0
                && first.use()
                        == GeneratedCFragmentLexer.LiteralUse.POINTER_ARGUMENT;
        for (int literalIndex : literalIndexes) {
            GeneratedCFragmentLexer.StringLiteral literal =
                    literal(literals, literalIndex);
            oneDirectCall &= literal.use()
                            == GeneratedCFragmentLexer.LiteralUse.POINTER_ARGUMENT
                    && literal.argumentListStart() == call;
        }
        if (oneDirectCall) {
            return new NativeTextUsePlan(
                    Lifetime.DIRECT_CALL_ARGUMENTS,
                    literalIndexes.size(),
                    decodeLiteral,
                    call);
        }
        return new NativeTextUsePlan(
                Lifetime.REUSED,
                literalIndexes.size(),
                -1,
                -1);
    }

    boolean requiresReadyGuard() {
        return lifetime == Lifetime.REUSED;
    }

    boolean decodesAt(int literalIndex) {
        return requiresReadyGuard() || decodeLiteralIndex == literalIndex;
    }

    private static GeneratedCFragmentLexer.StringLiteral literal(
            List<GeneratedCFragmentLexer.StringLiteral> literals,
            int index) {
        if (index < 0 || index >= literals.size()) {
            throw new IllegalArgumentException(
                    "native-text literal index is outside the scan result");
        }
        return literals.get(index);
    }

    enum Lifetime {
        SINGLE_USE,
        DIRECT_CALL_ARGUMENTS,
        REUSED
    }
}
