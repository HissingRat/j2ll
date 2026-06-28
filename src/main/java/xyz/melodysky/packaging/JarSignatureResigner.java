package xyz.melodysky.packaging;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import xyz.melodysky.config.SigningConfig;

public final class JarSignatureResigner {
    private final Function<String, String> environment;

    public JarSignatureResigner() {
        this(System::getenv);
    }

    public JarSignatureResigner(Function<String, String> environment) {
        this.environment = Objects.requireNonNull(environment, "environment");
    }

    public JarSignatureResignResult sign(Path jar, SigningConfig signing) {
        Objects.requireNonNull(jar, "jar");
        Objects.requireNonNull(signing, "signing");
        Path signer = jarsignerExecutable();
        if (!Files.isRegularFile(signer) || !Files.isExecutable(signer)) {
            return JarSignatureResignResult.failed(
                    "SIGNATURE_RESIGN_TOOL_UNAVAILABLE",
                    "jarsigner is not available at " + signer,
                    signer,
                    List.of(),
                    -1);
        }
        String storePassword = environment.apply(signing.storePasswordEnv());
        String keyPassword = environment.apply(signing.keyPasswordEnv());
        if (storePassword == null || storePassword.isEmpty()
                || keyPassword == null || keyPassword.isEmpty()) {
            return JarSignatureResignResult.failed(
                    "SIGNATURE_RESIGN_MISSING_PASSWORD_ENV",
                    "signer execution requires configured store and key password environment variables",
                    signer,
                    List.of(),
                    -1);
        }
        List<String> command = command(signer, jar, signing, storePassword, keyPassword);
        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.redirectErrorStream(true);
        try {
            Process process = processBuilder.start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int exitCode = process.waitFor();
            if (exitCode == 0) {
                return JarSignatureResignResult.succeeded(signer, redacted(command), exitCode);
            }
            return JarSignatureResignResult.failed(
                    "SIGNATURE_RESIGN_FAILED",
                    "jarsigner failed with exit code " + exitCode + ": " + abbreviate(output),
                    signer,
                    redacted(command),
                    exitCode);
        } catch (IOException exception) {
            return JarSignatureResignResult.failed(
                    "SIGNATURE_RESIGN_FAILED",
                    "could not execute jarsigner: " + exception.getMessage(),
                    signer,
                    redacted(command),
                    -1);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return JarSignatureResignResult.failed(
                    "SIGNATURE_RESIGN_FAILED",
                    "jarsigner execution was interrupted",
                    signer,
                    redacted(command),
                    -1);
        }
    }

    private List<String> command(Path signer, Path jar, SigningConfig signing, String storePassword, String keyPassword) {
        ArrayList<String> command = new ArrayList<>();
        command.add(signer.toString());
        command.add("-keystore");
        command.add(signing.keystorePath().toString());
        command.add("-storepass");
        command.add(storePassword);
        command.add("-keypass");
        command.add(keyPassword);
        command.add("-storetype");
        command.add("PKCS12");
        if (signing.tsaUrl() != null && !signing.tsaUrl().isBlank()) {
            command.add("-tsa");
            command.add(signing.tsaUrl());
        }
        command.add(jar.toString());
        command.add(signing.keyAlias());
        return List.copyOf(command);
    }

    private List<String> redacted(List<String> command) {
        ArrayList<String> redacted = new ArrayList<>(command);
        for (int index = 0; index < redacted.size() - 1; index++) {
            if (redacted.get(index).equals("-storepass") || redacted.get(index).equals("-keypass")) {
                redacted.set(index + 1, "<redacted>");
            }
        }
        return List.copyOf(redacted);
    }

    private Path jarsignerExecutable() {
        String executable = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")
                ? "jarsigner.exe"
                : "jarsigner";
        return Path.of(System.getProperty("java.home"), "bin", executable);
    }

    private String abbreviate(String output) {
        String normalized = output == null ? "" : output.replace('\n', ' ').replace('\r', ' ').trim();
        if (normalized.length() <= 400) {
            return normalized;
        }
        return normalized.substring(0, 400) + "...";
    }
}
