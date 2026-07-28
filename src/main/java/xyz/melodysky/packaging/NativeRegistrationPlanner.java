package xyz.melodysky.packaging;

import java.util.List;
import java.util.function.Function;
import xyz.melodysky.ir.pass.protection.ProtectionRandom;
import xyz.melodysky.toolchain.ClassArtifactPath;

public final class NativeRegistrationPlanner {
    private final ClassArtifactPath artifactPath = new ClassArtifactPath();

    public NativeRegistrationPlan plan(List<MethodRewriteDecision> decisions) {
        return plan(decisions, this::nativeSymbol);
    }

    /**
     * Plans build-scoped wrapper symbols while preserving the registration
     * semantics and canonical plan ordering.
     */
    public NativeRegistrationPlan plan(
            List<MethodRewriteDecision> decisions,
            long buildScopedSeed) {
        ProtectionRandom random = new ProtectionRandom(buildScopedSeed);
        return plan(
                decisions,
                decision -> "j2ll_n_" + random.token(
                        "NATIVE_REGISTRATION_WRAPPER_SYMBOL",
                        methodIdentity(decision),
                        32));
    }

    private NativeRegistrationPlan plan(
            List<MethodRewriteDecision> decisions,
            Function<MethodRewriteDecision, String> symbolFor) {
        return new NativeRegistrationPlan(decisions.stream()
                .filter(decision -> decision.strategy() != MethodRewriteStrategy.NOT_APPLICABLE)
                .map(decision -> entryFor(decision, symbolFor.apply(decision)))
                .toList());
    }

    private NativeRegistrationEntry entryFor(
            MethodRewriteDecision decision,
            String nativeSymbol) {
        String registeredName = decision.generatedHelperName().orElse(decision.method().name());
        return new NativeRegistrationEntry(
                decision.registrationOwner(),
                registeredName,
                registeredDescriptor(decision),
                nativeSymbol);
    }

    private String registeredDescriptor(MethodRewriteDecision decision) {
        if (decision.strategy() == MethodRewriteStrategy.CONSTRUCTOR_STUB) {
            String descriptor = decision.method().descriptor();
            int close = descriptor.indexOf(')');
            return "(L" + decision.method().owner() + ";" + descriptor.substring(1, close) + ")V";
        }
        if (decision.strategy() == MethodRewriteStrategy.CLASS_INITIALIZER_STUB) {
            return "()V";
        }
        return decision.method().descriptor();
    }

    private String nativeSymbol(MethodRewriteDecision decision) {
        String hash = artifactPath
                .methodHash(decision.method().owner(), decision.method().name(), decision.method().descriptor())
                .substring(0, 32);
        return "j2ll_n_" + hash;
    }

    private String methodIdentity(MethodRewriteDecision decision) {
        return decision.method().owner()
                + "#"
                + decision.method().name()
                + "!"
                + decision.method().descriptor();
    }
}
