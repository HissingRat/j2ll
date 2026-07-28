package xyz.melodysky.protection.audit;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

public record GhidraHeadlessRequest(
        Path ghidraHome,
        Path projectDirectory,
        String projectName,
        Path nativeLibrary,
        Path scriptDirectory,
        String postScript,
        List<String> scriptArguments) {
    public GhidraHeadlessRequest {
        ghidraHome = normalized(ghidraHome, "ghidraHome");
        projectDirectory = normalized(projectDirectory, "projectDirectory");
        nativeLibrary = normalized(nativeLibrary, "nativeLibrary");
        scriptDirectory = normalized(scriptDirectory, "scriptDirectory");
        Objects.requireNonNull(projectName, "projectName");
        Objects.requireNonNull(postScript, "postScript");
        if (projectName.isBlank() || postScript.isBlank()) {
            throw new IllegalArgumentException(
                    "Ghidra project and post-script names must not be blank");
        }
        scriptArguments = List.copyOf(Objects.requireNonNull(
                scriptArguments,
                "scriptArguments"));
    }

    private static Path normalized(Path path, String name) {
        return Objects.requireNonNull(path, name)
                .toAbsolutePath()
                .normalize();
    }
}
