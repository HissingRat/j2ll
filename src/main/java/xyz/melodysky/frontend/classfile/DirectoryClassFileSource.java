package xyz.melodysky.frontend.classfile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

public final class DirectoryClassFileSource implements ClassFileSource {
    private final Path root;

    public DirectoryClassFileSource(Path root) {
        this.root = Objects.requireNonNull(root, "root");
    }

    @Override
    public String description() {
        return root.toString();
    }

    @Override
    public List<ClassFileEntry> entries() throws IOException {
        try (var paths = Files.walk(root)) {
            List<ClassFileEntry> entries = paths
                    .filter(Files::isRegularFile)
                    .filter(path -> ClassFileEntries.isClassEntry(path.getFileName().toString()))
                    .map(this::entryFor)
                    .toList();
            return ClassFileEntries.stableSorted(entries);
        }
    }

    private ClassFileEntry entryFor(Path path) {
        String entryName = root.relativize(path).toString().replace('\\', '/');
        try {
            return new ClassFileEntry(entryName, Files.readAllBytes(path), path.toString());
        } catch (IOException exception) {
            throw new UncheckedClassFileReadException(path, exception);
        }
    }

    private static final class UncheckedClassFileReadException extends RuntimeException {
        private UncheckedClassFileReadException(Path path, IOException cause) {
            super("failed to read class file " + path, cause);
        }
    }
}
