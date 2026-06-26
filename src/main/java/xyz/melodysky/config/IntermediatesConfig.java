package xyz.melodysky.config;

public record IntermediatesConfig(
        boolean enabled,
        boolean includeDebugDumps,
        boolean includePerClassIr,
        boolean includePerClassLlvm,
        boolean includePerClassC) {
}
