package xyz.melodysky.toolchain;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

final class ZigTargetCompletionMonitor {
    private static final String MARKER_DIRECTORY = "zig-progress";
    private static final String COMPILE_MARKER_PREFIX = "j2ll-target-compile-v1:";
    private static final String LINKING_MARKER_PREFIX = "j2ll-target-linking-v1:";
    private static final String MARKER_PREFIX = "j2ll-target-complete-v1:";

    private final ZigBuildWorkspace workspace;
    private final List<ZigBuildProgressPlan.TargetPlan> targets;
    private final NativeBuildProgressListener listener;
    private final boolean detailedProgress;
    private final Set<TargetTriple> completedTargets = new LinkedHashSet<>();
    private final Set<TargetTriple> linkingTargets = new LinkedHashSet<>();
    private final Map<TargetTriple, Set<String>> completedCompileUnits = new LinkedHashMap<>();
    private final Map<TargetTriple, NativeTargetProgress> publishedProgress = new LinkedHashMap<>();

    ZigTargetCompletionMonitor(
            ZigBuildWorkspace workspace,
            NativeBuildPlan buildPlan,
            NativeBuildProgressListener listener) {
        this(workspace, ZigBuildProgressPlan.linkOnly(buildPlan), listener);
    }

    ZigTargetCompletionMonitor(
            ZigBuildWorkspace workspace,
            ZigBuildProgressPlan progressPlan,
            NativeBuildProgressListener listener) {
        this.workspace = Objects.requireNonNull(workspace, "workspace");
        ZigBuildProgressPlan requiredPlan = Objects.requireNonNull(progressPlan, "progressPlan");
        this.targets = List.copyOf(requiredPlan.targets());
        this.detailedProgress = requiredPlan.detailedProgress();
        this.listener = Objects.requireNonNull(listener, "listener");
    }

    void prepare() throws IOException {
        cleanupDirectory(workspace);
        Path directory = progressDirectory(workspace);
        Files.createDirectories(directory);
        completedTargets.clear();
        linkingTargets.clear();
        completedCompileUnits.clear();
        for (ZigBuildProgressPlan.TargetPlan target : targets) {
            completedCompileUnits.put(target.target(), new LinkedHashSet<>());
        }
        publishedProgress.clear();
    }

    void cleanup() throws IOException {
        cleanupDirectory(workspace);
    }

    void poll() {
        for (ZigBuildProgressPlan.TargetPlan target : targets) {
            if (completedTargets.contains(target.target())) {
                continue;
            }
            NativeTargetProgress progress = readProgress(target);
            if (progress.state() == NativeTargetBuildState.COMPLETED) {
                completedTargets.add(target.target());
            }
            if (!detailedProgress) {
                if (progress.state() == NativeTargetBuildState.COMPLETED) {
                    listener.targetCompleted(
                            progress.target(),
                            completedTargets.size(),
                            targets.size());
                }
            } else if (!progress.equals(publishedProgress.put(target.target(), progress))) {
                listener.targetProgress(progress, completedTargets.size(), targets.size());
            }
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

    static Path compileMarkerPath(
            ZigBuildWorkspace workspace,
            TargetTriple target,
            ZigBuildProgressPlan.CompileUnit compileUnit) {
        return markerFile(workspace, target.directoryName() + "." + compileUnit.id() + ".done");
    }

    static String compileMarkerContent(
            TargetTriple target,
            ZigBuildProgressPlan.CompileUnit compileUnit) {
        return COMPILE_MARKER_PREFIX + target.directoryName() + ":" + compileUnit.id() + "\n";
    }

    static Path linkingMarkerPath(ZigBuildWorkspace workspace, TargetTriple target) {
        return markerFile(workspace, target.directoryName() + ".linking");
    }

    static String linkingMarkerContent(TargetTriple target, int compileUnits) {
        return LINKING_MARKER_PREFIX + target.directoryName() + ":" + compileUnits + "\n";
    }

    private NativeTargetProgress readProgress(ZigBuildProgressPlan.TargetPlan target) {
        int totalUnits = target.totalUnits();
        if (isComplete(target.buildUnit())) {
            return new NativeTargetProgress(
                    target.target(),
                    NativeTargetBuildState.COMPLETED,
                    totalUnits,
                    totalUnits);
        }
        int completedCompileUnits = completedCompileUnits(target);
        boolean linking = completedCompileUnits == target.compileUnits().size()
                && (linkingTargets.contains(target.target())
                        || hasMarker(
                                linkingMarkerPath(workspace, target.target()),
                                linkingMarkerContent(
                                        target.target(),
                                        target.compileUnits().size())));
        if (linking) {
            linkingTargets.add(target.target());
        }
        return new NativeTargetProgress(
                target.target(),
                linking ? NativeTargetBuildState.LINKING : NativeTargetBuildState.BUILDING,
                completedCompileUnits,
                totalUnits);
    }

    private int completedCompileUnits(ZigBuildProgressPlan.TargetPlan target) {
        Set<String> completed = completedCompileUnits.computeIfAbsent(
                target.target(),
                ignored -> new LinkedHashSet<>());
        for (ZigBuildProgressPlan.CompileUnit compileUnit : target.compileUnits()) {
            if (!completed.contains(compileUnit.id()) && hasMarker(
                    compileMarkerPath(workspace, target.target(), compileUnit),
                    compileMarkerContent(target.target(), compileUnit))) {
                completed.add(compileUnit.id());
            }
        }
        return completed.size();
    }

    private boolean isComplete(NativeBuildUnit unit) {
        if (!hasMarker(markerPath(workspace, unit.target()), markerContent(unit.target()))) {
            return false;
        }
        try {
            return Files.isRegularFile(unit.outputPath(), LinkOption.NOFOLLOW_LINKS)
                    && Files.size(unit.outputPath()) > 0L;
        } catch (NoSuchFileException ignored) {
            return false;
        } catch (IOException ignored) {
            return false;
        }
    }

    private boolean hasMarker(Path marker, String expectedContent) {
        try {
            return Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS)
                    && Files.readString(marker, StandardCharsets.UTF_8).equals(expectedContent);
        } catch (NoSuchFileException ignored) {
            return false;
        } catch (IOException ignored) {
            return false;
        }
    }

    private static Path markerFile(ZigBuildWorkspace workspace, String fileName) {
        Path directory = progressDirectory(workspace);
        Path marker = directory.resolve(fileName).toAbsolutePath().normalize();
        if (!marker.startsWith(directory)) {
            throw new IllegalArgumentException("Zig target progress marker escapes progress directory: " + marker);
        }
        return marker;
    }

    static Path progressDirectory(ZigBuildWorkspace workspace) {
        return workspace.logsDirectory()
                .resolve(MARKER_DIRECTORY)
                .toAbsolutePath()
                .normalize();
    }

    static void cleanupDirectory(ZigBuildWorkspace workspace) throws IOException {
        Path directory = progressDirectory(workspace);
        if (Files.notExists(directory, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Path normalized = path.toAbsolutePath().normalize();
                if (!normalized.startsWith(directory)) {
                    throw new IOException(
                            "Zig progress cleanup escaped its transient directory: " + normalized);
                }
                Files.deleteIfExists(normalized);
            }
        }
    }
}
