package xyz.melodysky.packaging;

import java.util.Objects;

public record RuntimeLoaderPlan(
        String embeddedLibraryDirectory,
        String internalName,
        boolean includeFallbackDefinition) {
    public RuntimeLoaderPlan {
        Objects.requireNonNull(embeddedLibraryDirectory, "embeddedLibraryDirectory");
        Objects.requireNonNull(internalName, "internalName");
    }

    public static RuntimeLoaderPlan create(
            String embeddedLibraryDirectory,
            boolean includeFallbackDefinition) {
        EmbeddedLibraryLayout layout = new EmbeddedLibraryLayout();
        String directory = layout.normalizedDirectory(embeddedLibraryDirectory);
        return new RuntimeLoaderPlan(
                directory,
                layout.loaderInternalName(directory),
                includeFallbackDefinition);
    }

    public String entryName() {
        return internalName + ".class";
    }
}
