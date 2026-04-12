package xyz.melodysky.packaging;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import xyz.melodysky.backend.llvm.JniMangler;
import xyz.melodysky.config.BuildTarget;
import xyz.melodysky.toolchain.IrNativeBuildDriver;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.jar.*;

import static org.junit.jupiter.api.Assertions.*;

public class IrJarRepackerTest {

    @Test
    public void testRepacksJarWithNativeArtifactsAndLoader() throws Exception {
        Path inputJar = createFixtureJar(false);
        Path outputDirectory = Files.createTempDirectory("ir-repack-");
        Path outputJar = outputDirectory.resolve("output.jar");
        Path libraryFile = outputDirectory.resolve("x64-windows.dll");
        Files.writeString(libraryFile, "native", StandardCharsets.UTF_8);

        try {
            IrJarRepacker.RepackResult result = new IrJarRepacker().repack(
                    inputJar,
                    outputJar,
                    List.of(new IrNativeBuildDriver.BuildArtifact(
                            BuildTarget.WINDOWS_X64,
                            libraryFile,
                            outputDirectory.resolve("build.log"),
                            new IrNativeBuildDriver.BuildTiming(0, 0, 0, 0, 0)
                    )),
                    "native0",
                    null,
                    new NativeRegistrationPlan(List.of(
                            new NativeRegistrationPlan.ClassRegistration(
                                    0,
                                    "sample/Test",
                                    List.of(new NativeRegistrationPlan.MethodRegistration("noop", "()V", JniMangler.nativeBridgeName("sample/Test", "noop", "()V")))
                            )
                    ))
            );

            assertEquals("native0", result.nativeDir());
            try (JarFile jarFile = new JarFile(outputJar.toFile())) {
                assertNotNull(jarFile.getEntry("sample/Test.class"));
                assertNotNull(jarFile.getEntry("config/app.properties"));
                assertNotNull(jarFile.getEntry("native0/Loader.class"));
                assertNotNull(jarFile.getEntry("native0/x64-windows.dll"));
                assertEquals(Opcodes.V21, classVersion(jarFile, "native0/Loader.class"));
                assertTrue(classContainsEnsureLoaded(jarFile, "sample/Test.class", "native0/Loader"));
                assertTrue(classContainsRegisterCall(jarFile, "sample/Test.class", "native0/Loader"));
                assertTrue(methodIsNative(jarFile, "sample/Test.class", "noop", "()V"));
            }
        } finally {
            Files.deleteIfExists(inputJar);
            deleteRecursively(outputDirectory);
        }
    }

    @Test
    public void testChoosesUniqueNativeDirWhenInputAlreadyContainsRequestedPrefix() throws Exception {
        Path inputJar = createFixtureJar(true);
        Path outputDirectory = Files.createTempDirectory("ir-repack-collision-");
        Path outputJar = outputDirectory.resolve("output.jar");
        Path libraryFile = outputDirectory.resolve("x64-windows.dll");
        Files.writeString(libraryFile, "native", StandardCharsets.UTF_8);

        try {
            IrJarRepacker.RepackResult result = new IrJarRepacker().repack(
                    inputJar,
                    outputJar,
                    List.of(new IrNativeBuildDriver.BuildArtifact(
                            BuildTarget.WINDOWS_X64,
                            libraryFile,
                            outputDirectory.resolve("build.log"),
                            new IrNativeBuildDriver.BuildTiming(0, 0, 0, 0, 0)
                    )),
                    "native0",
                    null,
                    new NativeRegistrationPlan(List.of(
                            new NativeRegistrationPlan.ClassRegistration(
                                    0,
                                    "sample/Test",
                                    List.of(new NativeRegistrationPlan.MethodRegistration("noop", "()V", JniMangler.nativeBridgeName("sample/Test", "noop", "()V")))
                            )
                    ))
            );

            assertEquals("native0_1", result.nativeDir());
            try (JarFile jarFile = new JarFile(outputJar.toFile())) {
                assertNotNull(jarFile.getEntry("native0/existing.bin"));
                assertNotNull(jarFile.getEntry("native0_1/Loader.class"));
                assertNotNull(jarFile.getEntry("native0_1/x64-windows.dll"));
                assertEquals(Opcodes.V21, classVersion(jarFile, "native0_1/Loader.class"));
                assertTrue(classContainsEnsureLoaded(jarFile, "sample/Test.class", "native0_1/Loader"));
                assertTrue(classContainsRegisterCall(jarFile, "sample/Test.class", "native0_1/Loader"));
                assertTrue(methodIsNative(jarFile, "sample/Test.class", "noop", "()V"));
            }
        } finally {
            Files.deleteIfExists(inputJar);
            deleteRecursively(outputDirectory);
        }
    }

    @Test
    public void testStripsJarSignaturesAndSanitizesManifest() throws Exception {
        Path inputJar = createSignedFixtureJar();
        Path outputDirectory = Files.createTempDirectory("ir-repack-signed-");
        Path outputJar = outputDirectory.resolve("output.jar");
        Path libraryFile = outputDirectory.resolve("x64-windows.dll");
        Files.writeString(libraryFile, "native", StandardCharsets.UTF_8);

        try {
            new IrJarRepacker().repack(
                    inputJar,
                    outputJar,
                    List.of(new IrNativeBuildDriver.BuildArtifact(
                            BuildTarget.WINDOWS_X64,
                            libraryFile,
                            outputDirectory.resolve("build.log"),
                            new IrNativeBuildDriver.BuildTiming(0, 0, 0, 0, 0)
                    )),
                    "native0",
                    null,
                    NativeRegistrationPlan.empty()
            );

            try (JarFile jarFile = new JarFile(outputJar.toFile())) {
                assertNull(jarFile.getEntry("META-INF/MOJANGCS.SF"));
                assertNull(jarFile.getEntry("META-INF/MOJANGCS.RSA"));
                assertNotNull(jarFile.getEntry("META-INF/MANIFEST.MF"));
                Manifest manifest = new Manifest(jarFile.getInputStream(jarFile.getEntry("META-INF/MANIFEST.MF")));
                assertEquals("sample.Main", manifest.getMainAttributes().getValue(Attributes.Name.MAIN_CLASS));
                assertTrue(manifest.getEntries().isEmpty());
            }
        } finally {
            Files.deleteIfExists(inputJar);
            deleteRecursively(outputDirectory);
        }
    }

    private Path createFixtureJar(boolean includeExistingNativeDir) throws IOException {
        Path jarPath = Files.createTempFile("ir-repack-input-", ".jar");
        try (JarOutputStream outputStream = new JarOutputStream(Files.newOutputStream(jarPath))) {
            writeClassEntry(outputStream, buildTestClass());
            outputStream.putNextEntry(new JarEntry("config/app.properties"));
            outputStream.write("name=test".getBytes(StandardCharsets.UTF_8));
            outputStream.closeEntry();
            if (includeExistingNativeDir) {
                outputStream.putNextEntry(new JarEntry("native0/existing.bin"));
                outputStream.write("existing".getBytes(StandardCharsets.UTF_8));
                outputStream.closeEntry();
            }
        }
        return jarPath;
    }

    private Path createSignedFixtureJar() throws IOException {
        Path jarPath = Files.createTempFile("ir-repack-signed-input-", ".jar");
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, "sample.Main");
        Attributes entryAttributes = new Attributes();
        entryAttributes.putValue("SHA-384-Digest", "deadbeef");
        manifest.getEntries().put("sample/Test.class", entryAttributes);
        try (JarOutputStream outputStream = new JarOutputStream(Files.newOutputStream(jarPath), manifest)) {
            writeClassEntry(outputStream, buildTestClass());
            outputStream.putNextEntry(new JarEntry("META-INF/MOJANGCS.SF"));
            outputStream.write("Signature-Version: 1.0\r\n".getBytes(StandardCharsets.UTF_8));
            outputStream.closeEntry();
            outputStream.putNextEntry(new JarEntry("META-INF/MOJANGCS.RSA"));
            outputStream.write("signed".getBytes(StandardCharsets.UTF_8));
            outputStream.closeEntry();
        }
        return jarPath;
    }

    private void writeClassEntry(JarOutputStream outputStream, ClassNode classNode) throws IOException {
        outputStream.putNextEntry(new JarEntry(classNode.name + ".class"));
        ClassWriter classWriter = new ClassWriter(0);
        classNode.accept(classWriter);
        outputStream.write(classWriter.toByteArray());
        outputStream.closeEntry();
    }

    private ClassNode buildTestClass() {
        ClassNode classNode = new ClassNode(Opcodes.ASM9);
        classNode.version = Opcodes.V21;
        classNode.access = Opcodes.ACC_PUBLIC;
        classNode.name = "sample/Test";
        classNode.superName = "java/lang/Object";

        MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "noop", "()V", null, null);
        method.instructions.add(new InsnNode(Opcodes.RETURN));
        classNode.methods.add(method);
        return classNode;
    }

    private boolean classContainsEnsureLoaded(JarFile jarFile, String entryName, String loaderInternalName) throws IOException {
        return classContainsLoaderCall(jarFile, entryName, loaderInternalName, "ensureLoaded");
    }

    private boolean classContainsRegisterCall(JarFile jarFile, String entryName, String loaderInternalName) throws IOException {
        return classContainsLoaderCall(jarFile, entryName, loaderInternalName, "registerNativesForClass");
    }

    private boolean classContainsLoaderCall(JarFile jarFile, String entryName, String loaderInternalName, String methodName) throws IOException {
        try (var input = jarFile.getInputStream(jarFile.getEntry(entryName))) {
            ClassNode classNode = new ClassNode(Opcodes.ASM9);
            new ClassReader(input).accept(classNode, 0);
            for (MethodNode methodNode : classNode.methods) {
                if (!methodNode.name.equals("<clinit>")) {
                    continue;
                }
                for (var instruction = methodNode.instructions.getFirst();
                     instruction != null;
                     instruction = instruction.getNext()) {
                    if (instruction instanceof MethodInsnNode methodInsnNode
                            && methodInsnNode.owner.equals(loaderInternalName)
                            && methodInsnNode.name.equals(methodName)) {
                        return true;
                    }
                }
            }
            return false;
        }
    }

    private boolean methodIsNative(JarFile jarFile, String entryName, String methodName, String descriptor) throws IOException {
        try (var input = jarFile.getInputStream(jarFile.getEntry(entryName))) {
            ClassNode classNode = new ClassNode(Opcodes.ASM9);
            new ClassReader(input).accept(classNode, 0);
            return classNode.methods.stream()
                    .anyMatch(method -> method.name.equals(methodName)
                            && method.desc.equals(descriptor)
                            && (method.access & Opcodes.ACC_NATIVE) != 0);
        }
    }

    private int classVersion(JarFile jarFile, String entryName) throws IOException {
        try (var input = jarFile.getInputStream(jarFile.getEntry(entryName))) {
            return new ClassReader(input).readShort(6);
        }
    }

    private void deleteRecursively(Path root) throws IOException {
        if (root == null || Files.notExists(root)) {
            return;
        }
        try (var stream = Files.walk(root)) {
            for (Path path : stream.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
