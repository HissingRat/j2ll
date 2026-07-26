package xyz.melodysky.toolchain;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class ZigBuildInvoker {
    private static final long PROGRESS_POLL_INTERVAL_MILLIS = 200L;

    private final ZigCommandRunner runner;

    public ZigBuildInvoker() {
        this(ZigCommandRunner.process());
    }

    public ZigBuildInvoker(ZigCommandRunner runner) {
        this.runner = runner;
    }

    public ZigBuildInvocation invocation(ManagedZig zig, ZigBuildWorkspace workspace) {
        ArrayList<String> command = new ArrayList<>();
        command.add(zig.executable().toString());
        command.add("build");
        command.add("--prefix");
        command.add(workspace.workspaceRoot().toAbsolutePath().normalize().toString());
        command.add("--cache-dir");
        command.add(workspace.workspaceRoot().resolve("native/zig-cache/local").toAbsolutePath().normalize().toString());
        command.add("--global-cache-dir");
        command.add(workspace.workspaceRoot().resolve("native/zig-cache/global").toAbsolutePath().normalize().toString());
        return new ZigBuildInvocation(
                zig.executable(),
                workspace.buildDirectory(),
                List.copyOf(command),
                workspace.logsDirectory().resolve("zig-build.log"));
    }

    public void invoke(ManagedZig zig, ZigBuildWorkspace workspace) throws IOException {
        ZigBuildInvocation invocation = invocation(zig, workspace);
        Files.createDirectories(invocation.logFile().getParent());
        Throwable failure = null;
        try {
            ZigTargetCompletionMonitor.cleanupDirectory(workspace);
            ZigCommandResult result = runner.run(
                    invocation.command(),
                    invocation.workingDirectory(),
                    ZigWorkspaceEnvironment.environment(workspace.workspaceRoot()));
            writeLogAndCheck(invocation, result);
        } catch (IOException | RuntimeException | Error exception) {
            failure = exception;
            throw exception;
        } finally {
            cleanupProgress(workspace, failure);
        }
    }

    public void invoke(
            ManagedZig zig,
            ZigBuildWorkspace workspace,
            NativeBuildPlan buildPlan) throws IOException {
        invoke(zig, workspace, buildPlan, NativeBuildProgressListener.none());
    }

    public void invoke(
            ManagedZig zig,
            ZigBuildWorkspace workspace,
            NativeBuildPlan buildPlan,
            NativeBuildProgressListener progressListener) throws IOException {
        invoke(
                zig,
                workspace,
                buildPlan,
                ZigBuildProgressPlan.linkOnly(buildPlan),
                progressListener);
    }

    public void invoke(
            ManagedZig zig,
            ZigBuildWorkspace workspace,
            NativeBuildPlan buildPlan,
            ZigSourceSet sources,
            NativeBuildProgressListener progressListener) throws IOException {
        invoke(
                zig,
                workspace,
                buildPlan,
                ZigBuildProgressPlan.forSources(buildPlan, sources),
                progressListener);
    }

    private void invoke(
            ManagedZig zig,
            ZigBuildWorkspace workspace,
            NativeBuildPlan buildPlan,
            ZigBuildProgressPlan progressPlan,
            NativeBuildProgressListener progressListener) throws IOException {
        Objects.requireNonNull(progressListener, "progressListener");
        ZigBuildInvocation invocation = invocation(zig, workspace);
        Files.createDirectories(invocation.logFile().getParent());
        ZigTargetCompletionMonitor monitor =
                new ZigTargetCompletionMonitor(workspace, progressPlan, progressListener);
        Throwable failure = null;
        try {
            monitor.prepare();
            progressListener.buildStarted(buildPlan.units().stream()
                    .map(NativeBuildUnit::target)
                    .toList());
            monitor.poll();
            ZigCommandResult result = runWithProgress(invocation, workspace, monitor);
            writeLogAndCheck(invocation, result);
        } catch (IOException | RuntimeException | Error exception) {
            failure = exception;
            throw exception;
        } finally {
            cleanupProgress(workspace, failure);
        }
    }

    private ZigCommandResult runWithProgress(
            ZigBuildInvocation invocation,
            ZigBuildWorkspace workspace,
            ZigTargetCompletionMonitor monitor) throws IOException {
        ExecutorService executor = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("j2ll-zig-command-", 0).factory());
        Future<ZigCommandResult> command = executor.submit(() -> runner.run(
                invocation.command(),
                invocation.workingDirectory(),
                ZigWorkspaceEnvironment.environment(workspace.workspaceRoot())));
        try {
            while (true) {
                try {
                    ZigCommandResult result =
                            command.get(PROGRESS_POLL_INTERVAL_MILLIS, TimeUnit.MILLISECONDS);
                    monitor.poll();
                    return result;
                } catch (TimeoutException ignored) {
                    monitor.poll();
                }
            }
        } catch (InterruptedException exception) {
            command.cancel(true);
            Thread.currentThread().interrupt();
            throw new IOException(
                    "managed Zig build interrupted: " + String.join(" ", invocation.command()),
                    exception);
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof IOException ioException) {
                throw ioException;
            }
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IOException(
                    "managed Zig build runner failed: " + String.join(" ", invocation.command()),
                    cause);
        } finally {
            if (!command.isDone()) {
                command.cancel(true);
            }
            executor.shutdownNow();
        }
    }

    private void writeLogAndCheck(
            ZigBuildInvocation invocation,
            ZigCommandResult result) throws IOException {
        Files.writeString(
                invocation.logFile(),
                "$ " + String.join(" ", invocation.command()) + System.lineSeparator()
                        + result.combinedOutput(),
                StandardCharsets.UTF_8);
        if (result.exitCode() != 0) {
            String output = result.combinedOutput();
            String tail = output.length() > 4000 ? output.substring(output.length() - 4000) : output;
            throw new IOException("managed Zig build failed with exit code " + result.exitCode()
                    + "; see " + invocation.logFile().toAbsolutePath()
                    + System.lineSeparator()
                    + tail);
        }
    }

    private void cleanupProgress(
            ZigBuildWorkspace workspace,
            Throwable invocationFailure) throws IOException {
        try {
            ZigTargetCompletionMonitor.cleanupDirectory(workspace);
        } catch (IOException cleanupFailure) {
            if (invocationFailure != null) {
                invocationFailure.addSuppressed(cleanupFailure);
                return;
            }
            throw cleanupFailure;
        }
    }
}
