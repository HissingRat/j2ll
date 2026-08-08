package xyz.melodysky.cli;

import xyz.melodysky.config.IntermediatesConfig;
import xyz.melodysky.config.ResolvedConfig;

/** Applies per-invocation CLI modifiers without mutating the user's JSON configuration. */
final class CliConfigOverrides {
    ResolvedConfig applyDebug(ResolvedConfig config, boolean debug) {
        if (!debug) {
            return config;
        }
        return new ResolvedConfig(
                config.schemaVersion(),
                config.jarFile(),
                config.classPath(),
                config.javaHome(),
                config.runtimeImage(),
                config.worldModel(),
                config.outputDirectory(),
                config.whiteList(),
                config.blackList(),
                config.target(),
                config.targets(),
                config.embeddedLibraryDirectory(),
                config.signaturePolicy(),
                config.signing(),
                new IntermediatesConfig(true, true, true, true, true),
                config.protection(),
                true);
    }
}
