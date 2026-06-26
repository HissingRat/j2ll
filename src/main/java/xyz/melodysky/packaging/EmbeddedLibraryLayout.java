package xyz.melodysky.packaging;

import xyz.melodysky.toolchain.TargetTriple;

public final class EmbeddedLibraryLayout {
    public String jarPath(String embeddedLibraryDirectory, TargetTriple target) {
        String directory = embeddedLibraryDirectory.endsWith("/")
                ? embeddedLibraryDirectory.substring(0, embeddedLibraryDirectory.length() - 1)
                : embeddedLibraryDirectory;
        return directory + "/" + target.libraryFileName();
    }
}
