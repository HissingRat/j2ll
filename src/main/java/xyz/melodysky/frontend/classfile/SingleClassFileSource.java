package xyz.melodysky.frontend.classfile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

public final class SingleClassFileSource implements ClassFileSource {
    private final Path classFile;

    public SingleClassFileSource(Path classFile) {
        this.classFile = Objects.requireNonNull(classFile, "classFile");
    }

    @Override
    public String description() {
        return classFile.toString();
    }

    @Override
    public List<ClassFileEntry> entries() throws IOException {
        return List.of(new ClassFileEntry(
                classFile.getFileName().toString(),
                Files.readAllBytes(classFile),
                classFile.toString()));
    }
}
