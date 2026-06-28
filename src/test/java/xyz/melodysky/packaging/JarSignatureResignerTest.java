package xyz.melodysky.packaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import xyz.melodysky.config.SignaturePolicy;
import xyz.melodysky.config.SigningConfig;

class JarSignatureResignerTest {
    @TempDir
    Path temp;

    @Test
    void stripsOldSignatureFilesSignsOutputJarAndVerifies() throws Exception {
        Path keytool = jdkTool("keytool");
        Path jarsigner = jdkTool("jarsigner");
        assumeTrue(Files.isExecutable(keytool), "keytool is unavailable in this JDK");
        assumeTrue(Files.isExecutable(jarsigner), "jarsigner is unavailable in this JDK");

        Path keystore = temp.resolve("j2ll-test.p12");
        generateKeystore(keytool, keystore);
        Path inputJar = temp.resolve("signed-input.jar");
        Path outputJar = temp.resolve("out").resolve("signed-output.jar");
        writeJar(inputJar, Map.of(
                "META-INF/MANIFEST.MF", bytes("Manifest-Version: 1.0\r\n\r\n"),
                "META-INF/TEST.SF", bytes("Signature-Version: 1.0\r\n\r\n"),
                "META-INF/TEST.RSA", new byte[] {1, 2, 3},
                "pkg/data.txt", bytes("payload\n")));

        JarRepackager repackager = new JarRepackager();
        SignatureActionReport stripped = repackager.inspectSignature(inputJar, SignaturePolicy.RESIGN);
        repackager.write(inputJar, outputJar, Map.of(), Map.of(), SignaturePolicy.RESIGN);
        JarSignatureResignResult result = new JarSignatureResigner(Map.of(
                        "J2LL_TEST_STORE_PASS", "changeit",
                        "J2LL_TEST_KEY_PASS", "changeit")::get)
                .sign(outputJar, new SigningConfig(
                        keystore,
                        "J2LL_TEST_STORE_PASS",
                        "j2ll",
                        "J2LL_TEST_KEY_PASS",
                        null));

        assertTrue(result.successful(), result.reason());
        assertEquals("SIGNATURE_RESIGNED", result.reasonCode());
        assertTrue(stripped.removedEntries().contains("META-INF/TEST.SF"));
        try (JarFile jarFile = new JarFile(outputJar.toFile(), true)) {
            assertTrue(jarFile.getJarEntry("META-INF/TEST.SF") == null);
            assertTrue(jarFile.getJarEntry("META-INF/TEST.RSA") == null);
            assertTrue(jarFile.getJarEntry("META-INF/J2LL.SF") != null);
            assertTrue(jarFile.getJarEntry("META-INF/J2LL.RSA") != null);
        }
        assertEquals(0, verifyJar(jarsigner, outputJar), "jarsigner -verify should accept the signed output JAR");
    }

    @Test
    void reportsMissingSignerPasswordEnvironment() throws IOException {
        Path inputJar = temp.resolve("input.jar");
        Path outputJar = temp.resolve("out").resolve("output.jar");
        Path keystore = temp.resolve("missing-password.p12");
        writeJar(inputJar, Map.of("pkg/data.txt", bytes("payload\n")));
        Files.createDirectories(outputJar.getParent());
        Files.copy(inputJar, outputJar);

        JarSignatureResignResult result = new JarSignatureResigner(Map.<String, String>of()::get)
                .sign(outputJar, new SigningConfig(
                        keystore,
                        "J2LL_TEST_STORE_PASS",
                        "j2ll",
                        "J2LL_TEST_KEY_PASS",
                        null));

        assertFalse(result.successful());
        assertEquals("SIGNATURE_RESIGN_MISSING_PASSWORD_ENV", result.reasonCode());
    }

    private void generateKeystore(Path keytool, Path keystore) throws Exception {
        Process process = new ProcessBuilder(
                        keytool.toString(),
                        "-genkeypair",
                        "-alias", "j2ll",
                        "-keyalg", "RSA",
                        "-keysize", "2048",
                        "-validity", "1",
                        "-storetype", "PKCS12",
                        "-keystore", keystore.toString(),
                        "-storepass", "changeit",
                        "-keypass", "changeit",
                        "-dname", "CN=j2ll test, OU=tests, O=melodysky, L=test, ST=test, C=US",
                        "-noprompt")
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, process.waitFor(), output);
    }

    private int verifyJar(Path jarsigner, Path jar) throws Exception {
        Process process = new ProcessBuilder(jarsigner.toString(), "-verify", jar.toString())
                .redirectErrorStream(true)
                .start();
        process.getInputStream().readAllBytes();
        return process.waitFor();
    }

    private Path jdkTool(String name) {
        String executable = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")
                ? name + ".exe"
                : name;
        return Path.of(System.getProperty("java.home"), "bin", executable);
    }

    private void writeJar(Path path, Map<String, byte[]> entries) throws IOException {
        Files.createDirectories(path.getParent());
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(path))) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                JarEntry jarEntry = new JarEntry(entry.getKey());
                jarEntry.setTime(0L);
                output.putNextEntry(jarEntry);
                output.write(entry.getValue());
                output.closeEntry();
            }
        }
    }

    private byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
