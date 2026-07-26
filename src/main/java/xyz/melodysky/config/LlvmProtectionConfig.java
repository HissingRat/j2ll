package xyz.melodysky.config;

public record LlvmProtectionConfig(
        boolean enabled,
        boolean nameObfuscation,
        boolean opaquePredicates,
        boolean blockLayoutPerturbation,
        boolean indirectCalls,
        boolean globalLayout) {}
