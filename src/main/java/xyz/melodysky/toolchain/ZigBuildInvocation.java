package xyz.melodysky.toolchain;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

public record ZigBuildInvocation(
        Path zigExecutable,
        Path workingDirectory,
        List<String> command,
        Path logFile) {
    public ZigBuildInvocation {
        Objects.requireNonNull(zigExecutable, "zigExecutable");
        Objects.requireNonNull(workingDirectory, "workingDirectory");
        command = List.copyOf(Objects.requireNonNull(command, "command"));
        Objects.requireNonNull(logFile, "logFile");
    }
}
