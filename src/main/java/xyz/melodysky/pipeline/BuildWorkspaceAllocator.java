package xyz.melodysky.pipeline;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Objects;

public final class BuildWorkspaceAllocator {
    private static final DateTimeFormatter DIRECTORY_TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");

    private final Clock clock;

    public BuildWorkspaceAllocator() {
        this(Clock.systemDefaultZone());
    }

    public BuildWorkspaceAllocator(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public Path create(Path outputDirectory) throws IOException {
        Path outputRoot = Objects.requireNonNull(outputDirectory, "outputDirectory").normalize();
        Files.createDirectories(outputRoot);

        String baseName = "build_" + LocalDateTime.now(clock).format(DIRECTORY_TIMESTAMP);
        for (int suffix = 0; ; suffix++) {
            Path candidate = outputRoot.resolve(suffix == 0 ? baseName : baseName + "-" + suffix);
            try {
                Files.createDirectory(candidate);
                return candidate;
            } catch (FileAlreadyExistsException ignored) {
                // Another run already reserved this name; try the next suffix atomically.
            }
        }
    }
}
