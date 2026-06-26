package xyz.melodysky.packaging;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Comparator;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;

public final class JarRepackager {
    public void write(Path inputJar, Path outputJar, Map<String, byte[]> rewrittenEntries) throws IOException {
        write(inputJar, outputJar, rewrittenEntries, Map.of());
    }

    public void write(
            Path inputJar,
            Path outputJar,
            Map<String, byte[]> rewrittenEntries,
            Map<String, byte[]> addedEntries) throws IOException {
        Files.createDirectories(outputJar.getParent());
        try (JarFile jarFile = new JarFile(inputJar.toFile());
                JarOutputStream output = new JarOutputStream(Files.newOutputStream(outputJar))) {
            Set<String> writtenEntries = new HashSet<>();
            for (JarEntry inputEntry : jarFile.stream()
                    .sorted(Comparator.comparing(JarEntry::getName))
                    .toList()) {
                if (inputEntry.isDirectory()) {
                    continue;
                }
                JarEntry outputEntry = new JarEntry(inputEntry.getName());
                outputEntry.setTime(0L);
                output.putNextEntry(outputEntry);
                byte[] rewritten = rewrittenEntries.get(inputEntry.getName());
                if (rewritten != null) {
                    output.write(rewritten);
                } else {
                    try (InputStream input = jarFile.getInputStream(inputEntry)) {
                        input.transferTo(output);
                    }
                }
                output.closeEntry();
                writtenEntries.add(inputEntry.getName());
            }
            for (Map.Entry<String, byte[]> entry : addedEntries.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .toList()) {
                if (writtenEntries.contains(entry.getKey()) || isForbiddenPlainFallbackClass(entry.getKey())) {
                    continue;
                }
                JarEntry outputEntry = new JarEntry(entry.getKey());
                outputEntry.setTime(0L);
                output.putNextEntry(outputEntry);
                output.write(entry.getValue());
                output.closeEntry();
            }
        }
    }

    private boolean isForbiddenPlainFallbackClass(String entryName) {
        return entryName.startsWith("j2ll/generated/fallback/") && entryName.endsWith(".class");
    }
}
