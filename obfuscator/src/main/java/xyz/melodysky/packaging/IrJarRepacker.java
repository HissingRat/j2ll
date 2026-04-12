package xyz.melodysky.packaging;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import xyz.melodysky.toolchain.IrNativeBuildDriver;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.jar.*;

public class IrJarRepacker {

    private final LoaderAssembler loaderAssembler;
    private final LoaderInitClassRewriter loaderInitClassRewriter;
    private final NativeMethodClassRewriter nativeMethodClassRewriter;

    public IrJarRepacker() {
        this(new LoaderAssembler(), new LoaderInitClassRewriter(), new NativeMethodClassRewriter());
    }

    public IrJarRepacker(LoaderAssembler loaderAssembler, LoaderInitClassRewriter loaderInitClassRewriter,
                         NativeMethodClassRewriter nativeMethodClassRewriter) {
        this.loaderAssembler = loaderAssembler;
        this.loaderInitClassRewriter = loaderInitClassRewriter;
        this.nativeMethodClassRewriter = nativeMethodClassRewriter;
    }

    public RepackResult repack(Path inputJar, Path outputJar, List<IrNativeBuildDriver.BuildArtifact> nativeArtifacts,
                               String requestedNativeDir, String plainLibName) throws IOException {
        return repack(inputJar, outputJar, nativeArtifacts, requestedNativeDir, plainLibName,
                NativeRegistrationPlan.empty());
    }

    public RepackResult repack(Path inputJar, Path outputJar, List<IrNativeBuildDriver.BuildArtifact> nativeArtifacts,
                               String requestedNativeDir, String plainLibName, Set<String> loaderHookClasses) throws IOException {
        return repack(inputJar, outputJar, nativeArtifacts, requestedNativeDir, plainLibName,
                loaderHookClasses.isEmpty() ? NativeRegistrationPlan.empty() : throwLegacyRegistrationPlanError());
    }

    public RepackResult repack(Path inputJar, Path outputJar, List<IrNativeBuildDriver.BuildArtifact> nativeArtifacts,
                               String requestedNativeDir, String plainLibName, Set<String> loaderHookClasses,
                               Map<String, Set<NativeMethodClassRewriter.MethodKey>> nativeMethodsByClass) throws IOException {
        if (!loaderHookClasses.isEmpty() || !nativeMethodsByClass.isEmpty()) {
            throwLegacyRegistrationPlanError();
        }
        return repack(inputJar, outputJar, nativeArtifacts, requestedNativeDir, plainLibName, NativeRegistrationPlan.empty());
    }

    public RepackResult repack(Path inputJar, Path outputJar, List<IrNativeBuildDriver.BuildArtifact> nativeArtifacts,
                               String requestedNativeDir, String plainLibName,
                               NativeRegistrationPlan registrationPlan) throws IOException {
        Files.createDirectories(outputJar.toAbsolutePath().getParent());

        String nativeDir = planNativeDir(inputJar, requestedNativeDir);
        try (JarFile jarFile = new JarFile(inputJar.toFile())) {
            int targetClassVersion = determineOutputClassVersion(jarFile);
            byte[] loaderClassBytes = loaderAssembler.createLoaderClass(nativeDir, plainLibName, targetClassVersion);
            String loaderEntryName = nativeDir + "/Loader.class";
            String loaderInternalName = nativeDir + "/Loader";

            try (JarOutputStream outputStream = new JarOutputStream(Files.newOutputStream(outputJar))) {
                copyOriginalEntries(jarFile, outputStream, loaderEntryName, nativeDir,
                        loaderInternalName, registrationPlan);
                writeEntry(outputStream, loaderEntryName, loaderClassBytes);
                for (IrNativeBuildDriver.BuildArtifact artifact : nativeArtifacts) {
                    writeEntry(outputStream, nativeDir + "/" + artifact.libraryFile().getFileName(), Files.readAllBytes(artifact.libraryFile()));
                }
            }

            return new RepackResult(outputJar, nativeDir, loaderEntryName);
        }
    }

    private int determineOutputClassVersion(JarFile jarFile) throws IOException {
        int maxVersion = Opcodes.V1_8;
        boolean sawClass = false;
        for (JarEntry entry : Collections.list(jarFile.entries())) {
            if (entry.isDirectory() || !entry.getName().endsWith(".class")) {
                continue;
            }
            sawClass = true;
            try (InputStream input = jarFile.getInputStream(entry)) {
                ClassReader classReader = new ClassReader(input);
                maxVersion = Math.max(maxVersion, classReader.readShort(6));
            }
        }
        return sawClass ? maxVersion : Opcodes.V1_8;
    }

    public String planNativeDir(Path inputJar, String requestedNativeDir) throws IOException {
        try (JarFile jarFile = new JarFile(inputJar.toFile())) {
            return chooseNativeDir(jarFile, requestedNativeDir);
        }
    }

    private String chooseNativeDir(JarFile jarFile, String requestedNativeDir) {
        String base = requestedNativeDir == null || requestedNativeDir.isBlank() ? "native0" : requestedNativeDir.trim();
        String candidate = base;
        int suffix = 1;
        while (entryExists(jarFile, candidate)) {
            candidate = base + "_" + suffix++;
        }
        return candidate;
    }

    private boolean entryExists(JarFile jarFile, String prefix) {
        return jarFile.stream().anyMatch(entry -> entry.getName().equals(prefix) || entry.getName().startsWith(prefix + "/"));
    }

    private void copyOriginalEntries(JarFile inputJar, JarOutputStream outputStream, String loaderEntryName, String nativeDir,
                                     String loaderInternalName, NativeRegistrationPlan registrationPlan) throws IOException {
        Set<String> skippedPrefixes = Set.of(loaderEntryName, nativeDir + "/");
        HashSet<String> writtenEntries = new HashSet<>();
        Map<String, Set<NativeMethodClassRewriter.MethodKey>> nativeMethodsByClass = registrationPlan.nativeMethodsByClass();
        Map<String, Integer> classIndexes = registrationPlan.classIndexByInternalName();
        inputJar.stream().forEach(entry -> {
            try {
                if (shouldSkip(entry.getName(), skippedPrefixes) || isSignatureEntry(entry.getName())
                        || !writtenEntries.add(entry.getName())) {
                    return;
                }
                outputStream.putNextEntry(copyEntryMetadata(entry));
                try (InputStream input = inputJar.getInputStream(entry)) {
                    byte[] content = input.readAllBytes();
                    if ("META-INF/MANIFEST.MF".equals(entry.getName())) {
                        content = sanitizeManifest(content);
                    }
                    String internalName = classInternalName(entry.getName());
                    if (internalName != null && nativeMethodsByClass.containsKey(internalName)) {
                        content = nativeMethodClassRewriter.rewrite(content, nativeMethodsByClass.get(internalName));
                    }
                    if (internalName != null && classIndexes.containsKey(internalName)) {
                        content = loaderInitClassRewriter.injectLoaderCalls(content, loaderInternalName, classIndexes.get(internalName));
                    }
                    outputStream.write(content);
                }
                outputStream.closeEntry();
            } catch (IOException exception) {
                throw new JarWriteException(exception);
            }
        });
    }

    private String classInternalName(String entryName) {
        if (!entryName.endsWith(".class")) {
            return null;
        }
        return entryName.substring(0, entryName.length() - ".class".length());
    }

    private boolean shouldSkip(String entryName, Set<String> skippedPrefixes) {
        for (String skippedPrefix : skippedPrefixes) {
            if (skippedPrefix.endsWith("/")) {
                if (entryName.startsWith(skippedPrefix)) {
                    return true;
                }
                continue;
            }
            if (entryName.equals(skippedPrefix)) {
                return true;
            }
        }
        return false;
    }

    private boolean isSignatureEntry(String entryName) {
        String upper = entryName.toUpperCase(java.util.Locale.ROOT);
        if (!upper.startsWith("META-INF/")) {
            return false;
        }
        String simpleName = upper.substring("META-INF/".length());
        return simpleName.endsWith(".SF")
                || simpleName.endsWith(".RSA")
                || simpleName.endsWith(".DSA")
                || simpleName.endsWith(".EC")
                || simpleName.startsWith("SIG-");
    }

    private byte[] sanitizeManifest(byte[] content) throws IOException {
        Manifest manifest = new Manifest(new ByteArrayInputStream(content));
        Attributes mainAttributes = manifest.getMainAttributes();
        Attributes sanitizedMain = new Attributes();
        for (Map.Entry<Object, Object> attribute : mainAttributes.entrySet()) {
            Attributes.Name name = (Attributes.Name) attribute.getKey();
            if (isSignatureAttribute(name.toString())) {
                continue;
            }
            sanitizedMain.put(name, attribute.getValue());
        }
        if (sanitizedMain.getValue(Attributes.Name.MANIFEST_VERSION) == null) {
            sanitizedMain.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        }
        Manifest sanitized = new Manifest();
        sanitized.getMainAttributes().putAll(sanitizedMain);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        sanitized.write(out);
        return out.toByteArray();
    }

    private boolean isSignatureAttribute(String attributeName) {
        String upper = attributeName.toUpperCase(java.util.Locale.ROOT);
        return upper.contains("DIGEST")
                || upper.equals("SIGNATURE-VERSION")
                || upper.endsWith("-DIGEST-MANIFEST")
                || upper.endsWith("-DIGEST-MANIFEST-MAIN-ATTRIBUTES");
    }

    private JarEntry copyEntryMetadata(JarEntry source) {
        JarEntry target = new JarEntry(source.getName());
        target.setComment(source.getComment());
        target.setTime(source.getTime());
        if (source.getExtra() != null) {
            target.setExtra(source.getExtra());
        }
        return target;
    }

    private void writeEntry(JarOutputStream outputStream, String entryName, byte[] content) throws IOException {
        JarEntry entry = new JarEntry(entryName);
        outputStream.putNextEntry(entry);
        outputStream.write(content);
        outputStream.closeEntry();
    }

    private NativeRegistrationPlan throwLegacyRegistrationPlanError() {
        throw new UnsupportedOperationException("Use NativeRegistrationPlan-based repack() overload");
    }

    public record RepackResult(Path outputJar, String nativeDir, String loaderEntryName) {
    }

    private static class JarWriteException extends RuntimeException {
        private JarWriteException(IOException cause) {
            super(cause);
        }
    }
}
