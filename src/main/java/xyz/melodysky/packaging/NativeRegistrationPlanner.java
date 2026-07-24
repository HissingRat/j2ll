package xyz.melodysky.packaging;

import java.util.List;
import xyz.melodysky.toolchain.ClassArtifactPath;

public final class NativeRegistrationPlanner {
    private final ClassArtifactPath artifactPath = new ClassArtifactPath();

    public NativeRegistrationPlan plan(List<MethodRewriteDecision> decisions) {
        return new NativeRegistrationPlan(decisions.stream()
                .filter(decision -> decision.strategy() != MethodRewriteStrategy.NOT_APPLICABLE)
                .map(this::entryFor)
                .toList());
    }

    private NativeRegistrationEntry entryFor(MethodRewriteDecision decision) {
        String registeredName = decision.generatedHelperName().orElse(decision.method().name());
        return new NativeRegistrationEntry(
                decision.registrationOwner(),
                registeredName,
                registeredDescriptor(decision),
                nativeSymbol(decision));
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
}
