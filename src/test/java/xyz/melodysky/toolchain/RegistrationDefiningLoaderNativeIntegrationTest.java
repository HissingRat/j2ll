package xyz.melodysky.toolchain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.objectweb.asm.Opcodes.ACC_NATIVE;
import static org.objectweb.asm.Opcodes.ACC_PUBLIC;
import static org.objectweb.asm.Opcodes.ACC_STATIC;
import static org.objectweb.asm.Opcodes.ACC_SUPER;
import static org.objectweb.asm.Opcodes.ALOAD;
import static org.objectweb.asm.Opcodes.IRETURN;
import static org.objectweb.asm.Opcodes.V17;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import xyz.melodysky.packaging.MethodTableHidingPlanner;
import xyz.melodysky.packaging.NativeRegistrationEntry;
import xyz.melodysky.packaging.NativeRegistrationPlan;
import xyz.melodysky.testsupport.RegistrationDefiningLoaderHarness;
import xyz.melodysky.toolchain.nativetext.GeneratedCFragmentTextObfuscator;
import xyz.melodysky.toolchain.nativetext.NativeTextBuildKey;

final class RegistrationDefiningLoaderNativeIntegrationTest {
    @TempDir
    Path temp;

    @Test
    void jniOnLoadFindClassUsesTheSystemLoadDefiningLoaderWhenTcclIsNull()
            throws Exception {
        Path zigExecutable = realZigExecutable();
        assumeTrue(
                zigExecutable != null && Files.isRegularFile(zigExecutable),
                "set J2LL_REAL_ZIG to a Zig 0.15.2 executable");
        ZigCommandResult version = ZigCommandRunner.process().run(
                List.of(zigExecutable.toString(), "version"),
                zigExecutable.getParent(),
                Map.of());
        assumeTrue(
                version.exitCode() == 0
                        && version.stdout().trim().equals("0.15.2"),
                "registration loader fixture requires Zig 0.15.2");
        HostPlatform host = HostPlatform.detect().orElse(null);
        assumeTrue(host != null, "host platform is unsupported");

        NativeTextBuildKey buildKey =
                NativeTextBuildKey.fromUtf8("registration-loader-fixture");
        NativeRegistrationPlan registrationPlan =
                new NativeRegistrationPlan(List.of(
                        new NativeRegistrationEntry(
                                "registration/fixture/Owner",
                                "value",
                                "()I",
                                "j2ll_registration_fixture_value")));
        String registration = new HostNativeRegistrationSource().emit(
                registrationPlan,
                new MethodTableHidingPlanner().plan(
                        registrationPlan,
                        false,
                        0L),
                buildKey);
        String runtime = new GeneratedCFragmentTextObfuscator().obfuscate(
                buildKey,
                "registration-loader-runtime",
                HostJniRegistrationRuntimeSource.helperSource());
        String source = """
                #include <jni.h>
                #include <stdatomic.h>
                #include <stddef.h>
                #include <stdint.h>
                #include <stdlib.h>
                #include <string.h>

                """
                + runtime
                + """
                static jint j2ll_registration_fixture_value(
                        JNIEnv* env,
                        jclass owner) {
                    (void)env;
                    (void)owner;
                    return 42;
                }

                """
                + registration;

        NativeBuildPlan buildPlan = new NativeBuildPlanner().plan(
                temp,
                "j2ll_registration_loader",
                List.of(host.target()));
        ZigBuildWorkspace workspace = ZigBuildWorkspace.under(temp);
        Files.createDirectories(workspace.jniDirectory());
        Files.createDirectories(workspace.llvmDirectory());
        Files.createDirectories(workspace.logsDirectory());
        Path wrapper = workspace.jniDirectory()
                .resolve("registration_loader.c");
        Files.writeString(wrapper, source, StandardCharsets.UTF_8);
        ZigSourceSet sourceSet = new ZigSourceSet(
                List.of(),
                List.of(wrapper),
                List.of(),
                new ZigJniHeaderSet().prepare(workspace));
        new ZigBuildWriter().write(
                workspace,
                "j2ll_registration_loader",
                buildPlan,
                new ZigInputSet(sourceSet));
        new ZigBuildInvoker().invoke(
                new ManagedZig(
                        zigExecutable,
                        zigExecutable.getParent(),
                        "0.15.2",
                        "testProvidedVerifiedExecutable"),
                workspace,
                buildPlan,
                sourceSet,
                NativeBuildProgressListener.none());

        Path fixtureJar = temp.resolve("registration-owner.jar");
        writeJar(
                fixtureJar,
                "registration/fixture/Owner.class",
                ownerClass());
        NativeBuildUnit unit = buildPlan.units().get(0);
        assertTrue(Files.isRegularFile(unit.outputPath()));

        String javaExecutable = Path.of(
                        System.getProperty("java.home"),
                        "bin",
                        isWindows() ? "java.exe" : "java")
                .toString();
        Process process = new ProcessBuilder(
                        javaExecutable,
                        "-cp",
                        System.getProperty("java.class.path"),
                        RegistrationDefiningLoaderHarness.class.getName(),
                        fixtureJar.toString(),
                        unit.outputPath().toString())
                .redirectErrorStream(false)
                .start();
        String stdout = new String(
                process.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);
        String stderr = new String(
                process.getErrorStream().readAllBytes(),
                StandardCharsets.UTF_8);
        int exitCode = process.waitFor();

        assertEquals(0, exitCode, stderr);
        assertEquals("42", stdout.trim());
    }

    private byte[] ownerClass() {
        ClassWriter writer = new ClassWriter(
                ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(
                V17,
                ACC_PUBLIC | ACC_SUPER,
                "registration/fixture/Owner",
                null,
                "java/lang/Object",
                null);
        MethodVisitor value = writer.visitMethod(
                ACC_PUBLIC | ACC_STATIC | ACC_NATIVE,
                "value",
                "()I",
                null,
                null);
        value.visitEnd();
        MethodVisitor loadAndValue = writer.visitMethod(
                ACC_PUBLIC | ACC_STATIC,
                "loadAndValue",
                "(Ljava/lang/String;)I",
                null,
                null);
        loadAndValue.visitCode();
        loadAndValue.visitVarInsn(ALOAD, 0);
        loadAndValue.visitMethodInsn(
                Opcodes.INVOKESTATIC,
                "java/lang/System",
                "load",
                "(Ljava/lang/String;)V",
                false);
        loadAndValue.visitMethodInsn(
                Opcodes.INVOKESTATIC,
                "registration/fixture/Owner",
                "value",
                "()I",
                false);
        loadAndValue.visitInsn(IRETURN);
        loadAndValue.visitMaxs(0, 0);
        loadAndValue.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private void writeJar(
            Path jar,
            String entryName,
            byte[] bytes) throws Exception {
        try (JarOutputStream output =
                new JarOutputStream(Files.newOutputStream(jar))) {
            JarEntry entry = new JarEntry(entryName);
            entry.setTime(0L);
            output.putNextEntry(entry);
            output.write(bytes);
            output.closeEntry();
        }
    }

    private Path realZigExecutable() {
        String configured = System.getProperty("j2ll.realZig");
        if (configured == null || configured.isBlank()) {
            configured = System.getenv("J2LL_REAL_ZIG");
        }
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured);
        }
        String path = System.getenv("PATH");
        if (path == null || path.isBlank()) {
            return null;
        }
        String executable = isWindows() ? "zig.exe" : "zig";
        for (String directory : path.split(
                java.util.regex.Pattern.quote(File.pathSeparator))) {
            if (!directory.isBlank()) {
                Path candidate = Path.of(directory).resolve(executable);
                if (Files.isRegularFile(candidate)) {
                    return candidate;
                }
            }
        }
        return null;
    }

    private boolean isWindows() {
        return System.getProperty("os.name", "")
                .toLowerCase(java.util.Locale.ROOT)
                .contains("win");
    }
}
