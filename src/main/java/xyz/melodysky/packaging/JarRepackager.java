package xyz.melodysky.packaging;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import xyz.melodysky.config.SignaturePolicy;

public final class JarRepackager {
    public JarPreservationReport inspectPreservation(Path inputJar) throws IOException {
        try (JarFile jarFile = new JarFile(inputJar.toFile(), false)) {
            Manifest manifest = jarFile.getManifest();
            boolean multiRelease = manifest != null
                    && "true".equalsIgnoreCase(manifest.getMainAttributes().getValue("Multi-Release"));
            int services = 0;
            int versioned = 0;
            boolean moduleInfo = false;
            for (JarEntry entry : jarFile.stream().filter(entry -> !entry.isDirectory()).toList()) {
                String name = entry.getName();
                if (name.startsWith("META-INF/services/")) {
                    services++;
                }
                if (name.equals("module-info.class")) {
                    moduleInfo = true;
                }
                if (name.startsWith("META-INF/versions/")) {
                    versioned++;
                }
            }
            return new JarPreservationReport(
                    manifest != null,
                    services,
                    moduleInfo,
                    multiRelease,
                    versioned,
                    "baseClassesOnlyPreserveVersionedEntries");
        }
    }

    public SignatureActionReport inspectSignature(Path inputJar, SignaturePolicy policy) throws IOException {
        List<String> signatureEntries = signatureEntries(inputJar);
        boolean signed = !signatureEntries.isEmpty();
        return switch (policy) {
            case FAIL -> signed
                    ? SignatureActionReport.fail(true, "signaturePolicy fail rejects signed input JAR")
                    : SignatureActionReport.none(false);
            case STRIP -> signed
                    ? SignatureActionReport.strip(true, signatureEntries)
                    : SignatureActionReport.none(false);
            case RESIGN -> signed
                    ? new SignatureActionReport(
                            "resign",
                            true,
                            signatureEntries,
                            "SIGNATURE_RESIGN_REQUIRED",
                            "signaturePolicy resign requires signing config and signer execution")
                    : new SignatureActionReport(
                            "resign",
                            false,
                            List.of(),
                            "SIGNATURE_RESIGN_REQUIRED",
                            "signaturePolicy resign requires signing config and signer execution");
        };
    }

    public void write(Path inputJar, Path outputJar, Map<String, byte[]> rewrittenEntries) throws IOException {
        write(inputJar, outputJar, rewrittenEntries, Map.of(), SignaturePolicy.FAIL);
    }

    public void write(
            Path inputJar,
            Path outputJar,
            Map<String, byte[]> rewrittenEntries,
            Map<String, byte[]> addedEntries) throws IOException {
        write(inputJar, outputJar, rewrittenEntries, addedEntries, SignaturePolicy.FAIL);
    }

    public void write(
            Path inputJar,
            Path outputJar,
            Map<String, byte[]> rewrittenEntries,
            Map<String, byte[]> addedEntries,
            SignaturePolicy signaturePolicy) throws IOException {
        Files.createDirectories(outputJar.getParent());
        try (JarFile jarFile = new JarFile(inputJar.toFile(), false);
                JarOutputStream output = new JarOutputStream(Files.newOutputStream(outputJar))) {
            Set<String> writtenEntries = new HashSet<>();
            for (JarEntry inputEntry : jarFile.stream()
                    .sorted(Comparator.comparing(JarEntry::getName))
                    .toList()) {
                if (inputEntry.isDirectory()) {
                    continue;
                }
                if ((signaturePolicy == SignaturePolicy.STRIP || signaturePolicy == SignaturePolicy.RESIGN)
                        && isSignatureEntry(inputEntry.getName())) {
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
                if (writtenEntries.contains(entry.getKey())) {
                    throw new IOException("added JAR entry collides with input entry: " + entry.getKey());
                }
                if (isForbiddenPlainFallbackClass(entry.getKey())) {
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

    public boolean isSigned(Path inputJar) throws IOException {
        return !signatureEntries(inputJar).isEmpty();
    }

    private List<String> signatureEntries(Path inputJar) throws IOException {
        ArrayList<String> entries = new ArrayList<>();
        try (JarFile jarFile = new JarFile(inputJar.toFile(), false)) {
            jarFile.stream()
                    .filter(entry -> !entry.isDirectory())
                    .map(JarEntry::getName)
                    .filter(this::isSignatureEntry)
                    .sorted()
                    .forEach(entries::add);
        }
        return List.copyOf(entries);
    }

    private boolean isSignatureEntry(String entryName) {
        if (!entryName.startsWith("META-INF/")) {
            return false;
        }
        String fileName = entryName.substring("META-INF/".length());
        return fileName.indexOf('/') < 0
                && (fileName.endsWith(".SF")
                        || fileName.endsWith(".RSA")
                        || fileName.endsWith(".DSA")
                        || fileName.endsWith(".EC"));
    }

    private boolean isForbiddenPlainFallbackClass(String entryName) {
        return entryName.endsWith(".class")
                && (entryName.startsWith("j2ll/generated/fallback/")
                        || entryName.contains("/J2llFallback$")
                        || entryName.startsWith("J2llFallback$"));
    }
}
