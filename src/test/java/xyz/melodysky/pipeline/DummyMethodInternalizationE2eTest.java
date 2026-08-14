package xyz.melodysky.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import xyz.melodysky.analysis.method.PublicMethodInternalizationDiagnostics;
import xyz.melodysky.analysis.world.WholeProgramAnalysisFeature;
import xyz.melodysky.analysis.world.WholeProgramAnalysisPolicy;
import xyz.melodysky.config.ConfigLoader;
import xyz.melodysky.config.ResolvedConfig;
import xyz.melodysky.testsupport.FakeManagedZig;
import xyz.melodysky.toolchain.HostPlatform;
import xyz.melodysky.toolchain.J2llHomeResolver;
import xyz.melodysky.toolchain.TargetTriple;

class DummyMethodInternalizationE2eTest {
    @TempDir
    Path temp;

    @Test
    void currentJarOnlyPublicStaticRemovalKeepsDummyParity()
            throws Exception {
        String target =
                "zoo/basic/PrimitiveBasicCase#publicStaticLeaf!(I)I";
        String middle =
                "zoo/basic/PrimitiveBasicCase#publicStaticMiddle!(I)I";
        Path inputJar = dummyJar();
        ChildRun original = runJar(inputJar, "basic");
        Path workspace = temp.resolve("public-static-workspace");
        MainlinePipelineResult pipeline;
        try (AutoCloseable ignored = useManagedZig("public-static")) {
            pipeline = new MainlinePipeline().run(
                    config(
                            inputJar,
                            List.of(),
                            "PARTIAL_WORLD",
                            List.of(
                                    "zoo/basic/PrimitiveBasicCase#simpleInt!(II)I",
                                    middle,
                                    target),
                            List.of(middle, target)),
                    workspace,
                    xyz.melodysky.progress.BuildProgressListener.none(),
                    WholeProgramAnalysisPolicy.currentJarOnly(List.of(
                            WholeProgramAnalysisFeature
                                    .METHOD_INTERNALIZATION,
                            WholeProgramAnalysisFeature
                                    .FIELD_INTERNALIZATION)),
                    SkippedMethodApproval.allowAll());
        }

        assertSuccessfulParity(pipeline, original, "basic");
        assertFalse(jarContainsMethod(
                pipeline.outputJar(),
                "zoo/basic/PrimitiveBasicCase",
                "publicStaticLeaf",
                "(I)I"));
        assertFalse(jarContainsMethod(
                pipeline.outputJar(),
                "zoo/basic/PrimitiveBasicCase",
                "publicStaticMiddle",
                "(I)I"));
        assertTrue(jarContainsField(
                pipeline.outputJar(),
                "zoo/basic/PrimitiveBasicCase",
                "INLINE_LEAF_BIAS",
                "I"));
        assertTrue(jarContainsField(
                pipeline.outputJar(),
                "zoo/basic/PrimitiveBasicCase",
                "UNUSED_CONSTANT_LABEL",
                "Ljava/lang/String;"));
        assertKeptFieldReason(
                workspace,
                "zoo/basic/PrimitiveBasicCase",
                "INLINE_LEAF_BIAS",
                "I",
                "REFLECTION_DYNAMIC_SURFACE");
        assertKeptFieldReason(
                workspace,
                "zoo/basic/PrimitiveBasicCase",
                "UNUSED_CONSTANT_LABEL",
                "Ljava/lang/String;",
                "REFLECTION_DYNAMIC_SURFACE");
        assertTrue(pipeline.diagnostics().stream().anyMatch(diagnostic ->
                diagnostic.code().equals(
                        PublicMethodInternalizationDiagnostics
                                .UNRESOLVED_REFLECTION_RISK_ACCEPTED)));
        assertEquals(
                0,
                internalNativeOnlyCount(workspace));
        assertEquals(
                2,
                coalescedNativeOnlyCount(workspace));
        String lowering = Files.readString(workspace.resolve(
                "reports/lowering-report.json"));
        assertTrue(lowering.contains(
                "\"coalescedInto\": "
                        + "\"zoo/basic/PrimitiveBasicCase#simpleInt!(II)I\""),
                lowering);
        assertPassedArtifactCheck(
                workspace,
                "native.coalescedMethodStandaloneBodies",
                "COALESCED_NATIVE_STANDALONE_BODIES_ABSENT");
    }

    @Test
    void declaredClosedWorldRemovesNonFinalPublicAndProtectedTargetsWithParity()
            throws Exception {
        Path inputJar = compileInstanceFixture();
        Path analysisClasspath = writeObjectAnalysisClasspath();
        String publicTarget =
                "fixture/InstanceOwner#publicTarget!(I)I";
        List<String> selectors = List.of(
                "fixture/InstanceOwner#callPublic!(I)I",
                "fixture/InstanceOwner#callProtected!(I)I",
                "fixture/InstanceOwner#callProtectedStatic!(I)I",
                publicTarget,
                "fixture/InstanceOwner#protectedTarget!(I)I",
                "fixture/InstanceOwner#protectedStatic!(I)I");
        ChildRun original = runJar(inputJar, "fixture");
        Path workspace = temp.resolve("closed-world-workspace");
        MainlinePipelineResult pipeline;
        try (AutoCloseable ignored = useManagedZig("closed-world")) {
            pipeline = new MainlinePipeline().run(
                    config(
                            inputJar,
                            List.of(analysisClasspath),
                            "CLOSED_WORLD",
                            selectors,
                            List.of(publicTarget)),
                    workspace,
                    xyz.melodysky.progress.BuildProgressListener.none(),
                    WholeProgramAnalysisPolicy.strict(),
                    SkippedMethodApproval.allowAll());
        }

        assertSuccessfulParity(pipeline, original, "fixture");
        for (String method : List.of(
                "publicTarget",
                "protectedTarget",
                "protectedStatic")) {
            assertFalse(jarContainsMethod(
                    pipeline.outputJar(),
                    "fixture/InstanceOwner",
                    method,
                    "(I)I"),
                    method);
        }
        assertEquals(
                2,
                internalNativeOnlyCount(workspace));
        assertEquals(
                1,
                coalescedNativeOnlyCount(workspace));
        assertPassedArtifactCheck(
                workspace,
                "native.coalescedMethodStandaloneBodies",
                "COALESCED_NATIVE_STANDALONE_BODIES_ABSENT");
    }

    private void assertSuccessfulParity(
            MainlinePipelineResult pipeline,
            ChildRun original,
            String mode) throws Exception {
        assertTrue(
                pipeline.successful(),
                pipeline.diagnostics().toString());
        ChildRun output = runJar(pipeline.outputJar(), mode);
        assertEquals(0, original.exitCode(), original.stderr());
        assertEquals(0, output.exitCode(), output.stderr());
        assertEquals(original.stdout(), output.stdout());
        assertEquals(original.stderr(), output.stderr());
    }

    private int internalNativeOnlyCount(Path workspace)
            throws IOException {
        return retentionModeCount(workspace, "internalNativeOnly");
    }

    private int coalescedNativeOnlyCount(Path workspace)
            throws IOException {
        return retentionModeCount(workspace, "coalescedNativeOnly");
    }

    private int retentionModeCount(Path workspace, String retentionMode)
            throws IOException {
        String lowering = Files.readString(workspace.resolve(
                "reports/lowering-report.json"));
        return count(
                lowering,
                "\"retentionMode\": \"" + retentionMode + "\"");
    }

    private void assertPassedArtifactCheck(
            Path workspace,
            String name,
            String reasonCode) throws IOException {
        JsonArray checks = JsonParser.parseString(Files.readString(
                        workspace.resolve("reports/artifact-audit.json")))
                .getAsJsonObject()
                .getAsJsonArray("checks");
        assertTrue(
                checks.asList().stream()
                        .map(element -> element.getAsJsonObject())
                        .anyMatch(check -> check.get("name").getAsString()
                                        .equals(name)
                                && check.get("status").getAsString()
                                        .equals("passed")
                                && check.get("reasonCode").getAsString()
                                        .equals(reasonCode)),
                checks.toString());
    }

    private void assertKeptFieldReason(
            Path workspace,
            String owner,
            String name,
            String descriptor,
            String reasonCode) throws IOException {
        JsonArray decisions = JsonParser.parseString(Files.readString(
                        workspace.resolve("reports/field-internalization-report.json")))
                .getAsJsonObject()
                .getAsJsonArray("decisions");
        String expectedHash = sha256(owner + "#" + name + "!" + descriptor);
        assertTrue(
                decisions.asList().stream()
                        .map(element -> element.getAsJsonObject())
                        .anyMatch(decision -> decision.get("fieldIdHash")
                                        .getAsString()
                                        .equals(expectedHash)
                                && decision.get("status").getAsString()
                                        .equals("KEPT")
                                && !decision.get("removedFromOutputClass")
                                        .getAsBoolean()
                                && decision.getAsJsonArray("reasonCodes")
                                        .asList()
                                        .stream()
                                        .anyMatch(reason -> reason.getAsString()
                                                .equals(reasonCode))),
                decisions.toString());
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private ResolvedConfig config(
            Path inputJar,
            List<Path> classPath,
            String worldModel,
            List<String> selectors,
            List<String> publicAllowlist) {
        JsonObject json = JsonParser.parseString("""
                {
                  "schemaVersion": 1,
                  "jarFile": "%s",
                  "classPath": [],
                  "javaHome": null,
                  "runtimeImage": null,
                  "worldModel": "%s",
                  "outputDirectory": "out",
                  "whiteList": [],
                  "blackList": [],
                  "target": %s,
                  "embeddedLibraryDirectory": "native0",
                  "signaturePolicy": "fail",
                  "signing": null,
                  "intermediates": {
                    "enabled": false,
                    "includeDebugDumps": false,
                    "includePerClassIr": false,
                    "includePerClassLlvm": false,
                    "includePerClassC": false
                  },
                  "protection": {
                    "enabled": true,
                    "seed": "dummy-method-internalization",
                    "ir": {
                      "enabled": true,
                      "controlFlowFlattening": false,
                      "fakeBranches": false,
                      "basicBlockSplitting": false,
                      "constantEncryption": false,
                      "stringEncryption": false,
                      "methodInlining": false,
                      "methodSplitting": false,
                      "callIndirection": false,
                      "fieldInternalization": true,
                      "methodInternalization": true,
                      "publicMethodInternalizationAllowList": [],
                      "methodTableHiding": false,
                      "blockNameObfuscation": false
                    },
                    "llvm": {
                      "enabled": false,
                      "nameObfuscation": false,
                      "opaquePredicates": false,
                      "blockLayoutPerturbation": false,
                      "indirectCalls": false,
                      "globalLayout": false
                    },
                    "binary": {
                      "enabled": true,
                      "hideInternalSymbols": true,
                      "strip": true,
                      "removePdb": true,
                      "symbolAudit": true,
                      "retainUnwindInfo": false
                    }
                  }
                }
                """.formatted(
                        escaped(inputJar),
                        worldModel,
                        hostTargetJson()))
                .getAsJsonObject();
        JsonArray classPathJson = new JsonArray();
        classPath.stream()
                .map(Path::toString)
                .forEach(classPathJson::add);
        json.add("classPath", classPathJson);
        JsonArray selectorsJson = new JsonArray();
        selectors.forEach(selectorsJson::add);
        json.add("whiteList", selectorsJson);
        JsonArray allowlistJson = new JsonArray();
        publicAllowlist.forEach(allowlistJson::add);
        json.getAsJsonObject("protection")
                .getAsJsonObject("ir")
                .add(
                        "publicMethodInternalizationAllowList",
                        allowlistJson);
        return new ConfigLoader().load(json, temp)
                .config()
                .orElseThrow();
    }

    private String escaped(Path path) {
        return path.toString().replace("\\", "\\\\");
    }

    private Path dummyJar() {
        String configured = System.getProperty("j2ll.dummy.jar");
        Path jar = configured == null || configured.isBlank()
                ? Path.of("build/dummy/Dummy.jar")
                : Path.of(configured);
        return jar.toAbsolutePath().normalize();
    }

    private Path compileInstanceFixture() throws IOException {
        Path sourceRoot = temp.resolve("fixture-source/fixture");
        Path classes = temp.resolve("fixture-classes");
        Files.createDirectories(sourceRoot);
        Files.createDirectories(classes);
        Path owner = sourceRoot.resolve("InstanceOwner.java");
        Path main = sourceRoot.resolve("InstanceMain.java");
        Files.writeString(owner, """
                package fixture;

                public class InstanceOwner {
                    public int publicTarget(int value) { return value; }
                    protected int protectedTarget(int value) { return value; }
                    protected static int protectedStatic(int value) { return value; }
                    public int callPublic(int value) { return publicTarget(value); }
                    public int callProtected(int value) { return protectedTarget(value); }
                    public static int callProtectedStatic(int value) {
                        return protectedStatic(value);
                    }
                }
                """);
        Files.writeString(main, """
                package fixture;

                public final class InstanceMain {
                    public static void main(String[] args) {
                        System.out.println(new InstanceOwner().callPublic(42));
                        System.out.println(new InstanceOwner().callProtected(43));
                        System.out.println(InstanceOwner.callProtectedStatic(44));
                    }
                }
                """);
        int javac = ToolProvider.getSystemJavaCompiler().run(
                null,
                null,
                null,
                "--release",
                "17",
                "-d",
                classes.toString(),
                owner.toString(),
                main.toString());
        assertEquals(0, javac, "fixture javac");

        Path jar = temp.resolve("instance-internalization.jar");
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(
                Attributes.Name.MANIFEST_VERSION,
                "1.0");
        manifest.getMainAttributes().put(
                Attributes.Name.MAIN_CLASS,
                "fixture.InstanceMain");
        try (JarOutputStream output = new JarOutputStream(
                        Files.newOutputStream(jar),
                        manifest);
                var paths = Files.walk(classes)) {
            for (Path path : paths.filter(Files::isRegularFile)
                    .sorted()
                    .toList()) {
                writeEntry(
                        output,
                        classes.relativize(path)
                                .toString()
                                .replace('\\', '/'),
                        Files.readAllBytes(path));
            }
        }
        return jar;
    }

    private Path writeObjectAnalysisClasspath()
            throws IOException {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(
                Opcodes.V17,
                Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER,
                "java/lang/Object",
                null,
                null,
                null);
        MethodVisitor constructor = writer.visitMethod(
                Opcodes.ACC_PUBLIC,
                "<init>",
                "()V",
                null,
                null);
        constructor.visitCode();
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(0, 0);
        constructor.visitEnd();
        writer.visitEnd();
        Path jar = temp.resolve("analysis-object.jar");
        try (JarOutputStream output = new JarOutputStream(
                Files.newOutputStream(jar))) {
            writeEntry(
                    output,
                    "java/lang/Object.class",
                    writer.toByteArray());
        }
        return jar;
    }

    private void writeEntry(
            JarOutputStream output,
            String name,
            byte[] bytes) throws IOException {
        JarEntry entry = new JarEntry(name);
        entry.setTime(0L);
        output.putNextEntry(entry);
        output.write(bytes);
        output.closeEntry();
    }

    private boolean jarContainsMethod(
            Path jarPath,
            String owner,
            String name,
            String descriptor) throws IOException {
        try (JarFile jar = new JarFile(jarPath.toFile(), false)) {
            JarEntry entry = jar.getJarEntry(owner + ".class");
            if (entry == null) {
                return false;
            }
            boolean[] found = {false};
            try (var input = jar.getInputStream(entry)) {
                new ClassReader(input).accept(
                        new ClassVisitor(Opcodes.ASM9) {
                            @Override
                            public MethodVisitor visitMethod(
                                    int access,
                                    String candidateName,
                                    String candidateDescriptor,
                                    String signature,
                                    String[] exceptions) {
                                if (candidateName.equals(name)
                                        && candidateDescriptor.equals(
                                                descriptor)) {
                                    found[0] = true;
                                }
                                return null;
                            }
                        },
                        ClassReader.SKIP_CODE
                                | ClassReader.SKIP_DEBUG
                                | ClassReader.SKIP_FRAMES);
            }
            return found[0];
        }
    }

    private boolean jarContainsField(
            Path jarPath,
            String owner,
            String name,
            String descriptor) throws IOException {
        try (JarFile jar = new JarFile(jarPath.toFile(), false)) {
            JarEntry entry = jar.getJarEntry(owner + ".class");
            if (entry == null) {
                return false;
            }
            boolean[] found = {false};
            try (var input = jar.getInputStream(entry)) {
                new ClassReader(input).accept(
                        new ClassVisitor(Opcodes.ASM9) {
                            @Override
                            public FieldVisitor visitField(
                                    int access,
                                    String candidateName,
                                    String candidateDescriptor,
                                    String signature,
                                    Object value) {
                                if (candidateName.equals(name)
                                        && candidateDescriptor.equals(
                                                descriptor)) {
                                    found[0] = true;
                                }
                                return null;
                            }
                        },
                        ClassReader.SKIP_CODE
                                | ClassReader.SKIP_DEBUG
                                | ClassReader.SKIP_FRAMES);
            }
            return found[0];
        }
    }

    private ChildRun runJar(Path jar, String mode)
            throws IOException, InterruptedException {
        ArrayList<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"))
                .resolve("bin")
                .resolve(javaBinary())
                .toString());
        command.add("-Duser.language=en");
        command.add("-Duser.country=US");
        command.add("-Dfile.encoding=UTF-8");
        command.add("--enable-native-access=ALL-UNNAMED");
        command.add("--sun-misc-unsafe-memory-access=allow");
        command.add("-jar");
        command.add(jar.toString());
        command.add(mode);
        Process process = new ProcessBuilder(command)
                .redirectErrorStream(false)
                .start();
        if (!process.waitFor(20, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IOException("child JVM timed out: " + command);
        }
        return new ChildRun(
                process.exitValue(),
                new String(
                        process.getInputStream().readAllBytes(),
                        StandardCharsets.UTF_8),
                new String(
                        process.getErrorStream().readAllBytes(),
                        StandardCharsets.UTF_8));
    }

    private int count(String text, String needle) {
        int result = 0;
        int index = 0;
        while ((index = text.indexOf(needle, index)) >= 0) {
            result++;
            index += needle.length();
        }
        return result;
    }

    private AutoCloseable useManagedZig(String profile)
            throws Exception {
        Path realHome = realJ2llHome();
        if (realHome != null
                && Files.isRegularFile(zigExecutable(realHome))) {
            return useJ2llHome(realHome);
        }
        return FakeManagedZig.installAndUse(
                temp.resolve("j2ll-home-" + profile));
    }

    private Path realJ2llHome() {
        String configured = System.getProperty("j2ll.realHome");
        if (configured == null || configured.isBlank()) {
            configured = System.getenv("J2LL_REAL_HOME");
        }
        return configured == null || configured.isBlank()
                ? null
                : Path.of(configured)
                        .toAbsolutePath()
                        .normalize();
    }

    private Path zigExecutable(Path home) {
        return home.resolve("zig")
                .resolve(isWindows() ? "zig.exe" : "zig");
    }

    private AutoCloseable useJ2llHome(Path home) {
        String previous = System.getProperty(
                J2llHomeResolver.OVERRIDE_PROPERTY);
        System.setProperty(
                J2llHomeResolver.OVERRIDE_PROPERTY,
                home.toString());
        return () -> {
            if (previous == null) {
                System.clearProperty(
                        J2llHomeResolver.OVERRIDE_PROPERTY);
            } else {
                System.setProperty(
                        J2llHomeResolver.OVERRIDE_PROPERTY,
                        previous);
            }
        };
    }

    private String hostTargetJson() {
        TargetTriple target = HostPlatform.detect()
                .orElseThrow()
                .target();
        return """
                {
                  "windowsX64": %s,
                  "windowsArm64": %s,
                  "linuxX64": %s,
                  "linuxArm64": %s,
                  "macosX64": %s,
                  "macosArm64": %s
                }""".formatted(
                        target == TargetTriple.WINDOWS_X64,
                        target == TargetTriple.WINDOWS_ARM64,
                        target == TargetTriple.LINUX_X64,
                        target == TargetTriple.LINUX_ARM64,
                        target == TargetTriple.MACOS_X64,
                        target == TargetTriple.MACOS_ARM64);
    }

    private boolean isWindows() {
        return System.getProperty("os.name", "")
                .toLowerCase(Locale.ROOT)
                .contains("win");
    }

    private String javaBinary() {
        return isWindows() ? "java.exe" : "java";
    }

    private record ChildRun(
            int exitCode,
            String stdout,
            String stderr) {}
}
