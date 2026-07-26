package xyz.melodysky.ir.pass.protection;

import java.util.Objects;
import java.util.function.Function;
import xyz.melodysky.ir.model.IrMethod;

/**
 * A native/compiler-internal outlined function.
 *
 * <p>This is intentionally not an {@code IrClass} member. The LLVM backend may consume
 * it, while Java packaging and RegisterNatives planning must not.</p>
 */
public record OutlinedMethodHelper(MethodSplitPlan plan, IrMethod body) {
    public OutlinedMethodHelper {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(body, "body");
        if (!body.name().equals(plan.helperName())
                || !body.descriptor().equals(plan.helperDescriptor())
                || !body.parameters().equals(plan.liveIns())
                || body.returnType() != plan.liveOut().type()) {
            throw new IllegalArgumentException("outlined helper body does not match its split plan");
        }
    }

    public String methodKey() {
        return body.methodKey();
    }

    /**
     * Resolves the symbol through the exact name-mangling function used by the
     * LLVM backend. The split-plan token is not itself an emitted symbol.
     */
    public String emittedFunctionSymbol(Function<String, String> functionNameResolver) {
        Objects.requireNonNull(functionNameResolver, "functionNameResolver");
        String symbol = Objects.requireNonNull(
                functionNameResolver.apply(methodKey()),
                "emitted function symbol");
        if (symbol.isBlank()) {
            throw new IllegalArgumentException("emitted function symbol must not be blank");
        }
        return symbol;
    }
}
