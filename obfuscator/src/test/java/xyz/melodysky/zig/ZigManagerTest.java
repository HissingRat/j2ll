package xyz.melodysky.zig;

import xyz.melodysky.config.BuildTarget;
import com.rfksystems.blake2b.security.Blake2b512Digest;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ZigManagerTest {

    @Test
    public void testBuildCommandUsesTargetOnly() throws Exception {
        Path workspace = Files.createTempDirectory("zig-manager-test-");
        ZigManager manager = new ZigManager(workspace, workspace);

        Method method = ZigManager.class.getDeclaredMethod("createBuildCommand", String.class, BuildTarget.class);
        method.setAccessible(true);

        @SuppressWarnings("unchecked")
        List<String> command = (List<String>) method.invoke(manager, "zig", BuildTarget.LINUX_X64);

        assertEquals(List.of("zig", "build", "-Dtarget=x86_64-linux"), command);
    }

    @Test
    public void testSplitCommandOutputIsFlushedToConsoleAndLog() throws Exception {
        Path workspace = Files.createTempDirectory("zig-manager-test-");
        Path logFile = workspace.resolve("zig-build.log");
        List<String> command = createJavaEmitterCommand(workspace, "EmitHelloWorld", """
                public class EmitHelloWorld {
                    public static void main(String[] args) {
                        System.out.print("hello ");
                        System.out.print("world\\n");
                    }
                }
                """);

        ByteArrayOutputStream capturedStdout = new ByteArrayOutputStream();
        PrintStream originalStdout = System.out;
        try {
            System.setOut(new PrintStream(capturedStdout, true, StandardCharsets.UTF_8));
            runCommandThroughRunner(workspace, logFile, command);
        } finally {
            System.setOut(originalStdout);
        }

        assertEquals("hello world\n", Files.readString(logFile).replace("\r\n", "\n"));
        assertTrue(capturedStdout.toString(StandardCharsets.UTF_8).contains("hello world"));
    }

    @Test
    public void testCarriageReturnOutputIsWrittenToLog() throws Exception {
        Path workspace = Files.createTempDirectory("zig-manager-test-");
        Path logFile = workspace.resolve("zig-build.log");
        List<String> command = createJavaEmitterCommand(workspace, "EmitCarriageReturn", """
                public class EmitCarriageReturn {
                    public static void main(String[] args) {
                        System.out.print("step 1\\rstep 2\\r");
                    }
                }
                """);

        runCommandThroughRunner(workspace, logFile, command);

        assertEquals("step 1\nstep 2\n", Files.readString(logFile).replace("\r\n", "\n"));
    }

    @Test
    public void testFinalOverallBuildProgressFormat() throws Exception {
        Path workspace = Files.createTempDirectory("zig-manager-test-");
        ZigManager manager = new ZigManager(workspace, workspace);

        Method method = ZigManager.class.getDeclaredMethod("formatOverallBuildProgress", int.class, int.class, boolean.class);
        method.setAccessible(true);

        String progress = (String) method.invoke(manager, 4, 4, true);

        assertEquals("Build progress: [================] 4/4 completed.", progress);
    }

    @Test
    public void testWindowsConsoleProgressKeepsExistingTwoLineFormat() throws Exception {
        Path workspace = Files.createTempDirectory("zig-manager-test-");
        ZigManager manager = new ZigManager(workspace, workspace);

        Method method = ZigManager.class.getDeclaredMethod(
                "updateBuildProgress",
                BuildTarget.class,
                int.class,
                int.class,
                int.class,
                String.class
        );
        method.setAccessible(true);

        String originalOsName = System.getProperty("os.name");
        ByteArrayOutputStream capturedStdout = new ByteArrayOutputStream();
        PrintStream originalStdout = System.out;
        try {
            System.setProperty("os.name", "Windows 11");
            System.setOut(new PrintStream(capturedStdout, true, StandardCharsets.UTF_8));

            method.invoke(manager, BuildTarget.WINDOWS_X64, 1, 4, 2, "compiled native");
        } finally {
            if (originalOsName == null) {
                System.clearProperty("os.name");
            } else {
                System.setProperty("os.name", originalOsName);
            }
            System.setOut(originalStdout);
        }

        String output = capturedStdout.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("(x86_64-windows) - compiled native"));
        assertTrue(output.contains("Build progress: "));
        assertTrue(output.contains("\n"));
        assertTrue(!output.contains(" | "));
    }

    @Test
    public void testInstallLocalZigReusesVerifiedCachedArchive() throws Exception {
        Path application = Files.createTempDirectory("zig-manager-app-");
        Path workspace = Files.createTempDirectory("zig-manager-work-");
        RecordingZigManager manager = new RecordingZigManager(application, workspace, 0);

        String archiveName = detectHostArchiveName();
        Path archive = application.resolve(archiveName);
        Path signature = application.resolve(archiveName + ".minisig");
        Files.writeString(archive, "cached archive");
        Files.writeString(signature, "cached signature");

        invokeInstallLocalZig(manager);

        assertEquals(List.of(), manager.downloadedFiles);
        assertEquals(1, manager.verifyCalls);
        assertEquals(1, manager.extractCalls);
        assertTrue(Files.exists(archive));
        assertTrue(Files.exists(signature));
        assertTrue(Files.exists(application.resolve("zig-0.15.2")));
    }

    @Test
    public void testInstallLocalZigRedownloadsArchiveAfterVerificationFailure() throws Exception {
        Path application = Files.createTempDirectory("zig-manager-app-");
        Path workspace = Files.createTempDirectory("zig-manager-work-");
        RecordingZigManager manager = new RecordingZigManager(application, workspace, 1);

        String archiveName = detectHostArchiveName();
        Path archive = application.resolve(archiveName);
        Path signature = application.resolve(archiveName + ".minisig");
        Files.writeString(archive, "stale archive");
        Files.writeString(signature, "stale signature");

        invokeInstallLocalZig(manager);

        assertEquals(2, manager.verifyCalls);
        assertEquals(List.of(archive.getFileName().toString(), signature.getFileName().toString()), manager.downloadedFiles);
        assertTrue(Files.exists(archive));
        assertTrue(Files.exists(signature));
        assertTrue(Files.exists(application.resolve("zig-0.15.2")));
    }

    @Test
    public void testVerifyArchiveSignatureAcceptsValidMinisig() throws Exception {
        Path workspace = Files.createTempDirectory("zig-manager-test-");
        Path archive = workspace.resolve("zig-test.zip");
        Files.writeString(archive, "zig test archive");

        SyntheticMinisign syntheticMinisign = createSyntheticMinisign(archive);
        Path signature = workspace.resolve("zig-test.zip.minisig");
        Files.writeString(signature, syntheticMinisign.signatureFileContent(), StandardCharsets.UTF_8);

        MinisignVerifier.verify(archive, signature, archive.getFileName().toString(), syntheticMinisign.publicKey());
    }

    @Test
    public void testVerifyArchiveSignatureRejectsMismatchedTrustedCommentFileName() throws Exception {
        Path workspace = Files.createTempDirectory("zig-manager-test-");
        Path archive = workspace.resolve("zig-test.zip");
        Files.writeString(archive, "zig test archive");

        SyntheticMinisign syntheticMinisign = createSyntheticMinisign(archive);
        Path signature = workspace.resolve("zig-test.zip.minisig");
        Files.writeString(signature, syntheticMinisign.signatureFileContent(), StandardCharsets.UTF_8);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> MinisignVerifier.verify(archive, signature, "zig-other.zip", syntheticMinisign.publicKey()));
        assertTrue(error.getMessage().contains("expected archive"));
    }

    private void invokeInstallLocalZig(ZigManager manager) throws Exception {
        Method method = ZigManager.class.getDeclaredMethod("installLocalZig");
        method.setAccessible(true);
        method.invoke(manager);
    }

    private void runCommandThroughRunner(Path workspace, Path logFile, List<String> command) throws Exception {
        CommandRunner runner = new CommandRunner(workspace, isWindows(), System.getProperty("java.home"));
        runner.run(command, logFile, null, new CommandRunner.OutputHandler<Void>() {
            @Override
            public void onCommandStart(Path ignoredLogFile, String renderedCommand, boolean mirrorToConsole, Void context) {
            }

            @Override
            public void onPartialOutput(String text, boolean mirrorToConsole, Void context) {
                if (mirrorToConsole) {
                    System.out.print(text);
                }
            }

            @Override
            public boolean onLine(Path targetLogFile, String line, boolean carriageReturn, boolean mirrorToConsole, Void context) throws Exception {
                Files.writeString(targetLogFile, line + System.lineSeparator(), StandardCharsets.UTF_8,
                        java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
                if (mirrorToConsole) {
                    if (carriageReturn) {
                        System.out.print("\r" + line);
                    } else {
                        System.out.print(line + "\n");
                    }
                }
                return true;
            }
        });
    }

    private List<String> createJavaEmitterCommand(Path workspace, String className, String source) throws Exception {
        Path sourceFile = workspace.resolve(className + ".java");
        Files.writeString(sourceFile, source, StandardCharsets.UTF_8);

        String javaHome = System.getProperty("java.home");
        Path javac = Path.of(javaHome, "bin", isWindows() ? "javac.exe" : "javac");
        Process compile = new ProcessBuilder(javac.toString(), sourceFile.getFileName().toString())
                .directory(workspace.toFile())
                .redirectErrorStream(true)
                .start();
        String compilerOutput = new String(compile.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exitCode = compile.waitFor();
        if (exitCode != 0) {
            throw new IllegalStateException("Failed to compile test emitter: " + compilerOutput);
        }

        Path java = Path.of(javaHome, "bin", isWindows() ? "java.exe" : "java");
        return List.of(java.toString(), "-cp", workspace.toString(), className);
    }

    private String detectHostArchiveName() {
        String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch").toLowerCase(Locale.ROOT);

        String zigArch = (arch.contains("aarch64") || arch.contains("arm64")) ? "aarch64" : "x86_64";
        String zigOs;
        boolean zipArchive;
        if (os.contains("mac")) {
            zigOs = "macos";
            zipArchive = false;
        } else if (os.contains("win")) {
            zigOs = "windows";
            zipArchive = true;
        } else if (os.contains("linux")) {
            zigOs = "linux";
            zipArchive = false;
        } else {
            throw new IllegalStateException("Unsupported host OS for test: " + os);
        }
        return "zig-" + zigArch + "-" + zigOs + "-0.15.2" + (zipArchive ? ".zip" : ".tar.xz");
    }

    private SyntheticMinisign createSyntheticMinisign(Path archive) throws Exception {
        byte[] keyId = new byte[]{1, 3, 5, 7, 9, 11, 13, 15};

        KeyPairGenerator generator = KeyPairGenerator.getInstance("Ed25519");
        KeyPair keyPair = generator.generateKeyPair();
        byte[] publicKey = Arrays.copyOfRange(keyPair.getPublic().getEncoded(), 12, 44);

        byte[] publicKeyBlock = new byte[42];
        publicKeyBlock[0] = 'E';
        publicKeyBlock[1] = 'd';
        System.arraycopy(keyId, 0, publicKeyBlock, 2, keyId.length);
        System.arraycopy(publicKey, 0, publicKeyBlock, 10, publicKey.length);

        byte[] archiveHash = blake2b512(Files.readAllBytes(archive));
        byte[] archiveSignature = sign(keyPair.getPrivate(), archiveHash);
        String trustedComment = "timestamp:123456789\tfile:" + archive.getFileName() + "\thashed";
        byte[] commentSignature = sign(keyPair.getPrivate(),
                concatenate(archiveSignature, trustedComment.getBytes(StandardCharsets.UTF_8)));

        byte[] signatureBlock = new byte[74];
        signatureBlock[0] = 'E';
        signatureBlock[1] = 'D';
        System.arraycopy(keyId, 0, signatureBlock, 2, keyId.length);
        System.arraycopy(archiveSignature, 0, signatureBlock, 10, archiveSignature.length);

        String signatureFileContent = String.join("\n",
                "untrusted comment: signature from minisign secret key",
                Base64.getEncoder().encodeToString(signatureBlock),
                "trusted comment: " + trustedComment,
                Base64.getEncoder().encodeToString(commentSignature)
        ) + "\n";

        return new SyntheticMinisign(
                Base64.getEncoder().encodeToString(publicKeyBlock),
                signatureFileContent
        );
    }

    private byte[] blake2b512(byte[] input) {
        Blake2b512Digest digest = new Blake2b512Digest();
        digest.update(input, 0, input.length);
        return digest.digest();
    }

    private byte[] sign(PrivateKey privateKey, byte[] message) throws Exception {
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(privateKey);
        signer.update(message);
        return signer.sign();
    }

    private byte[] concatenate(byte[] first, byte[] second) {
        byte[] result = new byte[first.length + second.length];
        System.arraycopy(first, 0, result, 0, first.length);
        System.arraycopy(second, 0, result, first.length, second.length);
        return result;
    }

    private boolean isWindows() {
        return System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
    }

    private record SyntheticMinisign(String publicKey, String signatureFileContent) {
    }

    private static class RecordingZigManager extends ZigManager {
        private final List<String> downloadedFiles = new ArrayList<>();
        private int remainingVerificationFailures;
        private int verifyCalls;
        private int extractCalls;

        private RecordingZigManager(Path applicationDirectory, Path workspaceDirectory, int remainingVerificationFailures) {
            super(applicationDirectory, workspaceDirectory);
            this.remainingVerificationFailures = remainingVerificationFailures;
        }

        @Override
        protected void downloadFile(String url, Path destination, Path logFile) throws Exception {
            downloadedFiles.add(destination.getFileName().toString());
            Files.writeString(destination, "downloaded from " + url, StandardCharsets.UTF_8);
        }

        @Override
        protected void verifyArchiveSignature(Path archive, Path signatureFile, String expectedArchiveName) {
            verifyCalls++;
            if (remainingVerificationFailures > 0) {
                remainingVerificationFailures--;
                throw new IllegalStateException("synthetic verification failure");
            }
        }

        @Override
        protected void extractArchive(Path archive, Path extractDir, boolean zipArchive, Path logFile) throws Exception {
            extractCalls++;
            Path extractedRoot = extractDir.resolve("zig-host");
            Files.createDirectories(extractedRoot);
            Files.writeString(extractedRoot.resolve("zig.exe"), "zig", StandardCharsets.UTF_8);
            Files.writeString(extractedRoot.resolve("zig"), "zig", StandardCharsets.UTF_8);
        }
    }
}
