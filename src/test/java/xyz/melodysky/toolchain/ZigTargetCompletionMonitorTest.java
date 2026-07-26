package xyz.melodysky.toolchain;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ZigTargetCompletionMonitorTest {
    @TempDir
    Path temp;

    @Test
    void clearsStaleMarkersAndReportsOnlyCompleteMarkerWithNonEmptyArtifact() throws Exception {
        ZigBuildWorkspace workspace = ZigBuildWorkspace.under(temp);
        NativeBuildUnit linux = unit(TargetTriple.LINUX_X64);
        NativeBuildUnit windows = unit(TargetTriple.WINDOWS_X64);
        NativeBuildPlan plan = new NativeBuildPlan(List.of(windows, linux));
        ArrayList<String> events = new ArrayList<>();
        ZigTargetCompletionMonitor monitor = new ZigTargetCompletionMonitor(
                workspace,
                plan,
                (target, completed, total) ->
                        events.add(target.directoryName() + ":" + completed + "/" + total));

        Path staleMarker = ZigTargetCompletionMonitor.markerPath(workspace, linux.target());
        Files.createDirectories(staleMarker.getParent());
        Files.writeString(staleMarker, ZigTargetCompletionMonitor.markerContent(linux.target()));

        monitor.prepare();

        assertFalse(Files.exists(staleMarker));
        Files.createDirectories(linux.outputPath().getParent());
        Files.write(linux.outputPath(), new byte[0]);
        Files.writeString(staleMarker, ZigTargetCompletionMonitor.markerContent(linux.target()));
        monitor.poll();
        assertEquals(List.of(), events);

        Files.write(linux.outputPath(), new byte[] {1});
        monitor.poll();
        monitor.poll();
        assertEquals(List.of("linux-x64:1/2"), events);

        Path windowsMarker = ZigTargetCompletionMonitor.markerPath(workspace, windows.target());
        Files.write(windows.outputPath(), new byte[] {2});
        Files.writeString(windowsMarker, "j2ll-target-complete-v1:windows");
        monitor.poll();
        assertEquals(List.of("linux-x64:1/2"), events);

        Files.writeString(windowsMarker, ZigTargetCompletionMonitor.markerContent(windows.target()));
        monitor.poll();
        assertEquals(List.of("linux-x64:1/2", "windows-x64:2/2"), events);
        assertEquals(List.of(TargetTriple.LINUX_X64, TargetTriple.WINDOWS_X64), monitor.completedTargets());
    }

    @Test
    void reportsCompletedGraphUnitsAndRealLinkBoundaryWithoutDuplicateEvents() throws Exception {
        ZigBuildWorkspace workspace = ZigBuildWorkspace.under(temp);
        NativeBuildUnit linux = unit(TargetTriple.LINUX_X64);
        NativeBuildPlan buildPlan = new NativeBuildPlan(List.of(linux));
        ZigSourceSet sources = new ZigSourceSet(
                List.of(workspace.llvmDirectory().resolve("owner.ll")),
                List.of(
                        workspace.jniDirectory().resolve("wrapper.c"),
                        workspace.runtimeDirectory().resolve("runtime.c")),
                List.of(),
                List.of());
        ZigBuildProgressPlan progressPlan = ZigBuildProgressPlan.forSources(buildPlan, sources);
        ZigBuildProgressPlan.TargetPlan targetPlan = progressPlan.targets().get(0);
        ArrayList<NativeTargetProgress> events = new ArrayList<>();
        ZigTargetCompletionMonitor monitor = new ZigTargetCompletionMonitor(
                workspace,
                progressPlan,
                new NativeBuildProgressListener() {
                    @Override
                    public void targetCompleted(
                            TargetTriple target,
                            int completedTargets,
                            int totalTargets) {
                    }

                    @Override
                    public void targetProgress(
                            NativeTargetProgress progress,
                            int completedTargets,
                            int totalTargets) {
                        events.add(progress);
                    }
                });

        monitor.prepare();
        monitor.poll();
        assertProgress(events.get(0), NativeTargetBuildState.BUILDING, 0, 4, 0);

        ZigBuildProgressPlan.CompileUnit first = targetPlan.compileUnits().get(0);
        writeMarker(
                ZigTargetCompletionMonitor.compileMarkerPath(workspace, linux.target(), first),
                ZigTargetCompletionMonitor.compileMarkerContent(linux.target(), first));
        writeMarker(
                ZigTargetCompletionMonitor.linkingMarkerPath(workspace, linux.target()),
                ZigTargetCompletionMonitor.linkingMarkerContent(
                        linux.target(),
                        targetPlan.compileUnits().size()));
        monitor.poll();
        assertProgress(events.get(1), NativeTargetBuildState.BUILDING, 1, 4, 25);

        for (ZigBuildProgressPlan.CompileUnit compileUnit :
                targetPlan.compileUnits().subList(1, targetPlan.compileUnits().size())) {
            writeMarker(
                    ZigTargetCompletionMonitor.compileMarkerPath(
                            workspace,
                            linux.target(),
                            compileUnit),
                    ZigTargetCompletionMonitor.compileMarkerContent(linux.target(), compileUnit));
        }
        monitor.poll();
        assertProgress(events.get(2), NativeTargetBuildState.LINKING, 3, 4, 75);

        Files.createDirectories(linux.outputPath().getParent());
        Files.write(linux.outputPath(), new byte[] {1});
        writeMarker(
                ZigTargetCompletionMonitor.markerPath(workspace, linux.target()),
                ZigTargetCompletionMonitor.markerContent(linux.target()));
        monitor.poll();
        monitor.poll();
        assertEquals(4, events.size());
        assertProgress(events.get(3), NativeTargetBuildState.COMPLETED, 4, 4, 100);
        assertEquals(List.of(TargetTriple.LINUX_X64), monitor.completedTargets());
    }

    @Test
    void cleanupDeletesTheWholeTransientProgressDirectoryAndIsIdempotent() throws Exception {
        ZigBuildWorkspace workspace = ZigBuildWorkspace.under(temp);
        NativeBuildUnit linux = unit(TargetTriple.LINUX_X64);
        ZigTargetCompletionMonitor monitor = new ZigTargetCompletionMonitor(
                workspace,
                new NativeBuildPlan(List.of(linux)),
                NativeBuildProgressListener.none());
        monitor.prepare();
        Path progressDirectory = ZigTargetCompletionMonitor.progressDirectory(workspace);
        Path zigLog = workspace.logsDirectory().resolve("zig-build.log");
        Files.writeString(zigLog, "durable diagnostic");
        Path nestedMarker = progressDirectory.resolve("nested/unexpected-stale-marker");
        Files.createDirectories(nestedMarker.getParent());
        Files.writeString(nestedMarker, "temporary");
        writeMarker(
                ZigTargetCompletionMonitor.markerPath(workspace, linux.target()),
                ZigTargetCompletionMonitor.markerContent(linux.target()));

        monitor.cleanup();
        assertDoesNotThrow(() -> monitor.cleanup());

        assertTrue(Files.notExists(progressDirectory, LinkOption.NOFOLLOW_LINKS));
        assertTrue(Files.isRegularFile(zigLog));
        assertEquals("durable diagnostic", Files.readString(zigLog));
    }

    private void assertProgress(
            NativeTargetProgress progress,
            NativeTargetBuildState state,
            int completedUnits,
            int totalUnits,
            int percentage) {
        assertEquals(state, progress.state());
        assertEquals(completedUnits, progress.completedUnits());
        assertEquals(totalUnits, progress.totalUnits());
        assertEquals(percentage, progress.percentage());
    }

    private void writeMarker(Path path, String content) throws Exception {
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
        assertTrue(Files.isRegularFile(path));
    }

    private NativeBuildUnit unit(TargetTriple target) {
        return new NativeBuildUnit(
                target,
                temp.resolve("native").resolve(target.libraryFileName()),
                "j2lltest");
    }
}
