package xyz.melodysky.toolchain;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

final class ZigTargetCompletionMonitor {
    private static final String MARKER_DIRECTORY = "zig-progress";
    private static final String MARKER_PREFIX = "j2ll-target-complete-v1:";

    private final ZigBuildWorkspace workspace;
    private final List<NativeBuildUnit> units;
    private final NativeBuildProgressListener listener;
    private final Set<TargetTriple> completedTargets = new LinkedHashSet<>();

    ZigTargetCompletionMonitor(
            ZigBuildWorkspace workspace,
            NativeBuildPlan buildPlan,
            NativeBuildProgressListener listener) {
        this.workspace = Objects.requireNonNull(workspace, "workspace");
        this.units = List.copyOf(Objects.requireNonNull(buildPlan, "buildPlan").units());
        this.listener = Objects.requireNonNull(listener, "listener");
    }

    void prepare() throws IOException {
        Path directory = progressDirectory(workspace);
        Files.createDirectories(directory);
        for (NativeBuildUnit unit : units) {
            Files.deleteIfExists(markerPath(workspace, unit.target()));
        }
        completedTargets.clear();
    }

    void poll() {
        for (NativeBuildUnit unit : units) {
            if (completedTargets.contains(unit.target()) || !isComplete(unit)) {
                continue;
            }
            completedTargets.add(unit.target());
            listener.targetCompleted(unit.target(), completedTargets.size(), units.size());
        }
    }

    List<TargetTriple> completedTargets() {
        return List.copyOf(completedTargets);
    }

    static Path markerPath(ZigBuildWorkspace workspace, TargetTriple target) {
        Path directory = progressDirectory(workspace);
        Path marker = directory.resolve(target.directoryName() + ".done").toAbsolutePath().normalize();
        if (!marker.startsWith(directory)) {
            throw new IllegalArgumentException("Zig target completion marker escapes progress directory: " + marker);
        }
        return marker;
    }

    static String markerContent(TargetTriple target) {
        return MARKER_PREFIX + target.directoryName() + "\n";
    }

    private boolean isComplete(NativeBuildUnit unit) {
        Path marker = markerPath(workspace, unit.target());
        try {
            if (!Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS)
                    || !Files.readString(marker, StandardCharsets.UTF_8).equals(markerContent(unit.target()))) {
                return false;
            }
            return Files.isRegularFile(unit.outputPath(), LinkOption.NOFOLLOW_LINKS)
                    && Files.size(unit.outputPath()) > 0L;
        } catch (NoSuchFileException ignored) {
            return false;
        } catch (IOException ignored) {
            return false;
        }
    }

    private static Path progressDirectory(ZigBuildWorkspace workspace) {
        return workspace.logsDirectory()
                .resolve(MARKER_DIRECTORY)
                .toAbsolutePath()
                .normalize();
    }
}
