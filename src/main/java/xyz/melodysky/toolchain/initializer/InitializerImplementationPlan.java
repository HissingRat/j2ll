package xyz.melodysky.toolchain.initializer;

import java.util.Objects;
import java.util.Optional;
import xyz.melodysky.ir.model.IrMethod;

/**
 * Shared final-plan artifact consumed by LLVM compilation and JAR rewriting.
 */
public record InitializerImplementationPlan(
        InitializerImplementationKind kind,
        IrMethod nativeBody,
        Optional<ConstructorPrefixPlan> constructorPrefix) {
    public InitializerImplementationPlan {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(nativeBody, "nativeBody");
        Objects.requireNonNull(constructorPrefix, "constructorPrefix");
        if (kind == InitializerImplementationKind.CONSTRUCTOR) {
            if (!nativeBody.name().equals("<init>") || constructorPrefix.isEmpty()) {
                throw new IllegalArgumentException(
                        "constructor initializer plan requires <init> IR and a Java prefix");
            }
        } else if (!nativeBody.name().equals("<clinit>") || constructorPrefix.isPresent()) {
            throw new IllegalArgumentException(
                    "class-initializer plan requires <clinit> IR without a constructor prefix");
        }
    }

    public static InitializerImplementationPlan constructor(
            IrMethod nativeBody,
            ConstructorPrefixPlan prefix) {
        return new InitializerImplementationPlan(
                InitializerImplementationKind.CONSTRUCTOR,
                nativeBody,
                Optional.of(prefix));
    }

    public static InitializerImplementationPlan classInitializer(IrMethod nativeBody) {
        return new InitializerImplementationPlan(
                InitializerImplementationKind.CLASS_INITIALIZER,
                nativeBody,
                Optional.empty());
    }

    public InitializerImplementationPlan withNativeBody(IrMethod replacement) {
        return new InitializerImplementationPlan(kind, replacement, constructorPrefix);
    }
}
