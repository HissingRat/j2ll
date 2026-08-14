package xyz.melodysky.packaging;

import java.util.List;
import java.util.Optional;
import xyz.melodysky.frontend.classfile.ParsedClass;
import xyz.melodysky.frontend.classfile.ParsedMethod;
import xyz.melodysky.ir.pass.protection.ProtectionRandom;

public final class MethodRewritePlanner {
    private static final long COMPATIBILITY_SEED = 0L;

    public List<MethodRewriteDecision> planClass(ParsedClass parsedClass) {
        return planClass(parsedClass, COMPATIBILITY_SEED);
    }

    public List<MethodRewriteDecision> planClass(
            ParsedClass parsedClass,
            long buildScopedSeed) {
        return parsedClass.methods().stream()
                .map(method -> planMethod(parsedClass, method, buildScopedSeed))
                .toList();
    }

    public MethodRewriteDecision planMethod(ParsedClass parsedClass, ParsedMethod method) {
        return planMethod(parsedClass, method, COMPATIBILITY_SEED);
    }

    public MethodRewriteDecision planMethod(
            ParsedClass parsedClass,
            ParsedMethod method,
            long buildScopedSeed) {
        Optional<String> notApplicableReason = notApplicableReason(parsedClass, method);
        if (notApplicableReason.isPresent()) {
            return new MethodRewriteDecision(
                    method,
                    MethodRewriteStrategy.NOT_APPLICABLE,
                    parsedClass.internalName(),
                    Optional.empty(),
                    notApplicableReason.orElseThrow());
        }
        GeneratedInitializerCarrierName initializerNames =
                new GeneratedInitializerCarrierName(buildScopedSeed);
        if (method.name().equals("<init>")) {
            return new MethodRewriteDecision(
                    method,
                    MethodRewriteStrategy.CONSTRUCTOR_STUB,
                    parsedClass.internalName(),
                    Optional.of(initializerNames.constructor(method)),
                    null);
        }
        if (method.name().equals("<clinit>")) {
            return new MethodRewriteDecision(
                    method,
                    MethodRewriteStrategy.CLASS_INITIALIZER_STUB,
                    parsedClass.internalName(),
                    Optional.of(initializerNames.classInitializer(method)),
                    null);
        }
        if (parsedClass.isInterface()) {
            ProtectionRandom random = new ProtectionRandom(buildScopedSeed);
            String helper = helperOwner(random, parsedClass);
            return new MethodRewriteDecision(
                    method,
                    MethodRewriteStrategy.INTERFACE_METHOD_STUB,
                    helper,
                    Optional.of(helperMethodName(random, method)),
                    null);
        }
        return new MethodRewriteDecision(
                method,
                MethodRewriteStrategy.NATIVE_ORIGINAL,
                parsedClass.internalName(),
                Optional.empty(),
                null);
    }

    private Optional<String> notApplicableReason(ParsedClass parsedClass, ParsedMethod method) {
        if (method.accessFlags().isNative()) {
            return Optional.of("ALREADY_NATIVE");
        }
        if (method.accessFlags().isAbstract()) {
            return Optional.of("ABSTRACT_OR_NO_CODE");
        }
        if (!method.hasCode() && parsedClass.isInterface()) {
            return Optional.of("INTERFACE_NO_CODE");
        }
        if (!method.hasCode()) {
            return Optional.of("NO_CODE");
        }
        return Optional.empty();
    }

    private String helperOwner(
            ProtectionRandom random,
            ParsedClass parsedClass) {
        return "j2ll/generated/i_" + random.token(
                "INTERFACE_METHOD_HELPER_OWNER",
                parsedClass.internalName(),
                32);
    }

    private String helperMethodName(
            ProtectionRandom random,
            ParsedMethod method) {
        return "j2ll_m_" + random.token(
                "INTERFACE_METHOD_HELPER_METHOD",
                methodIdentity(method),
                32);
    }

    private String methodIdentity(ParsedMethod method) {
        return method.owner()
                + "#"
                + method.name()
                + "!"
                + method.descriptor();
    }

}
