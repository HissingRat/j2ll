package xyz.melodysky.packaging;

import java.nio.file.Path;
import java.util.List;

public record JarSignatureResignResult(
        boolean successful,
        String reasonCode,
        String reason,
        Path signerExecutable,
        List<String> command,
        int exitCode) {
    public JarSignatureResignResult {
        command = List.copyOf(command);
    }

    public static JarSignatureResignResult succeeded(Path signerExecutable, List<String> command, int exitCode) {
        return new JarSignatureResignResult(
                true,
                "SIGNATURE_RESIGNED",
                "output JAR was signed with the configured key",
                signerExecutable,
                command,
                exitCode);
    }

    public static JarSignatureResignResult failed(
            String reasonCode,
            String reason,
            Path signerExecutable,
            List<String> command,
            int exitCode) {
        return new JarSignatureResignResult(false, reasonCode, reason, signerExecutable, command, exitCode);
    }
}
