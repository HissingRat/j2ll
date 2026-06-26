package xyz.melodysky.frontend.classfile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

public final class JarClassFileSource implements ClassFileSource {
    private final Path jarPath;

    public JarClassFileSource(Path jarPath) {
        this.jarPath = Objects.requireNonNull(jarPath, "jarPath");
    }

    @Override
    public String description() {
        return jarPath.toString();
    }

    @Override
    public List<ClassFileEntry> entries() throws IOException {
        ArrayList<ClassFileEntry> entries = new ArrayList<>();
        try (JarFile jarFile = new JarFile(jarPath.toFile())) {
            for (JarEntry jarEntry : jarFile.stream().toList()) {
                if (!ClassFileEntries.isClassEntry(jarEntry.getName())) {
                    continue;
                }
                try (InputStream input = jarFile.getInputStream(jarEntry)) {
                    entries.add(new ClassFileEntry(
                            jarEntry.getName(),
                            input.readAllBytes(),
                            jarPath + "!" + jarEntry.getName()));
                }
            }
        }
        return ClassFileEntries.stableSorted(entries);
    }
}
