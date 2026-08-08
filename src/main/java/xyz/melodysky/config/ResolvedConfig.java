package xyz.melodysky.config;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import xyz.melodysky.analysis.hierarchy.AnalysisWorld;
import xyz.melodysky.toolchain.TargetTriple;

public record ResolvedConfig(
        int schemaVersion,
        Path jarFile,
        List<Path> classPath,
        Path javaHome,
        Path runtimeImage,
        AnalysisWorld worldModel,
        Path outputDirectory,
        List<Selector> whiteList,
        List<Selector> blackList,
        TargetConfig target,
        List<TargetTriple> targets,
        String embeddedLibraryDirectory,
        SignaturePolicy signaturePolicy,
        SigningConfig signing,
        IntermediatesConfig intermediates,
        ProtectionConfig protection,
        boolean debugMode) {
    public ResolvedConfig {
        Objects.requireNonNull(jarFile, "jarFile");
        classPath = List.copyOf(Objects.requireNonNull(classPath, "classPath"));
        Objects.requireNonNull(worldModel, "worldModel");
        Objects.requireNonNull(outputDirectory, "outputDirectory");
        whiteList = List.copyOf(Objects.requireNonNull(whiteList, "whiteList"));
        blackList = List.copyOf(Objects.requireNonNull(blackList, "blackList"));
        Objects.requireNonNull(target, "target");
        targets = List.copyOf(Objects.requireNonNull(targets, "targets"));
        Objects.requireNonNull(embeddedLibraryDirectory, "embeddedLibraryDirectory");
        Objects.requireNonNull(signaturePolicy, "signaturePolicy");
        Objects.requireNonNull(intermediates, "intermediates");
        Objects.requireNonNull(protection, "protection");
    }

    public ResolvedConfig(
            int schemaVersion,
            Path jarFile,
            List<Path> classPath,
            Path javaHome,
            Path runtimeImage,
            AnalysisWorld worldModel,
            Path outputDirectory,
            List<Selector> whiteList,
            List<Selector> blackList,
            TargetConfig target,
            List<TargetTriple> targets,
            String embeddedLibraryDirectory,
            SignaturePolicy signaturePolicy,
            SigningConfig signing,
            IntermediatesConfig intermediates,
            ProtectionConfig protection) {
        this(
                schemaVersion,
                jarFile,
                classPath,
                javaHome,
                runtimeImage,
                worldModel,
                outputDirectory,
                whiteList,
                blackList,
                target,
                targets,
                embeddedLibraryDirectory,
                signaturePolicy,
                signing,
                intermediates,
                protection,
                false);
    }
}
