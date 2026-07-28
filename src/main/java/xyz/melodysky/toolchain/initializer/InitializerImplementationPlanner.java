package xyz.melodysky.toolchain.initializer;

import java.util.Optional;
import xyz.melodysky.ir.model.IrMethod;
import xyz.melodysky.packaging.MethodRewriteDecision;
import xyz.melodysky.packaging.MethodRewriteStrategy;

/**
 * Builds a verifier-safe Java/native boundary for {@code <init>} and
 * {@code <clinit>}.
 *
 * <p>Constructor support is deliberately fail-closed. The Java prefix must
 * have linear control flow and no exception table, and the SSA initialization
 * call must be in the entry block. This covers javac's ordinary constructor
 * shape while avoiding an unsound attempt to move uninitialized {@code this}
 * into native code.</p>
 */
public final class InitializerImplementationPlanner {
    private final ConstructorPrefixAnalyzer prefixAnalyzer =
            new ConstructorPrefixAnalyzer();
    private final ConstructorIrBodySplitter bodySplitter =
            new ConstructorIrBodySplitter();

    public Optional<InitializerImplementationPlan> plan(
            MethodRewriteDecision decision,
            IrMethod source) {
        if (decision.strategy() == MethodRewriteStrategy.CLASS_INITIALIZER_STUB) {
            return classInitializer(decision, source);
        }
        if (decision.strategy() == MethodRewriteStrategy.CONSTRUCTOR_STUB) {
            return constructor(decision, source);
        }
        return Optional.empty();
    }

    private Optional<InitializerImplementationPlan> classInitializer(
            MethodRewriteDecision decision,
            IrMethod source) {
        if (!decision.method().name().equals("<clinit>")
                || !decision.method().descriptor().equals("()V")
                || !source.name().equals("<clinit>")
                || !source.descriptor().equals("()V")
                || !source.parameters().isEmpty()
                || source.blocks().isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(InitializerImplementationPlan.classInitializer(source));
    }

    private Optional<InitializerImplementationPlan> constructor(
            MethodRewriteDecision decision,
            IrMethod source) {
        if (!decision.method().name().equals("<init>")
                || !source.name().equals("<init>")
                || source.parameters().isEmpty()
                || source.parameters().get(0).type() != xyz.melodysky.ir.model.IrType.REFERENCE
                || source.blocks().isEmpty()
                || !decision.method().exceptionHandlers().isEmpty()
                || !decision.method().methodNode().tryCatchBlocks.isEmpty()) {
            return Optional.empty();
        }

        Optional<ConstructorPrefixPlan> prefix = prefixAnalyzer.analyze(decision);
        if (prefix.isEmpty()) {
            return Optional.empty();
        }
        return bodySplitter.split(decision, source, prefix.orElseThrow())
                .map(body -> InitializerImplementationPlan.constructor(
                        body,
                        prefix.orElseThrow()));
    }
}
