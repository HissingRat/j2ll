package xyz.melodysky.toolchain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.nio.file.Files;
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

    private NativeBuildUnit unit(TargetTriple target) {
        return new NativeBuildUnit(
                target,
                temp.resolve("native").resolve(target.libraryFileName()),
                "j2lltest");
    }
}
