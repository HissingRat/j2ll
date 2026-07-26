package xyz.melodysky.toolchain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ZigBuildInvokerTest {
    @TempDir
    Path temp;

    @Test
    void invocationUsesManagedZigBuildWithoutHostCcFallback() {
        ManagedZig zig = new ManagedZig(
                temp.resolve("j2ll-home/zig/zig"),
                temp.resolve("j2ll-home/zig"),
                "0.15.2",
                "checksumSignatureInterfacePresent:notYetHardcoded");

        ZigBuildInvocation invocation = new ZigBuildInvoker().invocation(zig, ZigBuildWorkspace.under(temp));

        assertEquals(zig.executable().toString(), invocation.command().get(0));
        assertEquals("build", invocation.command().get(1));
        String commandTail = String.join(" ", invocation.command().subList(1, invocation.command().size()))
                .toLowerCase(Locale.ROOT);
        assertFalse(commandTail.contains("zig cc"));
        assertFalse(commandTail.contains("clang"));
        assertFalse(commandTail.contains("gcc"));
        assertFalse(commandTail.matches(".*(^|\\s)cc(\\s|$).*"));
        assertFalse(commandTail.matches(".*(^|\\s)ld(\\s|$).*"));
    }

    @Test
    void reportsTargetsAsTheirInstalledCompletionMarkersAppear() throws Exception {
        ZigBuildWorkspace workspace = ZigBuildWorkspace.under(temp);
        NativeBuildUnit linux = unit(TargetTriple.LINUX_X64);
        NativeBuildUnit windows = unit(TargetTriple.WINDOWS_X64);
        NativeBuildPlan plan = new NativeBuildPlan(List.of(linux, windows));
        CountDownLatch firstCallback = new CountDownLatch(1);
        CopyOnWriteArrayList<String> events = new CopyOnWriteArrayList<>();
        ZigCommandRunner runner = (command, workingDirectory, environment) -> {
            writeCompletion(workspace, windows);
            try {
                if (!firstCallback.await(2, TimeUnit.SECONDS)) {
                    throw new IOException("target completion was not observed while Zig was still running");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IOException("synthetic Zig runner interrupted", exception);
            }
            writeCompletion(workspace, linux);
            return new ZigCommandResult(0, "", "");
        };

        new ZigBuildInvoker(runner).invoke(
                managedZig(),
                workspace,
                plan,
                new NativeBuildProgressListener() {
                    @Override
                    public void buildStarted(List<TargetTriple> targets) {
                        events.add("started:" + String.join(",", targets.stream()
                                .map(TargetTriple::directoryName)
                                .toList()));
                    }

                    @Override
                    public void targetCompleted(TargetTriple target, int completed, int total) {
                        events.add(target.directoryName() + ":" + completed + "/" + total);
                        if (target == TargetTriple.WINDOWS_X64) {
                            firstCallback.countDown();
                        }
                    }
                });

        assertEquals(List.of(
                "started:linux-x64,windows-x64",
                "windows-x64:1/2",
                "linux-x64:2/2"), events);
        assertProgressMarkersDeleted(workspace);
    }

    @Test
    void reportsCompileUnitsAndLinkBoundaryWhileOneMatrixInvocationRuns() throws Exception {
        ZigBuildWorkspace workspace = ZigBuildWorkspace.under(temp);
        NativeBuildUnit linux = unit(TargetTriple.LINUX_X64);
        NativeBuildPlan plan = new NativeBuildPlan(List.of(linux));
        ZigSourceSet sources = new ZigSourceSet(
                List.of(workspace.llvmDirectory().resolve("owner.ll")),
                List.of(
                        workspace.jniDirectory().resolve("wrapper.c"),
                        workspace.runtimeDirectory().resolve("runtime.c")),
                List.of(),
                List.of());
        ZigBuildProgressPlan.TargetPlan targetPlan =
                ZigBuildProgressPlan.forSources(plan, sources).targets().get(0);
        CountDownLatch firstCompileObserved = new CountDownLatch(1);
        CountDownLatch linkingObserved = new CountDownLatch(1);
        CopyOnWriteArrayList<String> events = new CopyOnWriteArrayList<>();
        ZigCommandRunner runner = (command, workingDirectory, environment) -> {
            writeCompileMarker(workspace, targetPlan, 0);
            awaitProgress(firstCompileObserved, "first compile unit");
            writeCompileMarker(workspace, targetPlan, 1);
            writeCompileMarker(workspace, targetPlan, 2);
            writeLinkingMarker(workspace, targetPlan);
            awaitProgress(linkingObserved, "linking boundary");
            writeCompletion(workspace, linux);
            return new ZigCommandResult(0, "", "");
        };

        new ZigBuildInvoker(runner).invoke(
                managedZig(),
                workspace,
                plan,
                sources,
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
                        events.add(progress.state() + ":"
                                + progress.completedUnits() + "/" + progress.totalUnits());
                        if (progress.state() == NativeTargetBuildState.BUILDING
                                && progress.completedUnits() == 1) {
                            firstCompileObserved.countDown();
                        }
                        if (progress.state() == NativeTargetBuildState.LINKING) {
                            linkingObserved.countDown();
                        }
                    }
                });

        assertEquals(List.of(
                "BUILDING:0/4",
                "BUILDING:1/4",
                "LINKING:3/4",
                "COMPLETED:4/4"), events);
        assertProgressMarkersDeleted(workspace);
    }

    @Test
    void clearsStaleMarkersBeforeStartingTheMatrixBuild() throws Exception {
        ZigBuildWorkspace workspace = ZigBuildWorkspace.under(temp);
        NativeBuildUnit linux = unit(TargetTriple.LINUX_X64);
        NativeBuildPlan plan = new NativeBuildPlan(List.of(linux));
        Path marker = ZigTargetCompletionMonitor.markerPath(workspace, linux.target());
        Files.createDirectories(marker.getParent());
        Files.createDirectories(linux.outputPath().getParent());
        Files.writeString(marker, ZigTargetCompletionMonitor.markerContent(linux.target()));
        Files.write(linux.outputPath(), new byte[] {1});
        AtomicBoolean markerWasCleared = new AtomicBoolean();
        ZigCommandRunner runner = (command, workingDirectory, environment) -> {
            markerWasCleared.set(Files.notExists(marker));
            return new ZigCommandResult(0, "", "");
        };
        CopyOnWriteArrayList<TargetTriple> completed = new CopyOnWriteArrayList<>();

        new ZigBuildInvoker(runner).invoke(
                managedZig(),
                workspace,
                plan,
                (target, current, total) -> completed.add(target));

        assertTrue(markerWasCleared.get());
        assertEquals(List.of(), completed);
        assertProgressMarkersDeleted(workspace);
    }

    @Test
    void failedMatrixReportsOnlyTargetsWithValidCompletionEvidence() throws Exception {
        ZigBuildWorkspace workspace = ZigBuildWorkspace.under(temp);
        NativeBuildUnit linux = unit(TargetTriple.LINUX_X64);
        NativeBuildUnit windows = unit(TargetTriple.WINDOWS_X64);
        NativeBuildPlan plan = new NativeBuildPlan(List.of(linux, windows));
        ZigCommandRunner runner = (command, workingDirectory, environment) -> {
            writeCompletion(workspace, linux);
            return new ZigCommandResult(1, "", "synthetic link failure");
        };
        CopyOnWriteArrayList<TargetTriple> completed = new CopyOnWriteArrayList<>();

        IOException failure = assertThrows(
                IOException.class,
                () -> new ZigBuildInvoker(runner).invoke(
                        managedZig(),
                        workspace,
                        plan,
                        (target, current, total) -> completed.add(target)));

        assertTrue(failure.getMessage().contains("exit code 1"));
        assertEquals(List.of(TargetTriple.LINUX_X64), completed);
        assertTrue(Files.readString(workspace.logsDirectory().resolve("zig-build.log"))
                .contains("synthetic link failure"));
        assertProgressMarkersDeleted(workspace);
    }

    @Test
    void runnerFailureStillDeletesTransientProgressMarkers() throws Exception {
        ZigBuildWorkspace workspace = ZigBuildWorkspace.under(temp);
        NativeBuildUnit linux = unit(TargetTriple.LINUX_X64);
        NativeBuildPlan plan = new NativeBuildPlan(List.of(linux));
        ZigCommandRunner runner = (command, workingDirectory, environment) -> {
            Path marker = ZigTargetCompletionMonitor.markerPath(workspace, linux.target());
            Files.createDirectories(marker.getParent());
            Files.writeString(marker, ZigTargetCompletionMonitor.markerContent(linux.target()));
            throw new IOException("synthetic runner failure");
        };

        IOException failure = assertThrows(
                IOException.class,
                () -> new ZigBuildInvoker(runner).invoke(
                        managedZig(),
                        workspace,
                        plan,
                        NativeBuildProgressListener.none()));

        assertTrue(failure.getMessage().contains("synthetic runner failure"));
        assertProgressMarkersDeleted(workspace);
    }

    @Test
    void invocationWithoutProgressListenerAlsoDeletesBuildGraphMarkers() throws Exception {
        ZigBuildWorkspace workspace = ZigBuildWorkspace.under(temp);
        ZigCommandRunner runner = (command, workingDirectory, environment) -> {
            Path progressDirectory = ZigTargetCompletionMonitor.progressDirectory(workspace);
            Files.createDirectories(progressDirectory);
            Files.writeString(progressDirectory.resolve("synthetic.done"), "temporary");
            return new ZigCommandResult(0, "", "");
        };

        new ZigBuildInvoker(runner).invoke(managedZig(), workspace);

        assertProgressMarkersDeleted(workspace);
        assertTrue(Files.isRegularFile(workspace.logsDirectory().resolve("zig-build.log")));
    }

    @Test
    void interruptCancelsTheRunnerTask() throws Exception {
        ZigBuildWorkspace workspace = ZigBuildWorkspace.under(temp);
        NativeBuildPlan plan = new NativeBuildPlan(List.of(unit(TargetTriple.LINUX_X64)));
        CountDownLatch runnerStarted = new CountDownLatch(1);
        CountDownLatch runnerInterrupted = new CountDownLatch(1);
        ZigCommandRunner runner = (command, workingDirectory, environment) -> {
            runnerStarted.countDown();
            try {
                new CountDownLatch(1).await();
                return new ZigCommandResult(0, "", "");
            } catch (InterruptedException exception) {
                runnerInterrupted.countDown();
                Thread.currentThread().interrupt();
                throw new IOException("synthetic runner interrupted", exception);
            }
        };
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread invokingThread = Thread.ofVirtual().start(() -> {
            try {
                new ZigBuildInvoker(runner).invoke(
                        managedZig(),
                        workspace,
                        plan,
                        NativeBuildProgressListener.none());
            } catch (Throwable throwable) {
                failure.set(throwable);
            }
        });

        assertTrue(runnerStarted.await(2, TimeUnit.SECONDS));
        invokingThread.interrupt();
        invokingThread.join(2_000L);

        assertFalse(invokingThread.isAlive());
        assertTrue(runnerInterrupted.await(2, TimeUnit.SECONDS));
        assertTrue(failure.get() instanceof IOException);
        assertTrue(failure.get().getMessage().contains("interrupted"));
        assertProgressMarkersDeleted(workspace);
    }

    private ManagedZig managedZig() {
        return new ManagedZig(
                temp.resolve("j2ll-home/zig/zig"),
                temp.resolve("j2ll-home/zig"),
                "0.15.2",
                "test-managed-zig");
    }

    private NativeBuildUnit unit(TargetTriple target) {
        return new NativeBuildUnit(
                target,
                temp.resolve("native").resolve(target.libraryFileName()),
                "j2lltest");
    }

    private void writeCompletion(
            ZigBuildWorkspace workspace,
            NativeBuildUnit unit) throws IOException {
        Files.createDirectories(unit.outputPath().getParent());
        Files.write(unit.outputPath(), new byte[] {1});
        Path marker = ZigTargetCompletionMonitor.markerPath(workspace, unit.target());
        Files.createDirectories(marker.getParent());
        Files.writeString(marker, ZigTargetCompletionMonitor.markerContent(unit.target()));
    }

    private void writeCompileMarker(
            ZigBuildWorkspace workspace,
            ZigBuildProgressPlan.TargetPlan targetPlan,
            int index) throws IOException {
        ZigBuildProgressPlan.CompileUnit unit = targetPlan.compileUnits().get(index);
        Path marker = ZigTargetCompletionMonitor.compileMarkerPath(
                workspace,
                targetPlan.target(),
                unit);
        Files.createDirectories(marker.getParent());
        Files.writeString(
                marker,
                ZigTargetCompletionMonitor.compileMarkerContent(targetPlan.target(), unit));
    }

    private void writeLinkingMarker(
            ZigBuildWorkspace workspace,
            ZigBuildProgressPlan.TargetPlan targetPlan) throws IOException {
        Path marker = ZigTargetCompletionMonitor.linkingMarkerPath(
                workspace,
                targetPlan.target());
        Files.createDirectories(marker.getParent());
        Files.writeString(
                marker,
                ZigTargetCompletionMonitor.linkingMarkerContent(
                        targetPlan.target(),
                        targetPlan.compileUnits().size()));
    }

    private void awaitProgress(CountDownLatch latch, String description) throws IOException {
        try {
            if (!latch.await(2, TimeUnit.SECONDS)) {
                throw new IOException(description + " was not observed while Zig was still running");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("synthetic Zig runner interrupted", exception);
        }
    }

    private void assertProgressMarkersDeleted(ZigBuildWorkspace workspace) {
        assertTrue(Files.notExists(
                ZigTargetCompletionMonitor.progressDirectory(workspace),
                LinkOption.NOFOLLOW_LINKS));
    }
}
