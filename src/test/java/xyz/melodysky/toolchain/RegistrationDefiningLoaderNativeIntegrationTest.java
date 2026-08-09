package xyz.melodysky.toolchain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import xyz.melodysky.packaging.MethodTableHidingPlanner;
import xyz.melodysky.packaging.NativeLoaderClassGenerator;
import xyz.melodysky.packaging.NativeRegistrationPlan;
import xyz.melodysky.packaging.RuntimeLoaderPlan;
import xyz.melodysky.testsupport.RegistrationDefiningLoaderFixture;
import xyz.melodysky.testsupport.RegistrationDefiningLoaderHarness;
import xyz.melodysky.toolchain.nativetext.GeneratedCFragmentTextObfuscator;
import xyz.melodysky.toolchain.nativetext.NativeTextBuildKey;
import xyz.melodysky.toolchain.nativetext.NativeTextCEmitter;

final class RegistrationDefiningLoaderNativeIntegrationTest {
    @TempDir
    Path temp;

    @Test
    void inProgressOwnerClinitLoadsRegistersThenInvokesItsNativeHelper()
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
                RegistrationDefiningLoaderFixture.registrationPlan();
        RuntimeLoaderPlan loaderPlan = RuntimeLoaderPlan.create(
                RegistrationDefiningLoaderFixture.PACKAGE_INTERNAL_NAME);
        String registration = new HostNativeRegistrationSource().emit(
                registrationPlan,
                new MethodTableHidingPlanner().plan(
                        registrationPlan,
                        false,
                        0L),
                loaderPlan,
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
                + new NativeTextCEmitter().runtimeSource()
                + runtime
                + """
                static jint j2ll_registration_fixture_clinit_body_calls = 0;
                static jint j2ll_registration_fixture_constructor_body_calls = 0;

                static void j2ll_registration_fixture_clinit_body(
                        JNIEnv* env,
                        jclass owner) {
                    (void)env;
                    (void)owner;
                    j2ll_registration_fixture_clinit_body_calls++;
                }

                static jint j2ll_registration_fixture_clinit_calls(
                        JNIEnv* env,
                        jclass owner) {
                    (void)env;
                    (void)owner;
                    return j2ll_registration_fixture_clinit_body_calls;
                }

                static void j2ll_registration_fixture_constructor_body(
                        JNIEnv* env,
                        jclass owner,
                        jobject self,
                        jint value) {
                    jfieldID value_field = (*env)->GetFieldID(env, owner, "value", "I");
                    if (value_field == NULL) {
                        return;
                    }
                    (*env)->SetIntField(env, self, value_field, value);
                    if ((*env)->ExceptionCheck(env)) {
                        return;
                    }
                    j2ll_registration_fixture_constructor_body_calls++;
                }

                static jint j2ll_registration_fixture_constructor_calls(
                        JNIEnv* env,
                        jclass owner) {
                    (void)env;
                    (void)owner;
                    return j2ll_registration_fixture_constructor_body_calls;
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

        NativeBuildUnit unit = buildPlan.units().get(0);
        assertTrue(Files.isRegularFile(unit.outputPath()));
        String libraryResource = RegistrationDefiningLoaderFixture.PACKAGE_INTERNAL_NAME
                + "/"
                + unit.target().libraryFileName();
        NativeLibraryArtifact artifact = new NativeLibraryArtifact(
                unit.target(),
                unit.outputPath(),
                wrapper,
                libraryResource,
                sha256(unit.outputPath()),
                List.of("JNI_OnLoad"));
        byte[] loaderClass = new NativeLoaderClassGenerator().generate(
                loaderPlan,
                List.of(artifact));
        Map<String, byte[]> fixtureEntries = new TreeMap<>(
                RegistrationDefiningLoaderFixture.classEntries(loaderClass));
        fixtureEntries.put(
                libraryResource,
                Files.readAllBytes(unit.outputPath()));
        Path fixtureJar = temp.resolve("registration-owner.jar");
        writeJar(fixtureJar, fixtureEntries);

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
                        fixtureJar.toString())
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
        assertEquals("clinit=1,constructor=1,value=7", stdout.trim());
    }

    private String sha256(Path path) throws Exception {
        return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
    }

    private void writeJar(
            Path jar,
            Map<String, byte[]> entries) throws Exception {
        try (JarOutputStream output =
                new JarOutputStream(Files.newOutputStream(jar))) {
            for (Map.Entry<String, byte[]> fixtureEntry
                    : new TreeMap<>(entries).entrySet()) {
                JarEntry entry = new JarEntry(fixtureEntry.getKey());
                entry.setTime(0L);
                output.putNextEntry(entry);
                output.write(fixtureEntry.getValue());
                output.closeEntry();
            }
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
