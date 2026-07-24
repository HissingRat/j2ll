package xyz.melodysky.packaging;

import xyz.melodysky.toolchain.TargetTriple;

public final class EmbeddedLibraryLayout {
    public String jarPath(String embeddedLibraryDirectory, TargetTriple target) {
        return normalizedDirectory(embeddedLibraryDirectory) + "/" + target.libraryFileName();
    }

    public String loaderInternalName(String embeddedLibraryDirectory) {
        return normalizedDirectory(embeddedLibraryDirectory) + "/Loader";
    }

    public String normalizedDirectory(String embeddedLibraryDirectory) {
        return embeddedLibraryDirectory.endsWith("/")
                ? embeddedLibraryDirectory.substring(0, embeddedLibraryDirectory.length() - 1)
                : embeddedLibraryDirectory;
    }
}
