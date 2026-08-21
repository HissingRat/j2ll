package xyz.melodysky.toolchain;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import xyz.melodysky.ir.model.IrMethod;
import xyz.melodysky.packaging.MethodRewriteDecision;
import xyz.melodysky.packaging.MethodRewriteStrategy;
import xyz.melodysky.runtime.RuntimeTokenMapper;
import xyz.melodysky.toolchain.initializer.InitializerImplementationPlan;
import xyz.melodysky.toolchain.initializer.InitializerImplementationPlanner;

/** Selects the effective native IR body, including verifier-safe initializers. */
final class NativeImplementationBodyPlanner {
    private final InitializerImplementationPlanner initializerPlanner;

    NativeImplementationBodyPlanner(RuntimeTokenMapper runtimeTokens) {
        initializerPlanner = new InitializerImplementationPlanner(runtimeTokens);
    }

    Map<String, IrMethod> nativeBodies(
            Map<String, MethodRewriteDecision> decisions,
            Map<String, IrMethod> irMethods,
            Map<String, InitializerImplementationPlan> initializerPlans,
            Map<String, InitializerImplementationPlan> preparedInitializerPlans) {
        LinkedHashMap<String, IrMethod> bodies = new LinkedHashMap<>();
        decisions.forEach((methodKey, decision) -> {
            IrMethod source = irMethods.get(methodKey);
            if (source == null) {
                return;
            }
            if (isInitializer(decision)) {
                InitializerImplementationPlan prepared =
                        preparedInitializerPlans.get(methodKey);
                if (prepared != null) {
                    InitializerImplementationPlan current =
                            prepared.withNativeBody(source);
                    initializerPlans.put(methodKey, current);
                    bodies.put(methodKey, source);
                    return;
                }
                initializerPlanner.plan(decision, source).ifPresent(plan -> {
                    initializerPlans.put(methodKey, plan);
                    bodies.put(methodKey, plan.nativeBody());
                });
                return;
            }
            bodies.put(methodKey, source);
        });
        return java.util.Collections.unmodifiableMap(bodies);
    }

    Optional<InitializerImplementationPlan> initializerPlan(
            MethodRewriteDecision decision,
            IrMethod method) {
        return initializerPlanner.plan(decision, method);
    }

    private boolean isInitializer(MethodRewriteDecision decision) {
        return decision.strategy() == MethodRewriteStrategy.CONSTRUCTOR_STUB
                || decision.strategy()
                        == MethodRewriteStrategy.CLASS_INITIALIZER_STUB;
    }
}
