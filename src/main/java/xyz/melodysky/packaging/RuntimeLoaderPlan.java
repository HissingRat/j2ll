package xyz.melodysky.packaging;

import java.util.Objects;

public record RuntimeLoaderPlan(
        String embeddedLibraryDirectory,
        String internalName,
        boolean includeFallbackDefinition,
        int referenceSidecarSize) {
    public RuntimeLoaderPlan {
        Objects.requireNonNull(embeddedLibraryDirectory, "embeddedLibraryDirectory");
        Objects.requireNonNull(internalName, "internalName");
        if (referenceSidecarSize < 0) {
            throw new IllegalArgumentException("referenceSidecarSize must be non-negative");
        }
    }

    public static RuntimeLoaderPlan create(
            String embeddedLibraryDirectory,
            boolean includeFallbackDefinition) {
        EmbeddedLibraryLayout layout = new EmbeddedLibraryLayout();
        String directory = layout.normalizedDirectory(embeddedLibraryDirectory);
        return new RuntimeLoaderPlan(
                directory,
                layout.loaderInternalName(directory),
                includeFallbackDefinition,
                0);
    }

    public static RuntimeLoaderPlan create(
            String embeddedLibraryDirectory,
            boolean includeFallbackDefinition,
            int referenceSidecarSize) {
        EmbeddedLibraryLayout layout = new EmbeddedLibraryLayout();
        String directory = layout.normalizedDirectory(embeddedLibraryDirectory);
        return new RuntimeLoaderPlan(
                directory,
                layout.loaderInternalName(directory),
                includeFallbackDefinition,
                referenceSidecarSize);
    }

    public boolean includeReferenceSidecar() {
        return referenceSidecarSize > 0;
    }

    public String entryName() {
        return internalName + ".class";
    }
}
