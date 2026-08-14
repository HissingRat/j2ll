package xyz.melodysky.toolchain;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Generated-C gate proving that LLVM JNI proxies have only an extern and the
 * registration reference in C. Their semantic bodies and local-ABI bridges
 * must remain exclusively in the final LLVM model.
 */
final class NativeJniEntryGeneratedCVerifier {
    private final xyz.melodysky.runtime.jni.JniTypeMapper typeMapper =
            new xyz.melodysky.runtime.jni.JniTypeMapper();
    List<String> verify(
            NativeImplementationPlan implementationPlan,
            String source) {
        Objects.requireNonNull(implementationPlan, "implementationPlan");
        Objects.requireNonNull(source, "source");
        ArrayList<String> issues = new ArrayList<>();
        for (NativeMethodImplementation implementation
                : implementationPlan.registeredImplementations()) {
            NativeJniEntryPlan entryPlan = implementationPlan
                    .jniEntryPlanFor(implementation.methodKey());
            String physicalSymbol = entryPlan.functionSymbol();
            if (entryPlan.llvmJniProxy()) {
                verifyProxy(
                        implementation,
                        entryPlan,
                        source,
                        issues);
            } else {
                verifyWrapped(
                        implementation,
                        physicalSymbol,
                        source,
                        issues);
            }
        }
        return issues.stream().sorted().toList();
    }

    private void verifyProxy(
            NativeMethodImplementation implementation,
            NativeJniEntryPlan entryPlan,
            String source,
            List<String> issues) {
        String methodKey = implementation.methodKey();
        String physicalSymbol = entryPlan.functionSymbol();
        var method = implementation.decision().method();
        String expectedPrototype = typeMapper.methodDescriptor(
                        method.owner(),
                        method.name(),
                        method.descriptor(),
                        method.accessFlags().isStatic())
                .cPrototype(physicalSymbol);
        if (!hasExactExternDeclaration(source, expectedPrototype)) {
            issues.add(methodKey
                    + ":LLVM_JNI_PROXY_C_EXTERN_MISSING");
        }
        if (!hasRegistrationReference(source, physicalSymbol)) {
            issues.add(methodKey
                    + ":LLVM_JNI_PROXY_REGISTRATION_REFERENCE_MISSING");
        }
        if (hasFunctionDefinition(source, physicalSymbol)) {
            issues.add(methodKey
                    + ":LLVM_JNI_PROXY_C_DEFINITION_RESIDUAL");
        }
        if (identifierOccurrences(source, physicalSymbol) != 2L) {
            issues.add(methodKey
                    + ":LLVM_JNI_PROXY_UNEXPECTED_C_REFERENCE_SURFACE");
        }
        String logicalWrapper = implementation.entry().nativeSymbol();
        if (identifierOccurrences(source, logicalWrapper) != 0L) {
            issues.add(methodKey
                    + ":LLVM_JNI_PROXY_LOGICAL_WRAPPER_RESIDUAL");
        }
        String semanticBody = entryPlan.semanticBodySymbol().orElseThrow();
        if (identifierOccurrences(source, semanticBody) != 0L) {
            issues.add(methodKey
                    + ":LLVM_JNI_PROXY_SEMANTIC_BODY_C_REFERENCE_RESIDUAL");
        }
        for (String bridge : entryPlan.topology()
                .orElseThrow()
                .bridgeSymbols()) {
            if (identifierOccurrences(source, bridge) != 0L) {
                issues.add(methodKey
                        + ":LLVM_JNI_PROXY_BRIDGE_C_REFERENCE_RESIDUAL");
            }
        }
    }

    private void verifyWrapped(
            NativeMethodImplementation implementation,
            String physicalSymbol,
            String source,
            List<String> issues) {
        String methodKey = implementation.methodKey();
        if (!hasFunctionDefinition(source, physicalSymbol)) {
            issues.add(methodKey
                    + ":WRAPPED_JNI_ENTRY_C_DEFINITION_MISSING");
        }
        if (!hasRegistrationReference(source, physicalSymbol)) {
            issues.add(methodKey
                    + ":WRAPPED_JNI_ENTRY_REGISTRATION_REFERENCE_MISSING");
        }
    }

    private boolean hasExactExternDeclaration(
            String source,
            String prototype) {
        return Pattern.compile(
                        "(?m)^"
                                + Pattern.quote("extern " + prototype + ";")
                                + "\\s*$")
                .matcher(source)
                .find();
    }

    private boolean hasFunctionDefinition(String source, String symbol) {
        return Pattern.compile(
                        "(?m)^\\s*static\\s+[^;\\r\\n]*"
                                + identifier(symbol)
                                + "\\s*\\([^;{}]*\\)\\s*\\{")
                .matcher(source)
                .find();
    }

    private boolean hasRegistrationReference(String source, String symbol) {
        return Pattern.compile(
                        "\\.fnPtr\\s*=\\s*\\(void\\s*\\*\\)\\s*"
                                + identifier(symbol)
                                + "\\s*;")
                .matcher(source)
                .find();
    }

    private long identifierOccurrences(String source, String symbol) {
        return Pattern.compile(identifier(symbol))
                .matcher(source)
                .results()
                .count();
    }

    private String identifier(String symbol) {
        return "(?<![A-Za-z0-9_])"
                + Pattern.quote(symbol)
                + "(?![A-Za-z0-9_])";
    }
}
