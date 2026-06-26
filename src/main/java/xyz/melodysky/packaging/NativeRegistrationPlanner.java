package xyz.melodysky.packaging;

import java.util.List;
import java.util.Locale;
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
                .substring(0, 16);
        return "j2ll_" + safeSymbol(decision.registrationOwner())
                + "_" + safeSymbol(decision.method().name())
                + "_" + hash;
    }

    private String safeSymbol(String value) {
        StringBuilder result = new StringBuilder();
        for (int index = 0; index < value.length(); index++) {
            char ch = value.charAt(index);
            if ((ch >= 'a' && ch <= 'z')
                    || (ch >= 'A' && ch <= 'Z')
                    || (ch >= '0' && ch <= '9')) {
                result.append(ch);
            } else {
                result.append('_');
                if (ch > 127) {
                    result.append(Integer.toHexString(ch).toLowerCase(Locale.ROOT));
                    result.append('_');
                }
            }
        }
        return result.toString();
    }
}
